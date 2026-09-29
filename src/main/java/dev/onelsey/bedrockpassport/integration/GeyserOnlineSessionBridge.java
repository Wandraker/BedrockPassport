package dev.onelsey.bedrockpassport.integration;

import dev.onelsey.bedrockpassport.gate.OnlineIdentityGate;
import org.bukkit.plugin.Plugin;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostReloadEvent;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GeyserOnlineSessionBridge implements AutoCloseable {
    private final GeyserApi api;
    private final EventRegistrar registrar;
    private final GeyserPendingSessionBridge pendingBridge;
    private final GeyserOnlineAuthBridge authBridge;
    private final OnlineIdentityGate gate;
    private final Logger logger;
    private final long holdingInitTimeoutSeconds;
    private boolean registered;

    public GeyserOnlineSessionBridge(
            Plugin plugin,
            GeyserPendingSessionBridge pendingBridge,
            GeyserOnlineAuthBridge authBridge,
            OnlineIdentityGate gate,
            Logger logger,
            long holdingInitTimeoutSeconds
    ) {
        this.api = Objects.requireNonNull(GeyserApi.api(), "Geyser API is unavailable");
        this.registrar = EventRegistrar.of(Objects.requireNonNull(plugin, "plugin"));
        this.pendingBridge = Objects.requireNonNull(pendingBridge, "pendingBridge");
        this.authBridge = Objects.requireNonNull(authBridge, "authBridge");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.holdingInitTimeoutSeconds = Math.max(60L, holdingInitTimeoutSeconds);
    }

    public void register() {
        if (registered) {
            throw new IllegalStateException("Geyser online session bridge is already registered");
        }
        api.eventBus().subscribe(registrar, GeyserPostInitializeEvent.class, event -> onGeyserReady("post-initialize"));
        api.eventBus().subscribe(registrar, GeyserPostReloadEvent.class, event -> onGeyserReady("post-reload"));
        api.eventBus().subscribe(registrar, SessionInitializeEvent.class, this::onInitialize);
        api.eventBus().subscribe(registrar, SessionDisconnectEvent.class, this::onDisconnect);
        registered = true;

        authBridge.ensureConfiguredOnline("bridge-register");
    }

    private void onGeyserReady(String phase) {
        try {
            authBridge.ensureConfiguredOnline(phase);
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "BedrockPassport could not enforce Geyser online authentication after " + phase + ".", throwable);
        }
    }

    private void onInitialize(SessionInitializeEvent event) {
        String xuid;
        try {
            xuid = event.connection().xuid();
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "BedrockPassport could not read the Bedrock XUID for online-mode Passport.", throwable);
            return;
        }
        if (xuid == null || xuid.isBlank()) {
            return;
        }

        GeyserPendingSessionBridge.SessionHandle handle = new GeyserPendingSessionBridge.SessionHandle(event.connection());
        GeyserOnlineAuthBridge.HeldSession held;
        try {
            held = authBridge.hold(handle, xuid);
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "BedrockPassport could not hold the Geyser online-auth session for XUID " + xuid, throwable);
            authBridge.disconnectRaw(handle, "§bBedrockPassport §8» §fCould not prepare Geyser online authentication for this Passport session.");
            return;
        }

        pendingBridge.awaitInitialized(handle, holdingInitTimeoutSeconds).whenComplete((ready, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                logger.log(Level.SEVERE, "BedrockPassport online holding environment failed for XUID " + xuid, cause);
                authBridge.disconnect(held, "§bBedrockPassport §8» §fCould not initialize the Java-account selector.");
                return;
            }
            gate.open(held);
        });
    }

    private void onDisconnect(SessionDisconnectEvent event) {
        String xuid;
        try {
            xuid = event.connection().xuid();
        } catch (Throwable ignored) {
            return;
        }
        if (xuid == null || xuid.isBlank()) {
            return;
        }
        gate.handleDisconnect(xuid);
        authBridge.releaseOnDisconnect(xuid);
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
        if (registered) {
            registered = false;
            api.eventBus().unregisterAll(registrar);
        }
    }
}
