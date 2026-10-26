package dev.onelsey.bedrockpassport.integration;

import dev.onelsey.bedrockpassport.access.BedrockAccessPolicy;
import dev.onelsey.bedrockpassport.data.Identity;
import dev.onelsey.bedrockpassport.i18n.LocalizedMessages;
import dev.onelsey.bedrockpassport.gate.IdentityGate;
import org.geysermc.floodgate.api.InstanceHolder;
import org.geysermc.floodgate.api.handshake.HandshakeData;
import org.geysermc.floodgate.api.handshake.HandshakeHandler;
import org.geysermc.floodgate.api.handshake.HandshakeHandlers;
import org.geysermc.floodgate.util.LinkedPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("deprecation")
public final class FloodgateIdentityBridge implements HandshakeHandler, AutoCloseable {
    private final IdentityGate gate;
    private final BedrockAccessPolicy accessPolicy;
    private final LocalizedMessages localizedMessages;
    private final Logger logger;
    private final HandshakeHandlers handlers;
    private final FloodgateSkinPolicy skinPolicy;
    private final UntrustedFloodgateIdentityBridge untrustedIdentityBridge;
    private int registrationId = -1;

    public FloodgateIdentityBridge(
            IdentityGate gate,
            BedrockAccessPolicy accessPolicy,
            LocalizedMessages localizedMessages,
            Logger logger,
            FloodgateSkinPolicy skinPolicy,
            UntrustedFloodgateIdentityBridge untrustedIdentityBridge
    ) {
        this.gate = gate;
        this.accessPolicy = accessPolicy;
        this.localizedMessages = localizedMessages;
        this.logger = logger;
        this.skinPolicy = skinPolicy;
        this.untrustedIdentityBridge = untrustedIdentityBridge;
        this.handlers = InstanceHolder.getHandshakeHandlers();
        if (handlers == null) {
            throw new IllegalStateException("Floodgate handshake API is unavailable");
        }
    }

    public void register() {
        registrationId = handlers.addHandshakeHandler(this);
        if (registrationId < 0) {
            throw new IllegalStateException("Floodgate rejected BedrockPassport handshake handler registration");
        }
    }

    @Override
    public void handle(HandshakeData data) {
        if (!data.isFloodgatePlayer() || data.getBedrockData() == null) {
            return;
        }

        String xuid = data.getBedrockData().getXuid();
        String bedrockUsername = data.getBedrockData().getUsername();
        String playerLocale = data.getBedrockData().getLanguageCode();
        String locale = localizedMessages.resolvePlayerLocale(playerLocale);
        UUID floodgateUuid = data.getJavaUniqueId();
        if (xuid == null || xuid.isBlank() || floodgateUuid == null) {
            data.setDisconnectReason(disconnectReason(localizedMessages.text(locale, "form.internal-error")));
            return;
        }

        if (!accessPolicy.allows(bedrockUsername, xuid)) {
            String displayName = bedrockUsername == null || bedrockUsername.isBlank() ? "<unknown>" : bedrockUsername;
            logger.info(localizedMessages.text("console.access-denied", Map.of("player", displayName)));
            data.setDisconnectReason(disconnectReason(localizedMessages.text(locale, "access.denied")));
            return;
        }

        // This runs on a Netty thread shared with other connections. The selector already ran in
        // Geyser's holding world before the backend connection was opened, so only take its result.
        Identity identity = gate.takePrepared(xuid);
        if (identity == null) {
            logger.warning("BedrockPassport received a Floodgate login for XUID " + xuid
                    + " without a completed Passport selection; refusing it.");
            data.setDisconnectReason(disconnectReason(localizedMessages.text(locale, "form.internal-error")));
            return;
        }
        try {
            if (identity.javaUuid() == null) {
                throw new IllegalStateException("Resolved BedrockPassport identity has no Java UUID");
            }
            gate.armInitialMovementGuard(xuid);
            data.setLinkedPlayer(LinkedPlayer.of(identity.gameName(), identity.javaUuid(), floodgateUuid));
            untrustedIdentityBridge.prepare(data.getChannel(), xuid, floodgateUuid, identity.javaUuid(), identity.gameName(), locale);
            skinPolicy.track(xuid, identity.javaUuid(), identity.gameName());
        } catch (Exception exception) {
            releaseIfSelected(xuid, identity);
            logger.log(Level.SEVERE, "BedrockPassport failed to apply identity for XUID " + xuid, exception);
            data.setDisconnectReason(disconnectReason(localizedMessages.text(locale, "form.internal-error")));
        }
    }

    private static String disconnectReason(String message) {
        return "§bBedrockPassport §8» §f" + message;
    }

    private void releaseIfSelected(String xuid, Identity identity) {
        if (identity != null && identity.javaUuid() != null) {
            gate.releaseReservation(xuid, identity.javaUuid());
        }
    }

    @Override
    public void close() {
        if (registrationId >= 0) {
            handlers.removeHandshakeHandler(registrationId);
            registrationId = -1;
        }
    }
}
