package dev.onelsey.bedrockpassport.integration;

import dev.onelsey.bedrockpassport.access.BedrockAccessPolicy;
import dev.onelsey.bedrockpassport.gate.GateClosedException;
import dev.onelsey.bedrockpassport.gate.GateTimeoutException;
import dev.onelsey.bedrockpassport.gate.IdentityGate;
import dev.onelsey.bedrockpassport.gate.PassportSessionBusyException;
import dev.onelsey.bedrockpassport.i18n.LocalizedMessages;
import dev.onelsey.bedrockpassport.security.SessionGuard;
import org.bukkit.plugin.Plugin;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionInitializeEvent;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class GeyserSessionLifecycleBridge implements AutoCloseable {
    private final GeyserApi api;
    private final EventRegistrar registrar;
    private final GeyserPendingSessionBridge pendingBridge;
    private final IdentityGate gate;
    private final BedrockAccessPolicy accessPolicy;
    private final SessionGuard sessionGuard;
    private final FloodgateSkinPolicy skinPolicy;
    private final UntrustedFloodgateIdentityBridge untrustedIdentityBridge;
    private final LocalizedMessages messages;
    private final Logger logger;
    private boolean registered;

    public GeyserSessionLifecycleBridge(
            Plugin plugin,
            GeyserPendingSessionBridge pendingBridge,
            IdentityGate gate,
            BedrockAccessPolicy accessPolicy,
            SessionGuard sessionGuard,
            FloodgateSkinPolicy skinPolicy,
            UntrustedFloodgateIdentityBridge untrustedIdentityBridge,
            LocalizedMessages messages,
            Logger logger
    ) {
        this.api = Objects.requireNonNull(GeyserApi.api(), "Geyser API is unavailable");
        this.registrar = EventRegistrar.of(Objects.requireNonNull(plugin, "plugin"));
        this.pendingBridge = Objects.requireNonNull(pendingBridge, "pendingBridge");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.sessionGuard = Objects.requireNonNull(sessionGuard, "sessionGuard");
        this.skinPolicy = Objects.requireNonNull(skinPolicy, "skinPolicy");
        this.untrustedIdentityBridge = Objects.requireNonNull(untrustedIdentityBridge, "untrustedIdentityBridge");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void register() {
        if (registered) {
            throw new IllegalStateException("Geyser session lifecycle bridge is already registered");
        }
        api.eventBus().subscribe(registrar, SessionInitializeEvent.class, this::onInitialize);
        api.eventBus().subscribe(registrar, SessionDisconnectEvent.class, this::onDisconnect);
        registered = true;
    }

    private void onInitialize(SessionInitializeEvent event) {
        String xuid;
        try {
            xuid = event.connection().xuid();
        } catch (Throwable throwable) {
            logger.log(Level.SEVERE, "BedrockPassport could not read the Bedrock XUID for the LOCAL Passport.", throwable);
            return;
        }
        if (xuid == null || xuid.isBlank()) {
            return;
        }

        String locale = resolvePlayerLocale(event);
        GeyserPendingSessionBridge.SessionHandle handle = new GeyserPendingSessionBridge.SessionHandle(event.connection());
        String bedrockUsername;
        try {
            bedrockUsername = event.connection().name();
        } catch (Throwable ignored) {
            bedrockUsername = null;
        }
        if (!accessPolicy.allows(bedrockUsername, xuid)) {
            String displayName = bedrockUsername == null || bedrockUsername.isBlank() ? "<unknown>" : bedrockUsername;
            logger.info(messages.text("console.access-denied", Map.of("player", displayName)));
            pendingBridge.disconnect(handle, messages.prefixed(locale, "access.denied"));
            return;
        }

        try {
            pendingBridge.holdBackendLogin(
                    handle,
                    () -> openSelector(handle, xuid, locale),
                    error -> fail(handle, xuid, locale, error)
            );
        } catch (Throwable throwable) {
            fail(handle, xuid, locale, throwable);
        }
    }

    private void openSelector(GeyserPendingSessionBridge.SessionHandle handle, String xuid, String locale) {
        try {
            if (pendingBridge.isClosed(handle)) {
                return;
            }
        } catch (Throwable throwable) {
            fail(handle, xuid, locale, throwable);
            return;
        }
        gate.open(handle, xuid, locale).whenComplete((identity, error) -> {
            if (error != null) {
                fail(handle, xuid, locale, error);
                return;
            }
            pendingBridge.authenticate(handle, failure -> fail(handle, xuid, locale, failure));
        });
    }

    private void fail(GeyserPendingSessionBridge.SessionHandle handle, String xuid, String locale, Throwable error) {
        Throwable cause = unwrap(error);
        String reason;
        if (cause instanceof GateTimeoutException) {
            reason = messages.prefixed(locale, "form.timeout");
        } else if (cause instanceof GateClosedException) {
            reason = messages.prefixed(locale, "security.bedrock-connection-closed");
        } else if (cause instanceof PassportSessionBusyException busy) {
            reason = "§bBedrockPassport §8» §f" + busy.getMessage();
        } else {
            logger.log(Level.SEVERE, "BedrockPassport failed to resolve identity for XUID " + xuid, cause);
            reason = messages.prefixed(locale, "form.internal-error");
        }
        pendingBridge.disconnect(handle, reason);
    }

    private String resolvePlayerLocale(SessionInitializeEvent event) {
        try {
            return messages.resolvePlayerLocale(event.connection().locale());
        } catch (Throwable ignored) {
            return messages.resolvePlayerLocale(null);
        }
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

        gate.handleBedrockDisconnect(xuid);
        sessionGuard.releasePendingBedrockByXuid(xuid);
        untrustedIdentityBridge.releasePending(xuid);
        skinPolicy.release(xuid);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        if (!registered) {
            return;
        }
        registered = false;
        api.eventBus().unregisterAll(registrar);
    }
}
