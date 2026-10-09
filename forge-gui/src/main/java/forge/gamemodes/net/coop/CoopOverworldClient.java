package forge.gamemodes.net.coop;

import forge.gamemodes.net.CompatibleObjectDecoder;
import forge.gamemodes.net.CompatibleObjectEncoder;
import forge.gamemodes.net.event.NetEvent;
import forge.util.IHasForgeLog;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.util.concurrent.Future;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Guest-side Netty client for the Ascendant co-op overworld port.
 */
public final class CoopOverworldClient implements IHasForgeLog {
    private final String hostname;
    private final int port;
    private final CoopMessageListener listener;
    private volatile EventLoopGroup group;
    private volatile Channel channel;
    private final CountDownLatch connectLatch = new CountDownLatch(1);
    private volatile boolean connected;
    private volatile Throwable connectError;

    public CoopOverworldClient(final String hostname, final int port, final CoopMessageListener listener) {
        this.hostname = hostname;
        this.port = port;
        this.listener = listener;
    }

    public void connect() throws InterruptedException {
        group = new NioEventLoopGroup();
        final Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline().addLast(
                                new CompatibleObjectEncoder(null),
                                new CompatibleObjectDecoder(9766 * 1024, ClassResolvers.cacheDisabled(null)),
                                new ClientHandler());
                    }
                });
        final ChannelFuture future = b.connect(hostname, port).sync();
        channel = future.channel();
        connected = true;
        connectLatch.countDown();
        netLog.info("Co-op overworld client connected to {}:{}", hostname, port);
    }

    public boolean awaitConnected(final long timeoutMs) throws InterruptedException {
        return connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && connected && connectError == null;
    }

    public void send(final NetEvent event) {
        final Channel ch = channel;
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(event);
        }
    }

    public void disconnect() {
        connected = false;
        final Channel ch = channel;
        channel = null;
        final EventLoopGroup g = group;
        group = null;
        if (ch != null) {
            ch.close();
        }
        if (g == null) {
            return;
        }
        // Prefer the group: channel may already be null while we are still on its loop.
        final boolean inLoop = g.next().inEventLoop();
        final Runnable shutdown = () -> {
            try {
                final Future<?> f = g.shutdownGracefully(0, 2, TimeUnit.SECONDS);
                f.awaitUninterruptibly(3, TimeUnit.SECONDS);
            } catch (final Exception e) {
                netLog.debug("Co-op client shutdown: {}", e.toString());
            }
        };
        if (inLoop) {
            // Never block a Netty thread (deadlock).
            final Thread t = new Thread(shutdown, "coop-overworld-client-shutdown");
            t.setDaemon(true);
            t.start();
        } else {
            // Test tearDown / UI: wait so the next connect does not inherit zombie groups.
            shutdown.run();
        }
    }

    public boolean isConnected() {
        final Channel ch = channel;
        return connected && ch != null && ch.isActive();
    }

    private final class ClientHandler extends SimpleChannelInboundHandler<NetEvent> {
        @Override
        public void channelActive(final ChannelHandlerContext ctx) {
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
            connected = false;
            if (listener != null) {
                listener.onDisconnected("connection closed");
            }
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            connectError = cause;
            if (listener != null) {
                listener.onError("overworld client error", cause);
            }
            ctx.close();
        }
    }
}
