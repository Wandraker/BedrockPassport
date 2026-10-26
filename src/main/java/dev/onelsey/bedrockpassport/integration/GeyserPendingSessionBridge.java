package dev.onelsey.bedrockpassport.integration;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class GeyserPendingSessionBridge {
    private static final String BEDROCK_PEER_HANDLER = "bedrock-peer";
    private static final String LOGIN_HOLD_HANDLER = "bedrockpassport-login-hold";
    private static final List<String> UPSTREAM_PACKET_HANDLER_CLASSES = List.of(
            "org.geysermc.geyser.network.bedrock.UpstreamPacketHandler",
            "org.geysermc.geyser.network.UpstreamPacketHandler"
    );
    private static final String GEYSER_SESSION_ADAPTER = "org.geysermc.geyser.session.GeyserSessionAdapter";
    private static final String CLIENTBOUND_PLAYER_POSITION =
            "org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket";
    private static final Set<String> EARLY_POSITION_PACKETS = Set.of(
            "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket",
            "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket"
    );
    private static final long INITIAL_MOVEMENT_GUARD_TIMEOUT_MILLIS = 5_000L;

    private final Object geyser;
    private final Method getSessionManager;
    private final Method getAllSessions;
    private final Method sessionXuid;
    private final Method sessionIsSentSpawnPacket;
    private final Method sessionIsClosed;
    private final Method sessionConnect;
    private final Method sessionExecuteInEventLoop;
    private final Method sessionScheduleInEventLoop;
    private final Method sessionGetUpstream;
    private final Method sessionGetDownstream;
    private final Method upstreamIsInitialized;
    private final Method downstreamGetSession;
    private final Method clientSessionGetChannel;
    private final Method clientSessionRemoveListener;
    private final Class<?> sessionListenerClass;
    private final Method packetSendingEventGetPacket;
    private final Method packetSendingEventSetCancelled;
    private final Method sessionAuthenticate;
    private final Method sessionBedrockUsername;
    private final Method sessionDisconnect;
    private final Method upstreamGetSession;
    private final Method bedrockSessionGetPeer;
    private final Method bedrockSessionGetPacketHandler;
    private final Method bedrockPeerGetChannel;
    private final Class<?> packetWrapperClass;
    private final Method packetWrapperGetPacket;
    private final Class<?> resourcePackResponseClass;
    private final Method resourcePackResponseGetStatus;
    private final Class<?> upstreamPacketHandlerClass;
    private final Field upstreamResourcePacksFinished;
    private final GeyserFormBridge forms;
    private final Logger logger;
    private final AtomicBoolean movementGuardWarningLogged = new AtomicBoolean(false);

    public GeyserPendingSessionBridge(Plugin geyserPlugin) throws ReflectiveOperationException {
        Objects.requireNonNull(geyserPlugin, "geyserPlugin");
        ClassLoader loader = geyserPlugin.getClass().getClassLoader();

        Class<?> geyserImplClass = Class.forName("org.geysermc.geyser.GeyserImpl", true, loader);
        Class<?> sessionManagerClass = Class.forName("org.geysermc.geyser.session.SessionManager", true, loader);
        Class<?> sessionClass = Class.forName("org.geysermc.geyser.session.GeyserSession", true, loader);
        Class<?> upstreamClass = Class.forName("org.geysermc.geyser.session.UpstreamSession", true, loader);
        Class<?> downstreamClass = Class.forName("org.geysermc.geyser.session.DownstreamSession", true, loader);
        Class<?> clientSessionClass = Class.forName("org.geysermc.mcprotocollib.network.ClientSession", true, loader);
        Class<?> mcplSessionClass = Class.forName("org.geysermc.mcprotocollib.network.Session", true, loader);
        this.sessionListenerClass = Class.forName("org.geysermc.mcprotocollib.network.event.session.SessionListener", true, loader);
        Class<?> packetSendingEventClass = Class.forName(
                "org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent",
                true,
                loader
        );

        Method getInstance = geyserImplClass.getMethod("getInstance");
        this.geyser = getInstance.invoke(null);
        this.getSessionManager = geyserImplClass.getMethod("getSessionManager");
        this.getAllSessions = sessionManagerClass.getMethod("getAllSessions");
        this.sessionXuid = sessionClass.getMethod("xuid");
        this.sessionIsSentSpawnPacket = sessionClass.getMethod("isSentSpawnPacket");
        this.sessionIsClosed = sessionClass.getMethod("isClosed");
        this.sessionConnect = sessionClass.getMethod("connect");
        this.sessionExecuteInEventLoop = sessionClass.getMethod("executeInEventLoop", Runnable.class);
        this.sessionScheduleInEventLoop = sessionClass.getMethod("scheduleInEventLoop", Runnable.class, long.class, TimeUnit.class);
        this.sessionGetUpstream = sessionClass.getMethod("getUpstream");
        this.sessionGetDownstream = sessionClass.getMethod("getDownstream");
        this.upstreamIsInitialized = upstreamClass.getMethod("isInitialized");
        this.downstreamGetSession = downstreamClass.getMethod("getSession");
        this.clientSessionGetChannel = clientSessionClass.getMethod("getChannel");
        this.clientSessionRemoveListener = mcplSessionClass.getMethod("removeListener", sessionListenerClass);
        this.packetSendingEventGetPacket = packetSendingEventClass.getMethod("getPacket");
        this.packetSendingEventSetCancelled = packetSendingEventClass.getMethod("setCancelled", boolean.class);

        Class<?> bedrockSessionClass = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockSession", true, loader);
        Class<?> bedrockPeerClass = Class.forName("org.cloudburstmc.protocol.bedrock.BedrockPeer", true, loader);
        this.packetWrapperClass = Class.forName("org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper", true, loader);
        this.resourcePackResponseClass = Class.forName(
                "org.cloudburstmc.protocol.bedrock.packet.ResourcePackClientResponsePacket",
                true,
                loader
        );
        this.sessionAuthenticate = sessionClass.getMethod("authenticate", String.class);
        this.sessionBedrockUsername = sessionClass.getMethod("bedrockUsername");
        this.sessionDisconnect = sessionClass.getMethod("disconnect", String.class);
        this.upstreamGetSession = upstreamClass.getMethod("getSession");
        this.bedrockSessionGetPeer = bedrockSessionClass.getMethod("getPeer");
        this.bedrockSessionGetPacketHandler = bedrockSessionClass.getMethod("getPacketHandler");
        this.bedrockPeerGetChannel = bedrockPeerClass.getMethod("getChannel");
        this.packetWrapperGetPacket = packetWrapperClass.getMethod("getPacket");
        this.resourcePackResponseGetStatus = resourcePackResponseClass.getMethod("getStatus");
        this.upstreamPacketHandlerClass = findClass(loader, UPSTREAM_PACKET_HANDLER_CLASSES);
        this.upstreamResourcePacksFinished = upstreamPacketHandlerClass.getDeclaredField("finishedResourcePackSending");
        this.upstreamResourcePacksFinished.setAccessible(true);
        this.forms = new GeyserFormBridge(loader, sessionClass);
        this.logger = geyserPlugin.getLogger();
    }

    public SessionHandle findByXuid(String xuid) {
        try {
            Object manager = getSessionManager.invoke(geyser);
            Object all = getAllSessions.invoke(manager);
            if (!(all instanceof Collection<?> sessions)) {
                throw new IllegalStateException("Unexpected Geyser session collection type");
            }
            for (Object session : sessions) {
                if (xuid.equals(sessionXuid.invoke(session))) {
                    return new SessionHandle(session);
                }
            }
            return null;
        } catch (ReflectiveOperationException exception) {
            throw failure(exception);
        }
    }

    /**
     * Stops Geyser from opening the Java backend connection when the Bedrock client finishes
     * its resource packs. {@code onReady} then runs in the Geyser session event loop, and the
     * backend login only starts once {@link #authenticate} is called. Nothing waits on a
     * Netty thread in between, so the selector cannot stall other connections.
     */
    public void holdBackendLogin(SessionHandle handle, Runnable onReady, Consumer<Throwable> onFailure) {
        try {
            Object upstream = sessionGetUpstream.invoke(handle.session());
            Object bedrockSession = upstreamGetSession.invoke(upstream);
            Object peer = bedrockSessionGetPeer.invoke(bedrockSession);
            if (!(bedrockPeerGetChannel.invoke(peer) instanceof Channel channel)) {
                throw new IllegalStateException("Bedrock upstream channel is not available");
            }
            if (channel.pipeline().get(BEDROCK_PEER_HANDLER) == null) {
                throw new IllegalStateException("Bedrock packet dispatcher was not found in the upstream pipeline");
            }
            channel.pipeline().addBefore(
                    BEDROCK_PEER_HANDLER,
                    LOGIN_HOLD_HANDLER,
                    new BackendLoginHold(handle, bedrockSession, onReady, onFailure)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure(throwable);
        }
    }

    public void authenticate(SessionHandle handle, Consumer<Throwable> onFailure) {
        try {
            execute(handle, () -> {
                try {
                    if (isClosed(handle)) {
                        return;
                    }
                    String bedrockUsername = (String) sessionBedrockUsername.invoke(handle.session());
                    sessionAuthenticate.invoke(handle.session(), bedrockUsername);
                } catch (Throwable throwable) {
                    onFailure.accept(bridgeFailure(throwable));
                }
            });
        } catch (Throwable throwable) {
            onFailure.accept(bridgeFailure(throwable));
        }
    }

    public void disconnect(SessionHandle handle, String reason) {
        try {
            execute(handle, () -> {
                try {
                    if (!isClosed(handle)) {
                        sessionDisconnect.invoke(handle.session(), reason);
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private boolean isResourcePacksCompleted(Object message) throws ReflectiveOperationException {
        if (!packetWrapperClass.isInstance(message)) {
            return false;
        }
        Object packet = packetWrapperGetPacket.invoke(message);
        if (!resourcePackResponseClass.isInstance(packet)) {
            return false;
        }
        return resourcePackResponseGetStatus.invoke(packet) instanceof Enum<?> status
                && "COMPLETED".equals(status.name());
    }

    private final class BackendLoginHold extends ChannelInboundHandlerAdapter {
        private final SessionHandle handle;
        private final Object bedrockSession;
        private final Runnable onReady;
        private final Consumer<Throwable> onFailure;

        private BackendLoginHold(SessionHandle handle, Object bedrockSession, Runnable onReady, Consumer<Throwable> onFailure) {
            this.handle = handle;
            this.bedrockSession = bedrockSession;
            this.onReady = onReady;
            this.onFailure = onFailure;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            boolean completed;
            try {
                completed = isResourcePacksCompleted(msg);
            } catch (Throwable throwable) {
                ctx.pipeline().remove(this);
                ReferenceCountUtil.release(msg);
                onFailure.accept(bridgeFailure(throwable));
                return;
            }
            if (!completed) {
                ctx.fireChannelRead(msg);
                return;
            }

            ctx.pipeline().remove(this);
            ReferenceCountUtil.release(msg);
            try {
                Object packetHandler = bedrockSessionGetPacketHandler.invoke(bedrockSession);
                if (!upstreamPacketHandlerClass.isInstance(packetHandler)) {
                    throw new IllegalStateException("Unexpected Geyser upstream packet handler: "
                            + (packetHandler == null ? "null" : packetHandler.getClass().getName()));
                }
                // Geyser still rejects a duplicate COMPLETED response, as if it had handled this one itself.
                upstreamResourcePacksFinished.setBoolean(packetHandler, true);
                execute(handle, onReady);
            } catch (Throwable throwable) {
                onFailure.accept(bridgeFailure(throwable));
            }
        }
    }

    public boolean armInitialMovementGuard(SessionHandle handle) {
        try {
            Object downstream = sessionGetDownstream.invoke(handle.session());
            if (downstream == null) {
                return false;
            }
            Object clientSession = downstreamGetSession.invoke(downstream);
            if (clientSession == null) {
                return false;
            }

            Field listenersField = findField(clientSession.getClass(), "listeners");
            listenersField.setAccessible(true);
            Object listenersObject = listenersField.get(clientSession);
            if (!(listenersObject instanceof List<?> rawListeners)) {
                return false;
            }

            @SuppressWarnings("unchecked")
            List<Object> listeners = (List<Object>) rawListeners;
            int geyserAdapterIndex = -1;
            for (int i = 0; i < listeners.size(); i++) {
                Object listener = listeners.get(i);
                if (listener != null && GEYSER_SESSION_ADAPTER.equals(listener.getClass().getName())) {
                    geyserAdapterIndex = i;
                    break;
                }
            }
            if (geyserAdapterIndex < 0) {
                warnMovementGuardUnavailable("GeyserSessionAdapter was not found in the downstream listener chain", null);
                return false;
            }

            AtomicBoolean active = new AtomicBoolean(true);
            AtomicInteger blockedPackets = new AtomicInteger();
            Object listener = Proxy.newProxyInstance(
                    sessionListenerClass.getClassLoader(),
                    new Class<?>[]{sessionListenerClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "BedrockPassportInitialMovementGuard";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> args != null && args.length == 1 && proxy == args[0];
                                default -> null;
                            };
                        }

                        if (!active.get()) {
                            return null;
                        }

                        if ("packetReceived".equals(method.getName()) && args != null && args.length >= 2) {
                            Object packet = args[1];
                            if (packet != null && CLIENTBOUND_PLAYER_POSITION.equals(packet.getClass().getName())) {
                                if (active.compareAndSet(true, false)) {
                                    removeListenerQuietly(clientSession, proxy);
                                    int blocked = blockedPackets.get();
                                    if (blocked > 0) {
                                        logger.fine("BedrockPassport blocked " + blocked
                                                + " early movement packet(s) before the first server position.");
                                    }
                                }
                            }
                            return null;
                        }

                        if ("packetSending".equals(method.getName()) && args != null && args.length == 1) {
                            Object packet = packetSendingEventGetPacket.invoke(args[0]);
                            if (packet != null && EARLY_POSITION_PACKETS.contains(packet.getClass().getName())) {
                                packetSendingEventSetCancelled.invoke(args[0], true);
                                blockedPackets.incrementAndGet();
                            }
                            return null;
                        }

                        if (("disconnecting".equals(method.getName()) || "disconnected".equals(method.getName()))
                                && active.compareAndSet(true, false)) {
                            removeListenerQuietly(clientSession, proxy);
                        }
                        return null;
                    }
            );

            listeners.add(geyserAdapterIndex, listener);
            schedule(handle, () -> {
                if (active.compareAndSet(true, false)) {
                    removeListenerQuietly(clientSession, listener);
                }
            }, INITIAL_MOVEMENT_GUARD_TIMEOUT_MILLIS);
            return true;
        } catch (Throwable throwable) {
            warnMovementGuardUnavailable("initial movement guard could not be installed", throwable);
            return false;
        }
    }

    private void removeListenerQuietly(Object clientSession, Object listener) {
        try {
            clientSessionRemoveListener.invoke(clientSession, listener);
        } catch (Throwable ignored) {
        }
    }

    private void warnMovementGuardUnavailable(String message, Throwable throwable) {
        if (!movementGuardWarningLogged.compareAndSet(false, true)) {
            return;
        }
        String detail = throwable == null ? "" : ": " + bridgeFailure(throwable).getMessage();
        logger.warning("BedrockPassport " + message + detail + ". Continuing without the guard.");
    }

    private static Class<?> findClass(ClassLoader loader, List<String> candidates) throws ClassNotFoundException {
        for (String name : candidates) {
            try {
                return Class.forName(name, true, loader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException(String.join(" / ", candidates));
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    public CompletableFuture<SessionHandle> enterHoldingWorld(SessionHandle handle) {
        CompletableFuture<SessionHandle> future = new CompletableFuture<>();
        try {
            execute(handle, () -> {
                try {
                    if (isClosed(handle)) {
                        future.completeExceptionally(new IllegalStateException("Geyser session closed before passport selection"));
                        return;
                    }
                    if (!(boolean) sessionIsSentSpawnPacket.invoke(handle.session())) {
                        sessionConnect.invoke(handle.session());
                    }
                    future.complete(handle);
                } catch (Throwable throwable) {
                    future.completeExceptionally(bridgeFailure(throwable));
                }
            });
        } catch (Throwable throwable) {
            future.completeExceptionally(bridgeFailure(throwable));
        }
        return future;
    }

    public CompletableFuture<SessionHandle> awaitInitialized(SessionHandle handle, long initTimeoutSeconds) {
        CompletableFuture<SessionHandle> future = new CompletableFuture<>();
        try {
            execute(handle, () -> {
                try {
                    if (isClosed(handle)) {
                        future.completeExceptionally(new IllegalStateException("Geyser session closed before passport selection"));
                        return;
                    }
                    probeInitialized(handle, future, System.nanoTime() + TimeUnit.SECONDS.toNanos(initTimeoutSeconds));
                } catch (Throwable throwable) {
                    future.completeExceptionally(bridgeFailure(throwable));
                }
            });
        } catch (Throwable throwable) {
            future.completeExceptionally(bridgeFailure(throwable));
        }
        return future;
    }

    private void probeInitialized(SessionHandle handle, CompletableFuture<SessionHandle> future, long deadline) {
        if (future.isDone()) {
            return;
        }
        try {
            if (isClosed(handle)) {
                future.completeExceptionally(new IllegalStateException("Geyser session closed while initializing passport holding world"));
                return;
            }
            Object upstream = sessionGetUpstream.invoke(handle.session());
            if ((boolean) upstreamIsInitialized.invoke(upstream)) {
                future.complete(handle);
                return;
            }
            if (System.nanoTime() >= deadline) {
                future.completeExceptionally(new IllegalStateException("Geyser passport holding world initialization timed out"));
                return;
            }
            sessionScheduleInEventLoop.invoke(handle.session(), (Runnable) () -> probeInitialized(handle, future, deadline), 50L, TimeUnit.MILLISECONDS);
        } catch (Throwable throwable) {
            future.completeExceptionally(bridgeFailure(throwable));
        }
    }

    public void showNicknameForm(
            SessionHandle handle,
            String title,
            String text,
            String inputLabel,
            String placeholder,
            String initialValue,
            String error,
            Consumer<String> onSubmit,
            Runnable onClosed,
            Consumer<Throwable> onFailure
    ) {
        forms.showNicknameForm(handle.session(), title, text, inputLabel, placeholder, initialValue, error, onSubmit, onClosed, onFailure);
    }

    public void showMenu(
            SessionHandle handle,
            String title,
            String content,
            List<String> buttons,
            Consumer<Integer> onSelected,
            Runnable onClosed,
            Consumer<Throwable> onFailure
    ) {
        forms.showMenu(handle.session(), title, content, buttons, onSelected, onClosed, onFailure);
    }

    public void showConfirmation(
            SessionHandle handle,
            String title,
            String content,
            String confirmButton,
            String cancelButton,
            Consumer<Boolean> onResult,
            Runnable onClosed,
            Consumer<Throwable> onFailure
    ) {
        forms.showConfirmation(handle.session(), title, content, confirmButton, cancelButton, onResult, onClosed, onFailure);
    }

    public void schedule(SessionHandle handle, Runnable runnable, long delayMillis) {
        try {
            sessionScheduleInEventLoop.invoke(handle.session(), runnable, delayMillis, TimeUnit.MILLISECONDS);
        } catch (ReflectiveOperationException exception) {
            throw failure(exception);
        }
    }

    public boolean isClosed(SessionHandle handle) {
        try {
            return (boolean) sessionIsClosed.invoke(handle.session());
        } catch (ReflectiveOperationException exception) {
            throw failure(exception);
        }
    }

    private void execute(SessionHandle handle, Runnable runnable) {
        try {
            sessionExecuteInEventLoop.invoke(handle.session(), runnable);
        } catch (ReflectiveOperationException exception) {
            throw failure(exception);
        }
    }

    private static RuntimeException failure(ReflectiveOperationException exception) {
        return bridgeFailure(exception);
    }

    static RuntimeException bridgeFailure(Throwable throwable) {
        Throwable cause = throwable instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause()
                : throwable;
        if (cause instanceof RuntimeException runtime && runtime.getMessage() != null
                && runtime.getMessage().startsWith("Geyser internal bridge failed")) {
            return runtime;
        }
        return new IllegalStateException("Geyser internal bridge failed", cause);
    }

    public record SessionHandle(Object session) {
        public SessionHandle {
            Objects.requireNonNull(session, "session");
        }
    }
}
