package dev.onelsey.bedrockpassport.integration;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.util.concurrent.TimeUnit;

public final class BackendReadTimeoutGuard {
    private final boolean suspendDuringPassport;

    public BackendReadTimeoutGuard(boolean suspendDuringPassport) {
        this.suspendDuringPassport = suspendDuringPassport;
    }

    public Lease suspend(Channel channel) {
        if (!suspendDuringPassport || channel == null) {
            return Lease.NOOP;
        }
        try {
            if (channel.eventLoop().inEventLoop()) {
                return suspend0(channel);
            }
            return channel.eventLoop().submit(() -> suspend0(channel)).get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not suspend backend login read timeout", exception);
        }
    }

    private Lease suspend0(Channel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        for (String name : pipeline.names()) {
            ChannelHandler handler = pipeline.get(name);
            if (!(handler instanceof ReadTimeoutHandler readTimeout)) {
                continue;
            }
            long originalMillis = readTimeout.getReaderIdleTimeInMillis();
            if (originalMillis <= 0L) {
                return Lease.NOOP;
            }
            ReadTimeoutHandler replacement = new ReadTimeoutHandler(0L, TimeUnit.MILLISECONDS);
            pipeline.replace(handler, name, replacement);
            return new Lease(channel, name, replacement, originalMillis);
        }
        return Lease.NOOP;
    }

    public static final class Lease implements AutoCloseable {
        private static final Lease NOOP = new Lease(null, null, null, 0L);
        private final Channel channel;
        private final String handlerName;
        private final ReadTimeoutHandler replacement;
        private final long originalTimeoutMillis;

        private Lease(Channel channel, String handlerName, ReadTimeoutHandler replacement, long originalTimeoutMillis) {
            this.channel = channel;
            this.handlerName = handlerName;
            this.replacement = replacement;
            this.originalTimeoutMillis = originalTimeoutMillis;
        }

        @Override
        public void close() {
            if (channel == null) {
                return;
            }
            Runnable restore = () -> {
                try {
                    ChannelHandler current = channel.pipeline().get(handlerName);
                    if (current == replacement) {
                        channel.pipeline().replace(
                                replacement,
                                handlerName,
                                new ReadTimeoutHandler(originalTimeoutMillis, TimeUnit.MILLISECONDS)
                        );
                    }
                } catch (Throwable ignored) {
                }
            };
            try {
                if (channel.eventLoop().inEventLoop()) {
                    restore.run();
                } else if (!channel.eventLoop().isShuttingDown()) {
                    channel.eventLoop().execute(restore);
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
