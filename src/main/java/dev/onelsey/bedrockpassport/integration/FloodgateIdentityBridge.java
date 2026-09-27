package dev.onelsey.bedrockpassport.integration;

import dev.onelsey.bedrockpassport.data.Identity;
import dev.onelsey.bedrockpassport.data.IdentityRepository;
import dev.onelsey.bedrockpassport.gate.GateMessages;
import dev.onelsey.bedrockpassport.gate.IdentityGate;
import org.geysermc.floodgate.api.InstanceHolder;
import org.geysermc.floodgate.api.handshake.HandshakeData;
import org.geysermc.floodgate.api.handshake.HandshakeHandler;
import org.geysermc.floodgate.api.handshake.HandshakeHandlers;
import org.geysermc.floodgate.util.LinkedPlayer;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("deprecation")
public final class FloodgateIdentityBridge implements HandshakeHandler, AutoCloseable {
    private final IdentityRepository repository;
    private final IdentityGate gate;
    private final GateMessages messages;
    private final Logger logger;
    private final HandshakeHandlers handlers;
    private int registrationId = -1;

    public FloodgateIdentityBridge(IdentityRepository repository, IdentityGate gate, GateMessages messages, Logger logger) {
        this.repository = repository;
        this.gate = gate;
        this.messages = messages;
        this.logger = logger;
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

        try {
            Optional<Identity> existing = repository.findByXuid(xuid).get(5, TimeUnit.SECONDS);
            Identity identity = existing.isPresent() ? existing.get() : gate.resolve(xuid, floodgateUuid).join();
            data.setLinkedPlayer(LinkedPlayer.of(identity.gameName(), floodgateUuid, floodgateUuid));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            data.setDisconnectReason(messages.internalError());
        } catch (IdentityGate.GateClosedException closed) {
            data.setDisconnectReason("Bedrock connection closed.");
        } catch (CompletionException exception) {
            Throwable cause = unwrap(exception);
            if (cause instanceof IdentityGate.GateTimeoutException) {
                data.setDisconnectReason(messages.timeout());
            } else if (cause instanceof IdentityGate.GateClosedException) {
                data.setDisconnectReason("Bedrock connection closed.");
            } else {
                logger.log(Level.SEVERE, "BedrockPassport failed to resolve identity for XUID " + xuid, cause);
                data.setDisconnectReason(messages.internalError());
            }
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "BedrockPassport failed to resolve identity for XUID " + xuid, exception);
            data.setDisconnectReason(messages.internalError());
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
