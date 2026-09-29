package dev.onelsey.bedrockpassport.integration;

import org.bukkit.plugin.Plugin;
import org.geysermc.geyser.api.network.AuthType;
import org.geysermc.geyser.api.network.RemoteServer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class GeyserOnlineAuthBridge implements AutoCloseable {
    private final Object geyser;
    private final Method sessionRemoteServerGetter;
    private final Method sessionRemoteServerSetter;
    private final Method sessionExecuteInEventLoop;
    private final Method sessionIsClosed;
    private final Method sessionDisconnect;
    private final Method sessionBedrockUsername;
    private final Field sessionProtocol;
    private final Field sessionLoggingIn;
    private final Method sessionConnectDownstream;

    private final Method geyserGetPendingMicrosoftAuthentication;
    private final Field geyserSavedAuthChains;
    private final Method pendingGetOrCreateTask;
    private final Method pendingGetTask;
    private final Method taskResetRunningFlow;
    private final Method taskPerformLoginAttempt;
    private final Method taskCleanup;
    private final Method showMicrosoftCodeWindow;

    private final Object gson;
    private final Method gsonFromJson;
    private final Method gsonToJson;
    private final Class<?> jsonObjectClass;
    private final Object authClient;
    private final Method authManagerFromJson;
    private final Method authManagerToJson;
    private final Method authManagerGetProfile;
    private final Method authManagerGetToken;
    private final Method profileStepGetUpToDate;
    private final Method tokenStepGetUpToDate;
    private final Method profileGetId;
    private final Method profileGetName;
    private final Method tokenGetToken;
    private final Constructor<?> gameProfileConstructor;
    private final Constructor<?> minecraftProtocolConstructor;

    private final Map<Object, HeldSession> heldBySession = new ConcurrentHashMap<>();

    public GeyserOnlineAuthBridge(Plugin geyserPlugin) throws ReflectiveOperationException {
        Objects.requireNonNull(geyserPlugin, "geyserPlugin");
        ClassLoader loader = geyserPlugin.getClass().getClassLoader();

        Class<?> geyserImplClass = Class.forName("org.geysermc.geyser.GeyserImpl", true, loader);
        Class<?> sessionClass = Class.forName("org.geysermc.geyser.session.GeyserSession", true, loader);
        Class<?> pendingClass = Class.forName("org.geysermc.geyser.session.PendingMicrosoftAuthentication", true, loader);
        Class<?> taskClass = Class.forName("org.geysermc.geyser.session.PendingMicrosoftAuthentication$AuthenticationTask", true, loader);
        Class<?> loginEncryptionUtilsClass = Class.forName("org.geysermc.geyser.util.LoginEncryptionUtils", true, loader);
        Class<?> javaAuthManagerClass = Class.forName("net.raphimc.minecraftauth.java.JavaAuthManager", true, loader);
        this.jsonObjectClass = Class.forName("com.google.gson.JsonObject", true, loader);
        Class<?> gameProfileClass = Class.forName("org.geysermc.mcprotocollib.auth.GameProfile", true, loader);
        Class<?> minecraftProtocolClass = Class.forName("org.geysermc.mcprotocollib.protocol.MinecraftProtocol", true, loader);

        this.geyser = geyserImplClass.getMethod("getInstance").invoke(null);
        this.sessionRemoteServerGetter = sessionClass.getMethod("remoteServer");
        this.sessionRemoteServerSetter = sessionClass.getMethod("remoteServer", RemoteServer.class);
        this.sessionExecuteInEventLoop = sessionClass.getMethod("executeInEventLoop", Runnable.class);
        this.sessionIsClosed = sessionClass.getMethod("isClosed");
        this.sessionDisconnect = sessionClass.getMethod("disconnect", String.class);
        this.sessionBedrockUsername = sessionClass.getMethod("bedrockUsername");

        this.sessionProtocol = sessionClass.getDeclaredField("protocol");
        this.sessionProtocol.setAccessible(true);
        this.sessionLoggingIn = sessionClass.getDeclaredField("loggingIn");
        this.sessionLoggingIn.setAccessible(true);
        this.sessionConnectDownstream = sessionClass.getDeclaredMethod("connectDownstream");
        this.sessionConnectDownstream.setAccessible(true);

        this.geyserGetPendingMicrosoftAuthentication = geyserImplClass.getMethod("getPendingMicrosoftAuthentication");
        this.geyserSavedAuthChains = geyserImplClass.getDeclaredField("savedAuthChains");
        this.geyserSavedAuthChains.setAccessible(true);

        this.pendingGetOrCreateTask = pendingClass.getMethod("getOrCreateTask", String.class);
        this.pendingGetTask = pendingClass.getMethod("getTask", String.class);
        this.taskResetRunningFlow = taskClass.getMethod("resetRunningFlow");
        this.taskPerformLoginAttempt = taskClass.getMethod("performLoginAttempt", boolean.class, Consumer.class);
        this.taskCleanup = taskClass.getMethod("cleanup");
        this.showMicrosoftCodeWindow = findStaticMethod(loginEncryptionUtilsClass, "buildAndShowMicrosoftCodeWindow", 2);

        Field gsonField = geyserImplClass.getField("GSON");
        this.gson = gsonField.get(null);
        this.gsonFromJson = gson.getClass().getMethod("fromJson", String.class, Class.class);
        this.gsonToJson = gson.getClass().getMethod("toJson", Object.class);

        Field authClientField = pendingClass.getField("AUTH_CLIENT");
        this.authClient = authClientField.get(null);

        this.authManagerFromJson = findStaticMethod(javaAuthManagerClass, "fromJson", 2);
        this.authManagerToJson = findStaticMethod(javaAuthManagerClass, "toJson", 1);
        this.authManagerGetProfile = javaAuthManagerClass.getMethod("getMinecraftProfile");
        this.authManagerGetToken = javaAuthManagerClass.getMethod("getMinecraftToken");

        Class<?> profileStepClass = authManagerGetProfile.getReturnType();
        Class<?> tokenStepClass = authManagerGetToken.getReturnType();
        this.profileStepGetUpToDate = profileStepClass.getMethod("getUpToDate");
        this.tokenStepGetUpToDate = tokenStepClass.getMethod("getUpToDate");

        Class<?> profileClass = profileStepGetUpToDate.getReturnType();
        Class<?> tokenClass = tokenStepGetUpToDate.getReturnType();
        this.profileGetId = profileClass.getMethod("getId");
        this.profileGetName = profileClass.getMethod("getName");
        this.tokenGetToken = tokenClass.getMethod("getToken");

        this.gameProfileConstructor = gameProfileClass.getConstructor(UUID.class, String.class);
        this.minecraftProtocolConstructor = minecraftProtocolClass.getConstructor(gameProfileClass, String.class);
    }

    public HeldSession hold(GeyserPendingSessionBridge.SessionHandle handle, String xuid) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(xuid, "xuid");
        Object session = handle.session();
        try {
            RemoteServer original = (RemoteServer) sessionRemoteServerGetter.invoke(session);
            if (original == null || original.authType() != AuthType.ONLINE) {
                throw new IllegalStateException("Geyser Java auth-type must be online for BedrockPassport JAVA_ACCOUNT mode");
            }

            String bedrockUsername = (String) sessionBedrockUsername.invoke(session);
            String previousSavedChain = removeGeyserSavedChain(bedrockUsername);
            cancelPendingAuthentication(xuid);

            HeldSession held = new HeldSession(handle, xuid, bedrockUsername, original, previousSavedChain);
            HeldSession previous = heldBySession.putIfAbsent(session, held);
            if (previous != null) {
                restoreGeyserSavedChain(bedrockUsername, previousSavedChain);
                throw new IllegalStateException("Geyser session is already held by BedrockPassport");
            }

            sessionRemoteServerSetter.invoke(session, new MaskedRemoteServer(original));
            return held;
        } catch (Throwable throwable) {
            throw bridgeFailure(throwable);
        }
    }

    public CompletableFuture<AuthenticatedJavaAccount> authenticateNew(HeldSession held) {
        Objects.requireNonNull(held, "held");
        CompletableFuture<AuthenticatedJavaAccount> result = new CompletableFuture<>();
        Object task = null;
        try {
            ensureActive(held);
            sessionLoggingIn.setBoolean(held.handle().session(), true);
            Object pending = geyserGetPendingMicrosoftAuthentication.invoke(geyser);
            task = pendingGetOrCreateTask.invoke(pending, held.xuid());
            taskResetRunningFlow.invoke(task);
            Object finalTask = task;
            Consumer<Object> codeConsumer = code -> {
                try {
                    showMicrosoftCodeWindow.invoke(null, held.handle().session(), code);
                } catch (Throwable throwable) {
                    result.completeExceptionally(bridgeFailure(throwable));
                }
            };
            Object futureObject = taskPerformLoginAttempt.invoke(task, true, codeConsumer);
            if (!(futureObject instanceof CompletableFuture<?> future)) {
                throw new IllegalStateException("Unexpected Geyser Microsoft authentication future type");
            }
            future.whenComplete((manager, error) -> {
                try {
                    if (error != null) {
                        result.completeExceptionally(unwrap(error));
                        return;
                    }
                    if (manager == null) {
                        result.completeExceptionally(new IllegalStateException("Geyser Microsoft authentication returned no account"));
                        return;
                    }
                    result.complete(materializeAccount(manager));
                } catch (Throwable throwable) {
                    result.completeExceptionally(bridgeFailure(throwable));
                } finally {
                    try {
                        taskCleanup.invoke(finalTask);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable throwable) {
            if (task != null) {
                try {
                    taskCleanup.invoke(task);
                } catch (Throwable ignored) {
                }
            }
            result.completeExceptionally(bridgeFailure(throwable));
        }
        return result;
    }

    public CompletableFuture<AuthenticatedJavaAccount> authenticateStored(HeldSession held, String authChain) {
        Objects.requireNonNull(held, "held");
        Objects.requireNonNull(authChain, "authChain");
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureActive(held);
                Object parsed = gsonFromJson.invoke(gson, authChain, jsonObjectClass);
                Object manager = authManagerFromJson.invoke(null, authClient, parsed);
                return materializeAccount(manager);
            } catch (Throwable throwable) {
                throw new CompletionException(bridgeFailure(throwable));
            }
        });
    }

    public CompletableFuture<Void> connect(HeldSession held, AuthenticatedJavaAccount account) {
        Objects.requireNonNull(held, "held");
        Objects.requireNonNull(account, "account");
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            ensureActive(held);
            sessionExecuteInEventLoop.invoke(held.handle().session(), (Runnable) () -> {
                try {
                    ensureActive(held);
                    restoreOriginalState(held);
                    sessionLoggingIn.setBoolean(held.handle().session(), true);
                    sessionProtocol.set(held.handle().session(), account.protocol());
                    sessionConnectDownstream.invoke(held.handle().session());
                    heldBySession.remove(held.handle().session(), held);
                    result.complete(null);
                } catch (Throwable throwable) {
                    result.completeExceptionally(bridgeFailure(throwable));
                }
            });
        } catch (Throwable throwable) {
            result.completeExceptionally(bridgeFailure(throwable));
        }
        return result;
    }

    public void resumeSelection(HeldSession held) {
        if (held == null) {
            return;
        }
        try {
            sessionExecuteInEventLoop.invoke(held.handle().session(), (Runnable) () -> {
                try {
                    if (heldBySession.get(held.handle().session()) == held
                            && !(boolean) sessionIsClosed.invoke(held.handle().session())) {
                        sessionLoggingIn.setBoolean(held.handle().session(), false);
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public void disconnectRaw(GeyserPendingSessionBridge.SessionHandle handle, String reason) {
        if (handle == null) {
            return;
        }
        try {
            sessionExecuteInEventLoop.invoke(handle.session(), (Runnable) () -> {
                try {
                    if (!(boolean) sessionIsClosed.invoke(handle.session())) {
                        sessionDisconnect.invoke(handle.session(), reason);
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public void disconnect(HeldSession held, String reason) {
        if (held == null) {
            return;
        }
        try {
            sessionExecuteInEventLoop.invoke(held.handle().session(), (Runnable) () -> {
                try {
                    restoreOriginalState(held);
                    heldBySession.remove(held.handle().session(), held);
                    if (!(boolean) sessionIsClosed.invoke(held.handle().session())) {
                        sessionDisconnect.invoke(held.handle().session(), reason);
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public void releaseOnDisconnect(String xuid) {
        for (HeldSession held : heldBySession.values()) {
            if (!held.xuid().equals(xuid)) {
                continue;
            }
            if (heldBySession.remove(held.handle().session(), held)) {
                restoreGeyserSavedChain(held.bedrockUsername(), held.previousGeyserAuthChain());
            }
        }
    }

    public int heldSessionCount() {
        return heldBySession.size();
    }

    private AuthenticatedJavaAccount materializeAccount(Object manager) throws Throwable {
        Object profileStep = authManagerGetProfile.invoke(manager);
        Object tokenStep = authManagerGetToken.invoke(manager);
        Object profile = profileStepGetUpToDate.invoke(profileStep);
        Object token = tokenStepGetUpToDate.invoke(tokenStep);

        UUID uuid = (UUID) profileGetId.invoke(profile);
        String username = (String) profileGetName.invoke(profile);
        String javaToken = (String) tokenGetToken.invoke(token);

        Object authJson = authManagerToJson.invoke(null, manager);
        String updatedAuthChain = (String) gsonToJson.invoke(gson, authJson);

        Object gameProfile = gameProfileConstructor.newInstance(uuid, username);
        Object protocol = minecraftProtocolConstructor.newInstance(gameProfile, javaToken);
        return new AuthenticatedJavaAccount(uuid, username, updatedAuthChain, protocol);
    }

    private void ensureActive(HeldSession held) throws ReflectiveOperationException {
        if (heldBySession.get(held.handle().session()) != held) {
            throw new IllegalStateException("BedrockPassport online session is no longer active");
        }
        if ((boolean) sessionIsClosed.invoke(held.handle().session())) {
            throw new IllegalStateException("Geyser session closed during Java account authentication");
        }
    }

    @SuppressWarnings("unchecked")
    private String removeGeyserSavedChain(String bedrockUsername) throws IllegalAccessException {
        Object value = geyserSavedAuthChains.get(geyser);
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        return ((Map<String, String>) raw).remove(bedrockUsername);
    }

    @SuppressWarnings("unchecked")
    private void restoreGeyserSavedChain(String bedrockUsername, String previous) {
        if (previous == null) {
            return;
        }
        try {
            Object value = geyserSavedAuthChains.get(geyser);
            if (value instanceof Map<?, ?> raw) {
                ((Map<String, String>) raw).put(bedrockUsername, previous);
            }
        } catch (Throwable ignored) {
        }
    }

    private void cancelPendingAuthentication(String xuid) {
        try {
            Object pending = geyserGetPendingMicrosoftAuthentication.invoke(geyser);
            Object task = pendingGetTask.invoke(pending, xuid);
            if (task == null) {
                return;
            }
            taskResetRunningFlow.invoke(task);
            taskCleanup.invoke(task);
        } catch (Throwable ignored) {
        }
    }

    private void restoreOriginalState(HeldSession held) throws ReflectiveOperationException {
        sessionRemoteServerSetter.invoke(held.handle().session(), held.originalRemoteServer());
        restoreGeyserSavedChain(held.bedrockUsername(), held.previousGeyserAuthChain());
    }

    private static Method findStaticMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount
                    && Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + " with " + parameterCount + " parameters");
    }

    private static RuntimeException bridgeFailure(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            cause = invocation.getCause();
        }
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException("Geyser online-auth bridge failed", cause);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        for (HeldSession held : heldBySession.values()) {
            restoreGeyserSavedChain(held.bedrockUsername(), held.previousGeyserAuthChain());
        }
        heldBySession.clear();
    }

    public record HeldSession(
            GeyserPendingSessionBridge.SessionHandle handle,
            String xuid,
            String bedrockUsername,
            RemoteServer originalRemoteServer,
            String previousGeyserAuthChain
    ) {
    }

    public record AuthenticatedJavaAccount(
            UUID javaUuid,
            String javaUsername,
            String authChain,
            Object protocol
    ) {
    }

    private record MaskedRemoteServer(RemoteServer delegate) implements RemoteServer {
        @Override
        public String address() {
            return delegate.address();
        }

        @Override
        public int port() {
            return delegate.port();
        }

        @Override
        public int protocolVersion() {
            return delegate.protocolVersion();
        }

        @Override
        public String minecraftVersion() {
            return delegate.minecraftVersion();
        }

        @Override
        public AuthType authType() {
            return AuthType.OFFLINE;
        }

        @Override
        public boolean resolveSrv() {
            return delegate.resolveSrv();
        }
    }
}
