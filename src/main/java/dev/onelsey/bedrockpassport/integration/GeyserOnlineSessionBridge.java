package dev.onelsey.bedrockpassport.integration;

import dev.onelsey.bedrockpassport.access.BedrockAccessPolicy;
import dev.onelsey.bedrockpassport.gate.OnlineIdentityGate;
import dev.onelsey.bedrockpassport.i18n.LocalizedMessages;
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
    private final BedrockAccessPolicy accessPolicy;
    private final LocalizedMessages messages;
    private final Logger logger;
    private final long holdingInitTimeoutSeconds;
    private boolean registered;

    public GeyserOnlineSessionBridge(
            Plugin plugin,
            GeyserPendingSessionBridge pendingBridge,
            GeyserOnlineAuthBridge authBridge,
            OnlineIdentityGate gate,
            BedrockAccessPolicy accessPolicy,
            LocalizedMessages messages,
            Logger logger,
            long holdingInitTimeoutSeconds
    ) {
        this.api = Objects.requireNonNull(GeyserApi.api(), "Geyser API is unavailable");
        this.registrar = EventRegistrar.of(Objects.requireNonNull(plugin, "plugin"));
        this.pendingBridge = Objects.requireNonNull(pendingBridge, "pendingBridge");
        this.authBridge = Objects.requireNonNull(authBridge, "authBridge");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.messages = Objects.requireNonNull(messages, "messages");
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

        String locale;
        try {
            locale = messages.resolvePlayerLocale(event.connection().locale());
        } catch (Throwable ignored) {
            locale = messages.resolvePlayerLocale(null);
        }

        GeyserPendingSessionBridge.SessionHandle handle = new GeyserPendingSessionBridge.SessionHandle(event.connection());
        String bedrockUsername;
        try {
            bedrockUsername = event.connection().name();
        } catch (Throwable ignored) {
            bedrockUsername = null;
        }
        if (!accessPolicy.allows(bedrockUsername, xuid)) {
            String displayName = bedrockUsername == null || bedrockUsername.isBlank() ? "<unknown>" : bedrockUsername;
            logger.info(messages.text("console.access-denied", java.util.Map.of("player", displayName)));
            authBridge.disconnectRaw(handle, messages.prefixed(locale, "access.denied"));
            return;
        }

        GeyserOnlineAuthBridge.HeldSession held;
        try {
            held = authBridge.hold(handle, xuid);
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "BedrockPassport could not hold the Geyser online-auth session for XUID " + xuid, throwable);
            authBridge.disconnectRaw(handle, messages.prefixed(locale, "online.prepare-failed"));
            return;
        }

        pendingBridge.awaitInitialized(handle, holdingInitTimeoutSeconds).whenComplete((ready, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                logger.log(Level.SEVERE, "BedrockPassport online holding environment failed for XUID " + xuid, cause);
                authBridge.disconnect(held, messages.prefixed(locale, "online.selector-init-failed"));
                return;
            }
            gate.open(held, locale);
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
