package dev.onelsey.bedrockpassport.integration;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class GeyserPendingSessionBridge {
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
    private final Method upstreamIsInitialized;
    private final Method sessionSendForm;
    private final Method customFormBuilder;
    private final Method builderTitle;
    private final Method builderLabel;
    private final Method builderInput;
    private final Method builderClosedOrInvalid;
    private final Method builderValid;
    private final Method builderBuild;
    private final Method responseAsInput;

    public GeyserPendingSessionBridge(Plugin geyserPlugin) throws ReflectiveOperationException {
        Objects.requireNonNull(geyserPlugin, "geyserPlugin");
        ClassLoader loader = geyserPlugin.getClass().getClassLoader();

        Class<?> geyserImplClass = Class.forName("org.geysermc.geyser.GeyserImpl", true, loader);
        Class<?> sessionManagerClass = Class.forName("org.geysermc.geyser.session.SessionManager", true, loader);
        Class<?> sessionClass = Class.forName("org.geysermc.geyser.session.GeyserSession", true, loader);
        Class<?> upstreamClass = Class.forName("org.geysermc.geyser.session.UpstreamSession", true, loader);
        Class<?> formClass = Class.forName("org.geysermc.cumulus.form.Form", true, loader);
        Class<?> customFormClass = Class.forName("org.geysermc.cumulus.form.CustomForm", true, loader);
        Class<?> customFormBuilderClass = Class.forName("org.geysermc.cumulus.form.CustomForm$Builder", true, loader);
        Class<?> customFormResponseClass = Class.forName("org.geysermc.cumulus.response.CustomFormResponse", true, loader);

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
        this.upstreamIsInitialized = upstreamClass.getMethod("isInitialized");
        this.sessionSendForm = sessionClass.getMethod("sendForm", formClass);
        this.customFormBuilder = customFormClass.getMethod("builder");
        this.builderTitle = customFormBuilderClass.getMethod("title", String.class);
        this.builderLabel = customFormBuilderClass.getMethod("label", String.class);
        this.builderInput = customFormBuilderClass.getMethod("input", String.class, String.class, String.class);
        this.builderClosedOrInvalid = customFormBuilderClass.getMethod("closedOrInvalidResultHandler", Runnable.class);
        this.builderValid = customFormBuilderClass.getMethod("validResultHandler", Consumer.class);
        this.builderBuild = customFormBuilderClass.getMethod("build");
        this.responseAsInput = customFormResponseClass.getMethod("asInput");
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

    public CompletableFuture<SessionHandle> enterHoldingWorld(SessionHandle handle, long initTimeoutSeconds) {
        CompletableFuture<SessionHandle> future = new CompletableFuture<>();
        try {
            execute(handle, () -> {
                try {
                    if (isClosed(handle)) {
                        future.completeExceptionally(new IllegalStateException("Geyser session closed before nickname selection"));
                        return;
                    }
                    if (!(boolean) sessionIsSentSpawnPacket.invoke(handle.session())) {
                        sessionConnect.invoke(handle.session());
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
                future.completeExceptionally(new IllegalStateException("Geyser session closed while initializing holding world"));
                return;
            }
            Object upstream = sessionGetUpstream.invoke(handle.session());
            if ((boolean) upstreamIsInitialized.invoke(upstream)) {
                future.complete(handle);
                return;
            }
            if (System.nanoTime() >= deadline) {
                future.completeExceptionally(new IllegalStateException("Geyser holding world initialization timed out"));
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
        try {
            execute(handle, () -> {
                try {
                    if (isClosed(handle)) {
                        onFailure.accept(new IllegalStateException("Geyser session closed before form could be shown"));
                        return;
                    }
                    Object builder = customFormBuilder.invoke(null);
                    builderTitle.invoke(builder, title);
                    if (error != null && !error.isBlank()) {
                        builderLabel.invoke(builder, "§c" + error);
                    }
                    builderLabel.invoke(builder, text);
                    builderInput.invoke(builder, inputLabel, placeholder, initialValue == null ? "" : initialValue);
                    builderClosedOrInvalid.invoke(builder, onClosed);
                    Consumer<Object> responseConsumer = response -> {
                        try {
                            onSubmit.accept((String) responseAsInput.invoke(response));
                        } catch (Throwable throwable) {
                            onFailure.accept(bridgeFailure(throwable));
                        }
                    };
                    builderValid.invoke(builder, responseConsumer);
                    Object form = builderBuild.invoke(builder);
                    Object sent = sessionSendForm.invoke(handle.session(), form);
                    if (sent instanceof Boolean success && !success) {
                        onFailure.accept(new IllegalStateException("Geyser rejected the nickname form"));
                    }
                } catch (Throwable throwable) {
                    onFailure.accept(bridgeFailure(throwable));
                }
            });
        } catch (Throwable throwable) {
            onFailure.accept(bridgeFailure(throwable));
        }
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

    private static RuntimeException bridgeFailure(Throwable throwable) {
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
