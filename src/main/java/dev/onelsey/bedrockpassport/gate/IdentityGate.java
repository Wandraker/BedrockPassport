package dev.onelsey.bedrockpassport.gate;

import static dev.onelsey.bedrockpassport.gate.GateSupport.unwrap;
import static dev.onelsey.bedrockpassport.gate.GateSupport.withError;

import dev.onelsey.bedrockpassport.data.ClaimResult;
import dev.onelsey.bedrockpassport.data.Identity;
import dev.onelsey.bedrockpassport.data.IdentityRepository;
import dev.onelsey.bedrockpassport.identity.LocalIdentityCanonicalizer;
import dev.onelsey.bedrockpassport.identity.LocalIdentityProvider;
import dev.onelsey.bedrockpassport.integration.GeyserPendingSessionBridge;
import dev.onelsey.bedrockpassport.i18n.LocalizedMessages;
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
    private final LocalIdentityProvider identityProvider;
    private final LocalIdentityCanonicalizer canonicalizer;
    private final SessionGuard sessionGuard;
    private final LocalizedMessages localizedMessages;
    private final AccountManagementFlow accountManagement;
    private final int maxAccounts;
    private final long inactivityTimeoutNanos;
    private final long formTransitionDelayMillis;
    private final long holdingInitTimeoutSeconds;
    private final Map<String, GateState> active = new ConcurrentHashMap<>();
    private final Map<String, Identity> prepared = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog;

    public IdentityGate(
            IdentityRepository repository,
            GeyserPendingSessionBridge geyser,
            NamePolicy namePolicy,
            LocalIdentityProvider identityProvider,
            LocalIdentityCanonicalizer canonicalizer,
            SessionGuard sessionGuard,
            LocalizedMessages localizedMessages,
            int maxAccounts,
            long inactivityTimeoutSeconds,
            long formTransitionDelayMillis,
            long holdingInitTimeoutSeconds
    ) {
        this.repository = repository;
        this.geyser = geyser;
        this.namePolicy = namePolicy;
        this.identityProvider = identityProvider;
        this.canonicalizer = canonicalizer;
        this.sessionGuard = sessionGuard;
        this.localizedMessages = localizedMessages;
        this.maxAccounts = maxAccounts;
        this.accountManagement = new AccountManagementFlow(repository, geyser, state -> reloadHome(state, null));
        this.inactivityTimeoutNanos = TimeUnit.SECONDS.toNanos(inactivityTimeoutSeconds);
        this.formTransitionDelayMillis = Math.max(0L, formTransitionDelayMillis);
        // Same floor as JAVA_ACCOUNT: slow devices can take a while to load the holding world.
        this.holdingInitTimeoutSeconds = Math.max(60L, holdingInitTimeoutSeconds);
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BedrockPassport-GateWatchdog");
            thread.setDaemon(true);
            return thread;
        });
        this.watchdog.scheduleAtFixedRate(this::checkTimeouts, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Runs the selector while the Bedrock player waits in Geyser's holding world, before any Java
     * backend connection exists. The returned future completes only after the selected identity
     * has been staged for {@link #takePrepared(String)}.
     */
    public CompletableFuture<Identity> open(GeyserPendingSessionBridge.SessionHandle handle, String xuid, String playerLocale) {
        String locale = localizedMessages.resolvePlayerLocale(playerLocale);
        GateMessages flowMessages = GateMessages.localized(localizedMessages, locale);
        if (sessionGuard.isBedrockXuidBusy(xuid)) {
            return CompletableFuture.failedFuture(new PassportSessionBusyException(flowMessages.passportInUse()));
        }
        GateState candidate = new GateState(xuid, floodgateUuid(xuid), handle, locale, flowMessages);
        GateState state = active.putIfAbsent(xuid, candidate);
        if (state != null) {
            return CompletableFuture.failedFuture(new PassportSessionBusyException(flowMessages.passportInUse()));
        }

        CompletableFuture<Identity> staged = candidate.result.whenComplete((identity, error) -> {
            active.remove(xuid, candidate);
            if (error != null) {
                if (candidate.reservedJavaUuid != null) {
                    sessionGuard.releaseBedrockReservation(candidate.reservedJavaUuid, candidate.xuid);
                    candidate.reservedJavaUuid = null;
                }
                return;
            }
            prepared.put(xuid, identity);
            if (isClosedQuietly(candidate.handle)) {
                prepared.remove(xuid, identity);
                sessionGuard.releaseBedrockReservation(identity.javaUuid(), xuid);
            }
        });
        start(candidate);
        return staged;
    }

    /**
     * Returns the identity chosen in the holding world, if any. Called from Floodgate's handshake
     * handler, so it must never wait.
     */
    public Identity takePrepared(String xuid) {
        return xuid == null ? null : prepared.remove(xuid);
    }

    private void start(GateState state) {
        try {
            // Geyser only flushes queued forms on client initialization once the Java login has
            // connected. There is no backend connection yet, so wait for the client to finish
            // loading the holding world; forms are then sent immediately.
            geyser.enterHoldingWorld(state.handle)
                    .thenCompose(ready -> geyser.awaitInitialized(state.handle, holdingInitTimeoutSeconds))
                    .whenComplete((ready, error) -> {
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
            buttons.add(identity.gameName() + (i == 0 ? state.messages.lastUsedSuffix() : ""));
        }

        boolean canAdd = maxAccounts <= 0 || accounts.size() < maxAccounts;
        int addIndex = canAdd ? buttons.size() : -1;
        if (canAdd) {
            buttons.add(state.messages.addAccount());
        }
        int manageIndex = buttons.size();
        buttons.add(state.messages.manageAccounts());

        String content = withError(state.messages.selectorText(), errorMessage);
        geyser.showMenu(
                state.handle,
                state.messages.selectorTitle(),
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
                state.messages.title(),
                state.messages.text(),
                state.messages.inputLabel(),
                state.messages.inputPlaceholder(),
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
            showNicknameForm(state, state.messages.invalidName(), true);
            return;
        }

        canonicalizer.resolve(name).whenComplete((canonical, canonicalError) -> {
            if (state.result.isDone()) {
                return;
            }
            if (canonicalError != null) {
                state.result.completeExceptionally(unwrap(canonicalError));
                return;
            }

            repository.claimCanonicalLocal(
                    state.xuid,
                    canonical.gameName(),
                    canonical.javaUuid(),
                    canonicalizer.uuidMode(),
                    maxAccounts,
                    canonical.source() == LocalIdentityCanonicalizer.Source.AUTHME
            ).whenComplete((claim, claimError) -> {
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
                    showNicknameForm(state, state.messages.nameTaken(), true);
                    return;
                }
                if (claim.status() == ClaimResult.Status.LIMIT_REACHED) {
                    reloadHome(state, state.messages.limitReached());
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

        if (identity.providerType() != identityProvider.type()) {
            state.result.completeExceptionally(new IllegalStateException(
                    "Identity provider " + identity.providerType().storageKey() + " is not available in the current LOCAL runtime"
            ));
            return;
        }

        CompletableFuture<Identity> prepared = canonicalizer.resolve(identity.gameName())
                .thenCompose(canonical -> {
                    boolean alreadyCanonical = identity.gameName().equals(canonical.gameName())
                            && canonical.javaUuid().equals(identity.javaUuid())
                            && canonicalizer.uuidMode().equals(identity.uuidMode());
                    if (alreadyCanonical) {
                        return CompletableFuture.completedFuture(identity);
                    }
                    return repository.updateCanonicalIdentity(
                            identity.id(),
                            state.xuid,
                            canonical.gameName(),
                            canonical.javaUuid(),
                            canonicalizer.uuidMode()
                    );
                });

        prepared.whenComplete((preparedIdentity, prepareError) -> {
            if (state.result.isDone()) {
                return;
            }
            if (prepareError != null) {
                state.result.completeExceptionally(unwrap(prepareError));
                return;
            }
            if (sessionGuard.reserveForBedrock(preparedIdentity, state.xuid) == SessionGuard.ReservationResult.IN_USE) {
                reloadHome(state, state.messages.accountInUse());
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

    public boolean armInitialMovementGuard(String xuid) {
        GeyserPendingSessionBridge.SessionHandle handle = geyser.findByXuid(xuid);
        return handle != null && geyser.armInitialMovementGuard(handle);
    }

    public void releaseReservation(String xuid, UUID javaUuid) {
        sessionGuard.releaseBedrockReservation(javaUuid, xuid);
    }

    public void handleBedrockDisconnect(String xuid) {
        if (xuid == null || xuid.isBlank()) {
            return;
        }
        prepared.remove(xuid);
        GateState state = active.get(xuid);
        if (state != null) {
            state.result.completeExceptionally(new GateClosedException());
        }
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
                    state.result.completeExceptionally(new GateTimeoutException(state.messages.timeout()));
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
        prepared.clear();
    }

    private boolean isClosedQuietly(GeyserPendingSessionBridge.SessionHandle handle) {
        try {
            return geyser.isClosed(handle);
        } catch (Throwable ignored) {
            return true;
        }
    }

    // Floodgate's Java UUID for a Bedrock player: the XUID in the low bits.
    private static UUID floodgateUuid(String xuid) {
        try {
            return new UUID(0L, Long.parseLong(xuid));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
