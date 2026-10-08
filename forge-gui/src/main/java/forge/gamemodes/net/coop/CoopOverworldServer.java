package forge.gamemodes.net.coop;

import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopHelloRejectEvent;
import forge.util.IHasForgeLog;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.util.concurrent.Future;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dedicated Netty listener for Ascendant co-op overworld traffic (port
 * {@link CoopPorts#OVERWORLD_PORT}). Separate from {@code FServerManager} so
 * stock online play on the game port is unchanged.
 *
 * <p>One guest only. Until hello (+ session code + version) is accepted, only
 * {@link CoopHelloEvent} is forwarded to the listener; everything else is dropped.
 * UPnP is never started here.
 */
public final class CoopOverworldServer implements IHasForgeLog {
    private final int port;
    private final CoopMessageListener listener;
    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile Channel serverChannel;
    private final AtomicReference<Channel> guestChannel = new AtomicReference<>();
    private final AtomicBoolean guestAuthenticated = new AtomicBoolean(false);
    private final CountDownLatch bindLatch = new CountDownLatch(1);
    private volatile boolean bound;

    public CoopOverworldServer(final int port, final CoopMessageListener listener) {
        this.port = port;
        this.listener = listener;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        final ServerBootstrap b = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline().addLast(
                                new CompatibleObjectEncoder(null),
                                new CompatibleObjectDecoder(9766 * 1024, ClassResolvers.cacheDisabled(null)),
                                new GuestHandler());
                    }
                });
        final ChannelFuture future = b.bind(port).sync();
        serverChannel = future.channel();
        bound = true;
        bindLatch.countDown();
        netLog.info("Co-op overworld server listening on {}", port);
    }

    public boolean awaitBound(final long timeoutMs) throws InterruptedException {
        return bindLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && bound;
    }

    public int getPort() {
        return port;
    }

    public boolean hasGuest() {
        final Channel ch = guestChannel.get();
        return ch != null && ch.isActive();
    }

    public boolean isGuestAuthenticated() {
        return guestAuthenticated.get();
    }

    public void markGuestAuthenticated() {
        guestAuthenticated.set(true);
    }

    public void send(final NetEvent event) {
        final Channel ch = guestChannel.get();
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(event);
        }
    }

    /** Send a reject and close the guest channel (does not stop the server). */
    public void rejectAndClose(final String reason) {
        final Channel ch = guestChannel.get();
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(new CoopHelloRejectEvent(reason)).addListener(f -> ch.close());
        }
        guestAuthenticated.set(false);
    }

    /**
     * Stop listening. {@link EventLoopGroup#shutdownGracefully()} is always run
     * off the Netty event loop to avoid deadlock when called from a handler.
     */
    public void stop() {
        bound = false;
        guestAuthenticated.set(false);
        final Channel guest = guestChannel.getAndSet(null);
        if (guest != null) {
            guest.close();
        }
        final Channel server = serverChannel;
        serverChannel = null;
        if (server != null) {
            server.close();
        }
        final EventLoopGroup boss = bossGroup;
        final EventLoopGroup worker = workerGroup;
        bossGroup = null;
        workerGroup = null;
        shutdownGroupsOffEventLoop(boss, worker);
    }

    private void shutdownGroupsOffEventLoop(final EventLoopGroup boss, final EventLoopGroup worker) {
        final Runnable shutdown = () -> {
            try {
                if (boss != null) {
                    final Future<?> f = boss.shutdownGracefully(0, 2, TimeUnit.SECONDS);
                    f.awaitUninterruptibly(3, TimeUnit.SECONDS);
                }
            } catch (final Exception e) {
                netLog.debug("Co-op boss shutdown: {}", e.toString());
            }
            try {
                if (worker != null) {
                    final Future<?> f = worker.shutdownGracefully(0, 2, TimeUnit.SECONDS);
                    f.awaitUninterruptibly(3, TimeUnit.SECONDS);
                }
            } catch (final Exception e) {
                netLog.debug("Co-op worker shutdown: {}", e.toString());
            }
        };
        final Thread t = new Thread(shutdown, "coop-overworld-shutdown");
        t.setDaemon(true);
        t.start();
    }

    private final class GuestHandler extends SimpleChannelInboundHandler<NetEvent> {
        @Override
        public void channelActive(final ChannelHandlerContext ctx) {
            while (true) {
                final Channel previous = guestChannel.get();
                if (previous != null && previous.isActive() && previous != ctx.channel()) {
                    ctx.writeAndFlush(new CoopHelloRejectEvent(
                            "Host already has a co-op guest connected.")).addListener(f -> ctx.close());
                    return;
                }
                if (guestChannel.compareAndSet(previous, ctx.channel())) {
                    break;
                }
            }
            guestAuthenticated.set(false);
            if (listener != null) {
                listener.onConnected();
            }
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final NetEvent msg) {
            if (listener == null) {
                return;
            }
            // Until hello + session code + version pass, ignore everything else.
            if (!guestAuthenticated.get() && !(msg instanceof CoopHelloEvent)) {
                netLog.warn("Dropping {} before co-op authentication", msg.getClass().getSimpleName());
                return;
            }
            listener.onMessage(msg);
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) {
            guestChannel.compareAndSet(ctx.channel(), null);
            guestAuthenticated.set(false);
            if (listener != null) {
                listener.onDisconnected("guest disconnected");
            }
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            if (listener != null) {
                listener.onError("overworld server error", cause);
            }
            ctx.close();
        }
    }
}
