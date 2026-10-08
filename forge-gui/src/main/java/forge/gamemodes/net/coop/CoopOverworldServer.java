package forge.gamemodes.net.coop;

import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.event.NetEvent;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dedicated Netty listener for Ascendant co-op overworld traffic (port
 * {@link CoopPorts#OVERWORLD_PORT}). Separate from {@code FServerManager} so
 * stock online play on the game port is unchanged.
 *
 * <p>CO1 supports one guest. UPnP is never started here — the Adventure host
 * layer decides whether to map ports (and skips UPnP for Tailscale).
 */
public final class CoopOverworldServer implements IHasForgeLog {
    private final int port;
    private final CoopMessageListener listener;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final AtomicReference<Channel> guestChannel = new AtomicReference<>();
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

    public void send(final NetEvent event) {
        final Channel ch = guestChannel.get();
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(event);
        }
    }

    public void stop() {
        final Channel guest = guestChannel.getAndSet(null);
        if (guest != null) {
            guest.close();
        }
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        bound = false;
    }

    private final class GuestHandler extends SimpleChannelInboundHandler<NetEvent> {
        @Override
        public void channelActive(final ChannelHandlerContext ctx) {
            final Channel previous = guestChannel.getAndSet(ctx.channel());
            if (previous != null && previous != ctx.channel() && previous.isActive()) {
                // One guest only — drop the new connection.
                ctx.writeAndFlush(new forge.gamemodes.net.event.coop.CoopHelloRejectEvent(
                        "Host already has a co-op guest connected."));
                ctx.close();
                guestChannel.set(previous);
                return;
            }
            if (listener != null) {
                listener.onConnected();
            }
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final NetEvent msg) {
            if (listener != null) {
                listener.onMessage(msg);
            }
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) {
            guestChannel.compareAndSet(ctx.channel(), null);
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
