package dev.onelsey.bedrockpassport.integration;

import dev.onelsey.bedrockpassport.data.Identity;
import dev.onelsey.bedrockpassport.gate.GateClosedException;
import dev.onelsey.bedrockpassport.gate.GateMessages;
import dev.onelsey.bedrockpassport.gate.GateTimeoutException;
import dev.onelsey.bedrockpassport.gate.IdentityGate;
import dev.onelsey.bedrockpassport.gate.PassportSessionBusyException;
import org.geysermc.floodgate.api.InstanceHolder;
import org.geysermc.floodgate.api.handshake.HandshakeData;
import org.geysermc.floodgate.api.handshake.HandshakeHandler;
import org.geysermc.floodgate.api.handshake.HandshakeHandlers;
import org.geysermc.floodgate.util.LinkedPlayer;

import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("deprecation")
public final class FloodgateIdentityBridge implements HandshakeHandler, AutoCloseable {
    private final IdentityGate gate;
    private final GateMessages messages;
    private final Logger logger;
    private final HandshakeHandlers handlers;
    private final ServerLoginReadTimeoutGuard serverTimeoutGuard;
    private int registrationId = -1;

    public FloodgateIdentityBridge(
            IdentityGate gate,
            GateMessages messages,
            Logger logger,
            ServerLoginReadTimeoutGuard serverTimeoutGuard
    ) {
        this.gate = gate;
        this.messages = messages;
        this.logger = logger;
        this.serverTimeoutGuard = serverTimeoutGuard;
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
        UUID floodgateUuid = data.getJavaUniqueId();
        if (xuid == null || xuid.isBlank() || floodgateUuid == null) {
            data.setDisconnectReason(messages.internalError());
            return;
        }

        ServerLoginReadTimeoutGuard.Lease serverTimeoutLease = serverTimeoutGuard.suspend(data.getChannel());
        Identity identity = null;
        try {
            identity = gate.resolve(xuid, floodgateUuid).join();
            if (identity.javaUuid() == null) {
                throw new IllegalStateException("Resolved BedrockPassport identity has no Java UUID");
            }
            data.setLinkedPlayer(LinkedPlayer.of(identity.gameName(), identity.javaUuid(), floodgateUuid));
        } catch (PassportSessionBusyException busy) {
            releaseIfSelected(xuid, identity);
            data.setDisconnectReason(busy.getMessage());
        } catch (GateClosedException closed) {
            releaseIfSelected(xuid, identity);
            data.setDisconnectReason("Bedrock connection closed.");
        } catch (CompletionException exception) {
            releaseIfSelected(xuid, identity);
            Throwable cause = unwrap(exception);
            if (cause instanceof GateTimeoutException) {
                data.setDisconnectReason(messages.timeout());
            } else if (cause instanceof GateClosedException) {
                data.setDisconnectReason("Bedrock connection closed.");
            } else if (cause instanceof PassportSessionBusyException busy) {
                data.setDisconnectReason(busy.getMessage());
            } else {
                logger.log(Level.SEVERE, "BedrockPassport failed to resolve identity for XUID " + xuid, cause);
                data.setDisconnectReason(messages.internalError());
            }
        } catch (Exception exception) {
            releaseIfSelected(xuid, identity);
            logger.log(Level.SEVERE, "BedrockPassport failed to resolve identity for XUID " + xuid, exception);
            data.setDisconnectReason(messages.internalError());
        } finally {
            serverTimeoutLease.close();
        }
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
