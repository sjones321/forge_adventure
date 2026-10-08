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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Guest-side Netty client for the Ascendant co-op overworld port.
 */
public final class CoopOverworldClient implements IHasForgeLog {
    private final String hostname;
    private final int port;
    private final CoopMessageListener listener;
    private EventLoopGroup group;
    private Channel channel;
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
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(event);
        }
    }

    public void disconnect() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
        connected = false;
    }

    public boolean isConnected() {
        return connected && channel != null && channel.isActive();
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
