package dev.onelsey.bedrockpassport.gate;

import static dev.onelsey.bedrockpassport.gate.GateSupport.unwrap;
import static dev.onelsey.bedrockpassport.gate.GateSupport.withError;

import dev.onelsey.bedrockpassport.data.ClaimResult;
import dev.onelsey.bedrockpassport.data.Identity;
import dev.onelsey.bedrockpassport.data.IdentityRepository;
import dev.onelsey.bedrockpassport.identity.JavaUuidResolver;
import dev.onelsey.bedrockpassport.integration.GeyserPendingSessionBridge;
import dev.onelsey.bedrockpassport.name.NamePolicy;
import dev.onelsey.bedrockpassport.security.SessionGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class IdentityGate implements AutoCloseable {
    private final IdentityRepository repository;
    private final GeyserPendingSessionBridge geyser;
    private final NamePolicy namePolicy;
    private final JavaUuidResolver uuidResolver;
    private final SessionGuard sessionGuard;
    private final GateMessages messages;
    private final AccountManagementFlow accountManagement;
    private final int maxAccounts;
    private final long inactivityTimeoutNanos;
    private final long holdingWorldInitTimeoutSeconds;
    private final long formTransitionDelayMillis;
    private final Map<String, GateState> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog;

    public IdentityGate(
            IdentityRepository repository,
            GeyserPendingSessionBridge geyser,
            NamePolicy namePolicy,
            JavaUuidResolver uuidResolver,
            SessionGuard sessionGuard,
            GateMessages messages,
            int maxAccounts,
            long inactivityTimeoutSeconds,
            long holdingWorldInitTimeoutSeconds,
            long formTransitionDelayMillis
    ) {
        this.repository = repository;
        this.geyser = geyser;
        this.namePolicy = namePolicy;
        this.uuidResolver = uuidResolver;
        this.sessionGuard = sessionGuard;
        this.messages = messages;
        this.maxAccounts = maxAccounts;
        this.accountManagement = new AccountManagementFlow(repository, geyser, messages, state -> reloadHome(state, null));
        this.inactivityTimeoutNanos = TimeUnit.SECONDS.toNanos(inactivityTimeoutSeconds);
        this.holdingWorldInitTimeoutSeconds = holdingWorldInitTimeoutSeconds;
        this.formTransitionDelayMillis = Math.max(0L, formTransitionDelayMillis);
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BedrockPassport-GateWatchdog");
            thread.setDaemon(true);
            return thread;
        });
        this.watchdog.scheduleAtFixedRate(this::checkTimeouts, 1, 1, TimeUnit.SECONDS);
    }

    public CompletableFuture<Identity> resolve(String xuid, UUID floodgateUuid) {
        if (sessionGuard.isBedrockXuidBusy(xuid)) {
            return CompletableFuture.failedFuture(new PassportSessionBusyException(messages.passportInUse()));
        }
        GateState candidate = new GateState(xuid, floodgateUuid, geyser.findByXuid(xuid));
        GateState state = active.putIfAbsent(xuid, candidate);
        if (state != null) {
            return CompletableFuture.failedFuture(new PassportSessionBusyException(messages.passportInUse()));
        }

        candidate.result.whenComplete((identity, error) -> {
            active.remove(xuid, candidate);
            if (candidate.downstreamReadTimeoutLease != null) {
                candidate.downstreamReadTimeoutLease.close();
                candidate.downstreamReadTimeoutLease = null;
            }
            if (error != null && candidate.reservedJavaUuid != null) {
                sessionGuard.releaseBedrockReservation(candidate.reservedJavaUuid, candidate.xuid);
                candidate.reservedJavaUuid = null;
            }
        });
        start(candidate);
        return candidate.result;
    }

    private void start(GateState state) {
        if (state.handle == null) {
            state.result.completeExceptionally(new IllegalStateException("Pending Geyser session not found for XUID"));
            return;
        }

        try {
            state.downstreamReadTimeoutLease = geyser.suspendDownstreamReadTimeout(state.handle);
            geyser.enterHoldingWorld(state.handle, holdingWorldInitTimeoutSeconds).whenComplete((ready, error) -> {
                if (error != null) {
                    state.result.completeExceptionally(error);
                    return;
                }
                state.touch();
                reloadHome(state, null);
            });
        } catch (Throwable throwable) {
            state.result.completeExceptionally(throwable);
        }
    }

    private void reloadHome(GateState state, String errorMessage) {
        if (state.result.isDone()) {
            return;
        }
        repository.listByXuid(state.xuid).whenComplete((accounts, error) -> {
            if (state.result.isDone()) {
                return;
            }
            if (error != null) {
                state.result.completeExceptionally(unwrap(error));
                return;
            }
            if (accounts.isEmpty()) {
                showNicknameForm(state, errorMessage, false);
            } else {
                showSelector(state, accounts, errorMessage);
            }
        });
    }

    private void showSelector(GateState state, List<Identity> accounts, String errorMessage) {
        if (!canShow(state)) {
            return;
        }

        long screen = state.beginScreen();
        List<String> buttons = new ArrayList<>();
        for (int i = 0; i < accounts.size(); i++) {
            Identity identity = accounts.get(i);
            buttons.add(identity.gameName() + (i == 0 ? messages.lastUsedSuffix() : ""));
        }

        boolean canAdd = maxAccounts <= 0 || accounts.size() < maxAccounts;
        int addIndex = canAdd ? buttons.size() : -1;
        if (canAdd) {
            buttons.add(messages.addAccount());
        }
        int manageIndex = buttons.size();
        buttons.add(messages.manageAccounts());

        String content = withError(messages.selectorText(), errorMessage);
        geyser.showMenu(
                state.handle,
                messages.selectorTitle(),
                content,
                buttons,
                index -> {
                    if (!state.claimAction(screen)) {
                        return;
                    }
                    state.touch();
                    if (index >= 0 && index < accounts.size()) {
                        activateIdentity(state, accounts.get(index), false);
                    } else if (index == addIndex) {
                        showNicknameForm(state, null, true);
                    } else if (index == manageIndex) {
                        accountManagement.show(state, accounts);
                    } else {
                        state.result.completeExceptionally(new IllegalStateException("Unexpected BedrockPassport selector button: " + index));
                    }
                },
                () -> reopenHomeAfterClose(state, screen),
                state.result::completeExceptionally
        );
    }

    private void showNicknameForm(GateState state, String errorMessage, boolean returnToHomeOnClose) {
        if (!canShow(state)) {
            return;
        }

        long screen = state.beginScreen();
        geyser.showNicknameForm(
                state.handle,
                messages.title(),
                messages.text(),
                messages.inputLabel(),
                messages.inputPlaceholder(),
                state.lastInput,
                errorMessage,
                input -> {
                    if (!state.claimAction(screen)) {
                        return;
                    }
                    state.touch();
                    onNicknameSubmit(state, input);
                },
                () -> {
                    if (!state.claimAction(screen)) {
                        return;
                    }
                    try {
                        geyser.schedule(state.handle, () -> {
                            if (returnToHomeOnClose) {
                                reloadHome(state, null);
                            } else {
                                showNicknameForm(state, null, false);
                            }
                        }, 700L);
                    } catch (Throwable throwable) {
                        state.result.completeExceptionally(throwable);
                    }
                },
                state.result::completeExceptionally
        );
    }

    private void onNicknameSubmit(GateState state, String rawInput) {
        String name = namePolicy.normalize(rawInput);
        state.lastInput = name;
        if (!namePolicy.valid(name)) {
            showNicknameForm(state, messages.invalidName(), true);
            return;
        }

        uuidResolver.resolve(name).whenComplete((javaUuid, uuidError) -> {
            if (state.result.isDone()) {
                return;
            }
            if (uuidError != null) {
                state.result.completeExceptionally(unwrap(uuidError));
                return;
            }

            repository.claim(state.xuid, name, javaUuid, uuidResolver.mode(), maxAccounts).whenComplete((claim, claimError) -> {
                if (state.result.isDone()) {
                    return;
                }
                if (claimError != null) {
                    state.result.completeExceptionally(unwrap(claimError));
                    return;
                }
                if (claim.accepted()) {
                    activateIdentity(state, claim.identity(), true);
                    return;
                }
                if (claim.status() == ClaimResult.Status.NAME_TAKEN) {
                    showNicknameForm(state, messages.nameTaken(), true);
                    return;
                }
                if (claim.status() == ClaimResult.Status.LIMIT_REACHED) {
                    reloadHome(state, messages.limitReached());
                    return;
                }
                state.result.completeExceptionally(new IllegalStateException("Unexpected identity claim status: " + claim.status()));
            });
        });
    }

    private void activateIdentity(GateState state, Identity identity, boolean afterTextInput) {
        if (state.result.isDone()) {
            return;
        }

        CompletableFuture<Identity> prepared;
        if (identity.javaUuid() != null && uuidResolver.mode().equals(identity.uuidMode())) {
            prepared = CompletableFuture.completedFuture(identity);
        } else {
            prepared = uuidResolver.resolve(identity.gameName())
                    .thenCompose(uuid -> repository.updateJavaIdentity(identity.id(), state.xuid, uuid, uuidResolver.mode()));
        }

        prepared.whenComplete((preparedIdentity, prepareError) -> {
            if (state.result.isDone()) {
                return;
            }
            if (prepareError != null) {
                state.result.completeExceptionally(unwrap(prepareError));
                return;
            }
            if (sessionGuard.reserveForBedrock(preparedIdentity, state.xuid) == SessionGuard.ReservationResult.IN_USE) {
                reloadHome(state, messages.accountInUse());
                return;
            }
            state.reservedJavaUuid = preparedIdentity.javaUuid();

            repository.markUsed(preparedIdentity.id(), state.xuid).whenComplete((selected, markError) -> {
                if (state.result.isDone()) {
                    sessionGuard.releaseBedrockReservation(preparedIdentity.javaUuid(), state.xuid);
                    state.reservedJavaUuid = null;
                    return;
                }
                if (markError != null) {
                    sessionGuard.releaseBedrockReservation(preparedIdentity.javaUuid(), state.xuid);
                    state.reservedJavaUuid = null;
                    state.result.completeExceptionally(unwrap(markError));
                    return;
                }
                completeSelection(state, selected, afterTextInput);
            });
        });
    }

    private void completeSelection(GateState state, Identity selected, boolean afterTextInput) {
        if (!afterTextInput || formTransitionDelayMillis <= 0L) {
            state.result.complete(selected);
            return;
        }
        try {
            geyser.schedule(state.handle, () -> {
                if (!state.result.isDone()) {
                    state.result.complete(selected);
                }
            }, formTransitionDelayMillis);
        } catch (Throwable throwable) {
            if (state.reservedJavaUuid != null) {
                sessionGuard.releaseBedrockReservation(state.reservedJavaUuid, state.xuid);
                state.reservedJavaUuid = null;
            }
            state.result.completeExceptionally(throwable);
        }
    }

    public void releaseReservation(String xuid, UUID javaUuid) {
        sessionGuard.releaseBedrockReservation(javaUuid, xuid);
    }

    public int activeSelectionCount() {
        return active.size();
    }

    private void reopenHomeAfterClose(GateState state, long screen) {
        if (!state.claimAction(screen)) {
            return;
        }
        try {
            geyser.schedule(state.handle, () -> reloadHome(state, null), 700L);
        } catch (Throwable throwable) {
            state.result.completeExceptionally(throwable);
        }
    }

    private boolean canShow(GateState state) {
        if (state.result.isDone() || state.handle == null) {
            return false;
        }
        try {
            if (geyser.isClosed(state.handle)) {
                state.result.completeExceptionally(new GateClosedException());
                return false;
            }
            return true;
        } catch (Throwable throwable) {
            state.result.completeExceptionally(throwable);
            return false;
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
}
