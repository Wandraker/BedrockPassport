package dev.onelsey.bedrockpassport.gate;

import dev.onelsey.bedrockpassport.data.Identity;
import dev.onelsey.bedrockpassport.integration.GeyserPendingSessionBridge;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class GateState {
    final String xuid;
    final UUID floodgateUuid;
    final GeyserPendingSessionBridge.SessionHandle handle;
    final CompletableFuture<Identity> result = new CompletableFuture<>();
    final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
    private final AtomicLong screenSequence = new AtomicLong();
    private final AtomicBoolean actionInFlight = new AtomicBoolean(false);
    volatile String lastInput = "";
    volatile UUID reservedJavaUuid;

    GateState(String xuid, UUID floodgateUuid, GeyserPendingSessionBridge.SessionHandle handle) {
        this.xuid = xuid;
        this.floodgateUuid = floodgateUuid;
        this.handle = handle;
    }

    void touch() {
        lastActivity.set(System.nanoTime());
    }

    long beginScreen() {
        actionInFlight.set(false);
        return screenSequence.incrementAndGet();
    }

    boolean claimAction(long screen) {
        return screenSequence.get() == screen && actionInFlight.compareAndSet(false, true);
    }
}
