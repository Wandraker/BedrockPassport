package dev.onelsey.bedrockpassport.integration;

import dev.onelsey.bedrockpassport.gate.IdentityGate;
import dev.onelsey.bedrockpassport.security.SessionGuard;
import org.bukkit.plugin.Plugin;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.EventRegistrar;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;

import java.util.Objects;

public final class GeyserSessionLifecycleBridge implements AutoCloseable {
    private final GeyserApi api;
    private final EventRegistrar registrar;
    private final IdentityGate gate;
    private final SessionGuard sessionGuard;
    private final FloodgateSkinPolicy skinPolicy;
    private final UntrustedFloodgateIdentityBridge untrustedIdentityBridge;
    private boolean registered;

    public GeyserSessionLifecycleBridge(
            Plugin plugin,
            IdentityGate gate,
            SessionGuard sessionGuard,
            FloodgateSkinPolicy skinPolicy,
            UntrustedFloodgateIdentityBridge untrustedIdentityBridge
    ) {
        this.api = Objects.requireNonNull(GeyserApi.api(), "Geyser API is unavailable");
        this.registrar = EventRegistrar.of(Objects.requireNonNull(plugin, "plugin"));
        this.gate = Objects.requireNonNull(gate, "gate");
        this.sessionGuard = Objects.requireNonNull(sessionGuard, "sessionGuard");
        this.skinPolicy = Objects.requireNonNull(skinPolicy, "skinPolicy");
        this.untrustedIdentityBridge = Objects.requireNonNull(untrustedIdentityBridge, "untrustedIdentityBridge");
    }

    public void register() {
        if (registered) {
            throw new IllegalStateException("Geyser session lifecycle bridge is already registered");
        }
        api.eventBus().subscribe(registrar, SessionDisconnectEvent.class, this::onDisconnect);
        registered = true;
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

    @Override
    public void close() {
        if (!registered) {
            return;
        }
        registered = false;
        api.eventBus().unregisterAll(registrar);
    }
}
