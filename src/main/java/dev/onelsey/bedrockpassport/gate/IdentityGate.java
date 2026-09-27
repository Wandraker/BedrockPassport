package dev.onelsey.bedrockpassport.gate;

import dev.onelsey.bedrockpassport.data.ClaimResult;
import dev.onelsey.bedrockpassport.data.Identity;
import dev.onelsey.bedrockpassport.data.IdentityRepository;
import dev.onelsey.bedrockpassport.integration.GeyserPendingSessionBridge;
import dev.onelsey.bedrockpassport.name.NamePolicy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class IdentityGate implements AutoCloseable {
    private final IdentityRepository repository;
    private final GeyserPendingSessionBridge geyser;
    private final NamePolicy namePolicy;
    private final GateMessages messages;
    private final long inactivityTimeoutNanos;
    private final long holdingWorldInitTimeoutSeconds;
    private final Map<String, GateState> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog;

    public IdentityGate(
            IdentityRepository repository,
            GeyserPendingSessionBridge geyser,
            NamePolicy namePolicy,
            GateMessages messages,
            long inactivityTimeoutSeconds,
            long holdingWorldInitTimeoutSeconds
    ) {
        this.repository = repository;
        this.geyser = geyser;
        this.namePolicy = namePolicy;
        this.messages = messages;
        this.inactivityTimeoutNanos = TimeUnit.SECONDS.toNanos(inactivityTimeoutSeconds);
        this.holdingWorldInitTimeoutSeconds = holdingWorldInitTimeoutSeconds;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BedrockPassport-GateWatchdog");
            thread.setDaemon(true);
            return thread;
        });
        this.watchdog.scheduleAtFixedRate(this::checkTimeouts, 1, 1, TimeUnit.SECONDS);
    }

    public CompletableFuture<Identity> resolve(String xuid, UUID floodgateUuid) {
        GateState candidate = new GateState(xuid, floodgateUuid, geyser.findByXuid(xuid));
        GateState state = active.putIfAbsent(xuid, candidate);
        if (state != null) {
            return state.result;
        }

        candidate.result.whenComplete((identity, error) -> active.remove(xuid, candidate));
        start(candidate);
        return candidate.result;
    }

    private void start(GateState state) {
        if (state.handle == null) {
            state.result.completeExceptionally(new IllegalStateException("Pending Geyser session not found for XUID"));
            return;
        }

        try {
            geyser.enterHoldingWorld(state.handle, holdingWorldInitTimeoutSeconds).whenComplete((ready, error) -> {
                if (error != null) {
                    state.result.completeExceptionally(error);
                    return;
                }
                state.touch();
                showForm(state, null);
            });
        } catch (Throwable throwable) {
            state.result.completeExceptionally(throwable);
        }
    }

    private void showForm(GateState state, String error) {
        if (state.result.isDone() || state.handle == null) {
            return;
        }
        try {
            if (geyser.isClosed(state.handle)) {
                state.result.completeExceptionally(new GateClosedException());
                return;
            }
            state.touch();
            state.processing.set(false);
            geyser.showNicknameForm(
                    state.handle,
                    messages.title(),
                    messages.text(),
                    messages.inputLabel(),
                    messages.inputPlaceholder(),
                    state.lastInput,
                    error,
                    input -> onSubmit(state, input),
                    () -> onClosed(state),
                    state.result::completeExceptionally
            );
        } catch (Throwable throwable) {
            state.result.completeExceptionally(throwable);
        }
    }

    private void onSubmit(GateState state, String rawInput) {
        if (state.result.isDone() || !state.processing.compareAndSet(false, true)) {
            return;
        }
        state.touch();
        String name = namePolicy.normalize(rawInput);
        state.lastInput = name;
        if (!namePolicy.valid(name)) {
            showForm(state, messages.invalidName());
            return;
        }

        repository.claim(state.xuid, state.floodgateUuid, name).whenComplete((claim, error) -> {
            if (state.result.isDone()) {
                return;
            }
            if (error != null) {
                state.result.completeExceptionally(error);
                return;
            }
            if (claim.accepted()) {
                state.result.complete(claim.identity());
                return;
            }
            if (claim.status() == ClaimResult.Status.NAME_TAKEN) {
                showForm(state, messages.nameTaken());
                return;
            }
            state.result.completeExceptionally(new IllegalStateException("Unexpected identity claim status: " + claim.status()));
        });
    }

    private void onClosed(GateState state) {
        if (state.result.isDone()) {
            return;
        }
        state.touch();
        state.processing.set(false);
        try {
            geyser.schedule(state.handle, () -> showForm(state, null), 700L);
        } catch (Throwable throwable) {
            state.result.completeExceptionally(throwable);
        }
    }

    private void checkTimeouts() {
        long now = System.nanoTime();
        for (GateState state : active.values()) {
            if (state.result.isDone()) {
                continue;
            }
            try {
                if (state.handle != null && geyser.isClosed(state.handle)) {
                    state.result.completeExceptionally(new GateClosedException());
                    continue;
                }
                if (now - state.lastActivity.get() >= inactivityTimeoutNanos) {
                    state.result.completeExceptionally(new GateTimeoutException(messages.timeout()));
                }
            } catch (Throwable throwable) {
                state.result.completeExceptionally(throwable);
            }
        }
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        for (GateState state : active.values()) {
            state.result.completeExceptionally(new IllegalStateException("BedrockPassport is shutting down"));
        }
        active.clear();
    }

    private static final class GateState {
        private final String xuid;
        private final UUID floodgateUuid;
        private final GeyserPendingSessionBridge.SessionHandle handle;
        private final CompletableFuture<Identity> result = new CompletableFuture<>();
        private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        private final AtomicBoolean processing = new AtomicBoolean(false);
        private volatile String lastInput = "";

        private GateState(String xuid, UUID floodgateUuid, GeyserPendingSessionBridge.SessionHandle handle) {
            this.xuid = xuid;
            this.floodgateUuid = floodgateUuid;
            this.handle = handle;
        }

        private void touch() {
            lastActivity.set(System.nanoTime());
        }
    }

    public static final class GateTimeoutException extends RuntimeException {
        public GateTimeoutException(String message) {
            super(message);
        }
    }

    public static final class GateClosedException extends RuntimeException {
        public GateClosedException() {
            super("Bedrock connection closed during nickname selection");
        }
    }
}
