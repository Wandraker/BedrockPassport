package dev.onelsey.bedrockpassport.integration;

import dev.onelsey.bedrockpassport.access.BedrockAccessPolicy;
import dev.onelsey.bedrockpassport.data.Identity;
import dev.onelsey.bedrockpassport.i18n.LocalizedMessages;
import dev.onelsey.bedrockpassport.gate.GateClosedException;
import dev.onelsey.bedrockpassport.gate.GateTimeoutException;
import dev.onelsey.bedrockpassport.gate.IdentityGate;
import dev.onelsey.bedrockpassport.gate.PassportSessionBusyException;
import org.geysermc.floodgate.api.InstanceHolder;
import org.geysermc.floodgate.api.handshake.HandshakeData;
import org.geysermc.floodgate.api.handshake.HandshakeHandler;
import org.geysermc.floodgate.api.handshake.HandshakeHandlers;
import org.geysermc.floodgate.util.LinkedPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("deprecation")
public final class FloodgateIdentityBridge implements HandshakeHandler, AutoCloseable {
    private final IdentityGate gate;
    private final BedrockAccessPolicy accessPolicy;
    private final LocalizedMessages localizedMessages;
    private final Logger logger;
    private final HandshakeHandlers handlers;
    private final ServerLoginReadTimeoutGuard serverTimeoutGuard;
    private final FloodgateSkinPolicy skinPolicy;
    private final UntrustedFloodgateIdentityBridge untrustedIdentityBridge;
    private int registrationId = -1;

    public FloodgateIdentityBridge(
            IdentityGate gate,
            BedrockAccessPolicy accessPolicy,
            LocalizedMessages localizedMessages,
            Logger logger,
            ServerLoginReadTimeoutGuard serverTimeoutGuard,
            FloodgateSkinPolicy skinPolicy,
            UntrustedFloodgateIdentityBridge untrustedIdentityBridge
    ) {
        this.gate = gate;
        this.accessPolicy = accessPolicy;
        this.localizedMessages = localizedMessages;
        this.logger = logger;
        this.serverTimeoutGuard = serverTimeoutGuard;
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

        ServerLoginReadTimeoutGuard.Lease serverTimeoutLease = serverTimeoutGuard.suspend(data.getChannel());
        Identity identity = null;
        try {
            identity = gate.resolve(xuid, floodgateUuid, locale).join();
            if (identity.javaUuid() == null) {
                throw new IllegalStateException("Resolved BedrockPassport identity has no Java UUID");
            }
            gate.armInitialMovementGuard(xuid);
            data.setLinkedPlayer(LinkedPlayer.of(identity.gameName(), identity.javaUuid(), floodgateUuid));
            untrustedIdentityBridge.prepare(data.getChannel(), xuid, floodgateUuid, identity.javaUuid(), identity.gameName(), locale);
            skinPolicy.track(xuid, identity.javaUuid(), identity.gameName());
            GeyserPendingSessionBridge.SkinUploadRequest skinUploadRequest = gate.requestBedrockSkinUpload(xuid);
            if (skinUploadRequest != GeyserPendingSessionBridge.SkinUploadRequest.REQUESTED) {
                logger.fine("BedrockPassport early skin upload request for " + identity.gameName() + " returned " + skinUploadRequest + ".");
            }
        } catch (PassportSessionBusyException busy) {
            releaseIfSelected(xuid, identity);
            data.setDisconnectReason(disconnectReason(busy.getMessage()));
        } catch (GateClosedException closed) {
            releaseIfSelected(xuid, identity);
            data.setDisconnectReason(disconnectReason(localizedMessages.text(locale, "security.bedrock-connection-closed")));
        } catch (CompletionException exception) {
            releaseIfSelected(xuid, identity);
            Throwable cause = unwrap(exception);
            if (cause instanceof GateTimeoutException) {
                data.setDisconnectReason(disconnectReason(localizedMessages.text(locale, "form.timeout")));
            } else if (cause instanceof GateClosedException) {
                data.setDisconnectReason(disconnectReason(localizedMessages.text(locale, "security.bedrock-connection-closed")));
            } else if (cause instanceof PassportSessionBusyException busy) {
                data.setDisconnectReason(disconnectReason(busy.getMessage()));
            } else {
                logger.log(Level.SEVERE, "BedrockPassport failed to resolve identity for XUID " + xuid, cause);
                data.setDisconnectReason(disconnectReason(localizedMessages.text(locale, "form.internal-error")));
            }
        } catch (Exception exception) {
            releaseIfSelected(xuid, identity);
            logger.log(Level.SEVERE, "BedrockPassport failed to resolve identity for XUID " + xuid, exception);
            data.setDisconnectReason(disconnectReason(localizedMessages.text(locale, "form.internal-error")));
        } finally {
            serverTimeoutLease.close();
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

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        if (registrationId >= 0) {
            handlers.removeHandshakeHandler(registrationId);
            registrationId = -1;
        }
    }
}
