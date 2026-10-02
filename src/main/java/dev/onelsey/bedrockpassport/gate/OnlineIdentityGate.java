package dev.onelsey.bedrockpassport.gate;

import dev.onelsey.bedrockpassport.data.ClaimResult;
import dev.onelsey.bedrockpassport.data.Identity;
import dev.onelsey.bedrockpassport.data.IdentityRepository;
import dev.onelsey.bedrockpassport.identity.IdentityProviderType;
import dev.onelsey.bedrockpassport.integration.GeyserOnlineAuthBridge;
import dev.onelsey.bedrockpassport.integration.GeyserPendingSessionBridge;
import dev.onelsey.bedrockpassport.i18n.LocalizedMessages;
import dev.onelsey.bedrockpassport.security.CredentialVault;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OnlineIdentityGate implements AutoCloseable {
    private static final String UUID_MODE = "online";

    private final IdentityRepository repository;
    private final CredentialVault credentialVault;
    private final GeyserPendingSessionBridge geyser;
    private final GeyserOnlineAuthBridge authBridge;
    private final Logger logger;
    private final LocalizedMessages messages;
    private final int maxAccounts;
    private final long inactivityTimeoutNanos;

    private final Map<String, Flow> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog;
    private final Object credentialMaintenanceLock = new Object();
    private boolean credentialMaintenance;

    public OnlineIdentityGate(
            IdentityRepository repository,
            CredentialVault credentialVault,
            GeyserPendingSessionBridge geyser,
            GeyserOnlineAuthBridge authBridge,
            Logger logger,
            LocalizedMessages messages,
            int maxAccounts,
            long inactivityTimeoutSeconds
    ) {
        this.repository = repository;
        this.credentialVault = credentialVault;
        this.geyser = geyser;
        this.authBridge = authBridge;
        this.logger = logger;
        this.messages = messages;
        this.maxAccounts = maxAccounts;
        this.inactivityTimeoutNanos = TimeUnit.SECONDS.toNanos(Math.max(15L, inactivityTimeoutSeconds));

        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BedrockPassport-OnlineGate");
            thread.setDaemon(true);
            return thread;
        });
        this.watchdog.scheduleAtFixedRate(this::expireInactive, 1L, 1L, TimeUnit.SECONDS);
    }

    public void open(GeyserOnlineAuthBridge.HeldSession held, String locale) {
        Flow flow = new Flow(held, locale);
        Flow previous = null;
        boolean maintenance;
        synchronized (credentialMaintenanceLock) {
            maintenance = credentialMaintenance;
            if (!maintenance) {
                previous = active.putIfAbsent(held.xuid(), flow);
            }
        }
        if (maintenance) {
            authBridge.disconnect(held, messages.prefixed(locale, "online.maintenance"));
            return;
        }
        if (previous != null) {
            authBridge.disconnect(held, messages.prefixed(locale, "online.active-session"));
            return;
        }
        showHome(flow, null);
    }

    public boolean beginCredentialMaintenance() {
        synchronized (credentialMaintenanceLock) {
            if (credentialMaintenance || !active.isEmpty()) {
                return false;
            }
            credentialMaintenance = true;
            return true;
        }
    }

    public void endCredentialMaintenance() {
        synchronized (credentialMaintenanceLock) {
            credentialMaintenance = false;
        }
    }

    public void handleDisconnect(String xuid) {
        Flow flow = active.remove(xuid);
        if (flow != null) {
            flow.finished = true;
        }
    }

    public int activeSelectionCount() {
        return active.size();
    }

    private void showHome(Flow flow, String notice) {
        if (!usable(flow)) {
            return;
        }
        flow.touch();
        repository.listByXuid(flow.held.xuid(), IdentityProviderType.JAVA_ACCOUNT).whenComplete((accounts, error) -> {
            if (!usable(flow)) {
                return;
            }
            if (error != null) {
                fail(flow, error);
                return;
            }

            long screen = flow.beginScreen();
            List<String> buttons = new ArrayList<>();
            for (int i = 0; i < accounts.size(); i++) {
                Identity identity = accounts.get(i);
                buttons.add("§b" + identity.gameName() + (i == 0 ? text(flow, "selector.last-used-suffix") : ""));
            }
            int addIndex = buttons.size();
            buttons.add(text(flow, "online.add-account"));
            int manageIndex = -1;
            if (!accounts.isEmpty()) {
                manageIndex = buttons.size();
                buttons.add(text(flow, "online.manage-accounts"));
            }
            int finalManageIndex = manageIndex;

            String content = text(flow, "online.selector-text");
            if (notice != null && !notice.isBlank()) {
                content = "§e" + notice + "\n\n" + content;
            }

            geyser.showMenu(
                    flow.held.handle(),
                    text(flow, "selector.title"),
                    content,
                    buttons,
                    index -> {
                        if (!flow.claimScreen(screen)) {
                            return;
                        }
                        flow.touch();
                        if (index >= 0 && index < accounts.size()) {
                            connectExisting(flow, accounts.get(index));
                        } else if (index == addIndex) {
                            if (maxAccounts > 0 && accounts.size() >= maxAccounts) {
                                showHome(flow, text(flow, "online.limit-reached"));
                            } else {
                                addJavaAccount(flow);
                            }
                        } else if (index == finalManageIndex) {
                            showManage(flow, accounts);
                        } else {
                            fail(flow, new IllegalStateException("Unexpected online Passport button: " + index));
                        }
                    },
                    () -> reopenHome(flow, screen),
                    throwable -> fail(flow, throwable)
            );
        });
    }

    private void connectExisting(Flow flow, Identity identity) {
        flow.externalAuth = true;
        repository.findCredential(identity.id(), flow.held.xuid()).whenComplete((stored, error) -> {
            if (!usable(flow)) {
                return;
            }
            if (error != null) {
                flow.externalAuth = false;
                fail(flow, error);
                return;
            }
            if (stored.isEmpty()) {
                flow.externalAuth = false;
                offerReauthentication(flow, identity, text(flow, "online.reauth-required"));
                return;
            }

            String authChain;
            try {
                authChain = decrypt(identity, flow.held.xuid(), stored.get());
            } catch (Throwable throwable) {
                flow.externalAuth = false;
                offerReauthentication(flow, identity, text(flow, "online.credential-decrypt-failed"));
                return;
            }

            authBridge.authenticateStored(flow.held, authChain).whenComplete((account, authError) -> {
                if (!usable(flow)) {
                    return;
                }
                if (authError != null) {
                    flow.externalAuth = false;
                    offerReauthentication(flow, identity, text(flow, "online.credential-refresh-required"));
                    return;
                }
                if (!identity.javaUuid().equals(account.javaUuid())) {
                    flow.externalAuth = false;
                    fail(flow, new IllegalStateException("Stored credential resolved to a different Java UUID"));
                    return;
                }
                persistAndConnect(flow, identity, account);
            });
        });
    }

    private void addJavaAccount(Flow flow) {
        if (!usable(flow)) {
            return;
        }
        flow.externalAuth = true;
        authBridge.authenticateNew(flow.held).whenComplete((account, error) -> {
            if (!usable(flow)) {
                return;
            }
            if (error != null) {
                flow.externalAuth = false;
                authBridge.resumeSelection(flow.held);
                showHome(flow, text(flow, "online.sign-in-failed-retry"));
                return;
            }

            repository.claim(
                    flow.held.xuid(),
                    IdentityProviderType.JAVA_ACCOUNT,
                    account.javaUsername(),
                    account.javaUuid(),
                    UUID_MODE,
                    maxAccounts
            ).whenComplete((claim, claimError) -> {
                if (!usable(flow)) {
                    return;
                }
                if (claimError != null) {
                    flow.externalAuth = false;
                    fail(flow, claimError);
                    return;
                }
                if (!claim.accepted() || claim.identity() == null) {
                    flow.externalAuth = false;
                    authBridge.resumeSelection(flow.held);
                    if (claim.status() == ClaimResult.Status.LIMIT_REACHED) {
                        showHome(flow, text(flow, "online.limit-reached"));
                    } else {
                        showHome(flow, text(flow, "online.account-save-failed"));
                    }
                    return;
                }
                if (!account.javaUuid().equals(claim.identity().javaUuid())) {
                    flow.externalAuth = false;
                    authBridge.resumeSelection(flow.held);
                    fail(flow, new IllegalStateException("Existing Passport identity has a different verified Java UUID"));
                    return;
                }
                persistAndConnect(flow, claim.identity(), account);
            });
        });
    }

    private void offerReauthentication(Flow flow, Identity identity, String reason) {
        if (!usable(flow)) {
            return;
        }
        long screen = flow.beginScreen();
        geyser.showConfirmation(
                flow.held.handle(),
                text(flow, "online.reauth-title"),
                text(flow, "online.reauth-text", Map.of("reason", reason, "account", identity.gameName())),
                text(flow, "online.sign-in-button"),
                text(flow, "manage.back"),
                confirmed -> {
                    if (!flow.claimScreen(screen)) {
                        return;
                    }
                    if (confirmed) {
                        reauthenticate(flow, identity);
                    } else {
                        showHome(flow, null);
                    }
                },
                () -> reopenHome(flow, screen),
                throwable -> fail(flow, throwable)
        );
    }

    private void reauthenticate(Flow flow, Identity identity) {
        flow.externalAuth = true;
        authBridge.authenticateNew(flow.held).whenComplete((account, error) -> {
            if (!usable(flow)) {
                return;
            }
            if (error != null) {
                flow.externalAuth = false;
                authBridge.resumeSelection(flow.held);
                showHome(flow, text(flow, "online.sign-in-failed"));
                return;
            }
            if (!identity.javaUuid().equals(account.javaUuid())) {
                flow.externalAuth = false;
                authBridge.resumeSelection(flow.held);
                showHome(flow, text(flow, "online.wrong-account", Map.of("account", identity.gameName())));
                return;
            }
            persistAndConnect(flow, identity, account);
        });
    }

    private void persistAndConnect(
            Flow flow,
            Identity identity,
            GeyserOnlineAuthBridge.AuthenticatedJavaAccount account
    ) {
        String encrypted;
        try {
            encrypted = credentialVault.encrypt(account.authChain(), flow.held.xuid(), account.javaUuid());
        } catch (GeneralSecurityException exception) {
            flow.externalAuth = false;
            fail(flow, exception);
            return;
        }

        repository.saveCredential(
                identity.id(),
                flow.held.xuid(),
                CredentialVault.FORMAT,
                encrypted
        ).thenCompose(ignored -> repository.markUsed(identity.id(), flow.held.xuid()))
                .thenCompose(ignored -> authBridge.connect(flow.held, account))
                .whenComplete((ignored, error) -> {
                    flow.externalAuth = false;
                    if (error != null) {
                        fail(flow, error);
                        return;
                    }
                    finish(flow);
                });
    }

    private String decrypt(Identity identity, String xuid, IdentityRepository.StoredCredential stored)
            throws GeneralSecurityException {
        if (!CredentialVault.FORMAT.equals(stored.credentialFormat())) {
            throw new GeneralSecurityException("Unsupported credential format: " + stored.credentialFormat());
        }
        return credentialVault.decrypt(stored.encryptedBlob(), xuid, identity.javaUuid());
    }

    private void showManage(Flow flow, List<Identity> accounts) {
        if (!usable(flow)) {
            return;
        }
        if (accounts.isEmpty()) {
            showHome(flow, null);
            return;
        }
        long screen = flow.beginScreen();
        List<String> buttons = new ArrayList<>();
        for (Identity identity : accounts) {
            buttons.add(text(flow, "manage.remove-prefix") + identity.gameName());
        }
        int backIndex = buttons.size();
        buttons.add(text(flow, "manage.back"));

        geyser.showMenu(
                flow.held.handle(),
                text(flow, "online.manage-title"),
                text(flow, "online.manage-text"),
                buttons,
                index -> {
                    if (!flow.claimScreen(screen)) {
                        return;
                    }
                    flow.touch();
                    if (index >= 0 && index < accounts.size()) {
                        confirmRemove(flow, accounts.get(index));
                    } else if (index == backIndex) {
                        showHome(flow, null);
                    } else {
                        fail(flow, new IllegalStateException("Unexpected online management button: " + index));
                    }
                },
                () -> reopenHome(flow, screen),
                throwable -> fail(flow, throwable)
        );
    }

    private void confirmRemove(Flow flow, Identity identity) {
        if (!usable(flow)) {
            return;
        }
        long screen = flow.beginScreen();
        geyser.showConfirmation(
                flow.held.handle(),
                text(flow, "manage.confirm-title"),
                text(flow, "online.confirm-text", Map.of("account", identity.gameName())),
                text(flow, "manage.confirm-button"),
                text(flow, "manage.cancel-button"),
                confirmed -> {
                    if (!flow.claimScreen(screen)) {
                        return;
                    }
                    flow.touch();
                    if (!confirmed) {
                        showHome(flow, null);
                        return;
                    }
                    repository.remove(identity.id(), flow.held.xuid()).whenComplete((removed, error) -> {
                        if (!usable(flow)) {
                            return;
                        }
                        if (error != null) {
                            fail(flow, error);
                        } else {
                            showHome(flow, removed ? text(flow, "online.removed") : text(flow, "online.already-removed"));
                        }
                    });
                },
                () -> reopenHome(flow, screen),
                throwable -> fail(flow, throwable)
        );
    }

    private void reopenHome(Flow flow, long screen) {
        if (!flow.claimScreen(screen) || !usable(flow)) {
            return;
        }
        try {
            geyser.schedule(flow.held.handle(), () -> showHome(flow, null), 700L);
        } catch (Throwable throwable) {
            fail(flow, throwable);
        }
    }

    private boolean usable(Flow flow) {
        return !flow.finished && active.get(flow.held.xuid()) == flow;
    }

    private void finish(Flow flow) {
        flow.finished = true;
        active.remove(flow.held.xuid(), flow);
    }

    private void fail(Flow flow, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        logger.log(Level.SEVERE, "BedrockPassport online identity flow failed for XUID " + flow.held.xuid(), cause);
        if (active.remove(flow.held.xuid(), flow)) {
            flow.finished = true;
            authBridge.disconnect(flow.held, prefixed(flow, "online.auth-failed"));
        }
    }

    private void expireInactive() {
        long now = System.nanoTime();
        for (Flow flow : active.values()) {
            if (flow.externalAuth || flow.finished) {
                continue;
            }
            if (now - flow.lastTouchedNanos > inactivityTimeoutNanos && active.remove(flow.held.xuid(), flow)) {
                flow.finished = true;
                authBridge.disconnect(flow.held, prefixed(flow, "online.timeout"));
            }
        }
    }

    private String text(Flow flow, String key) {
        return messages.text(flow.locale, key);
    }

    private String text(Flow flow, String key, Map<String, ?> placeholders) {
        return messages.text(flow.locale, key, placeholders);
    }

    private String prefixed(Flow flow, String key) {
        return messages.prefixed(flow.locale, key);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        watchdog.shutdownNow();
        for (Flow flow : active.values()) {
            flow.finished = true;
            authBridge.disconnect(flow.held, prefixed(flow, "online.reloading"));
        }
        active.clear();
    }

    private static final class Flow {
        private final GeyserOnlineAuthBridge.HeldSession held;
        private final String locale;
        private volatile long lastTouchedNanos = System.nanoTime();
        private volatile boolean externalAuth;
        private volatile boolean finished;
        private long screen;

        private Flow(GeyserOnlineAuthBridge.HeldSession held, String locale) {
            this.held = held;
            this.locale = locale;
        }

        private synchronized long beginScreen() {
            return ++screen;
        }

        private synchronized boolean claimScreen(long expected) {
            if (finished || screen != expected) {
                return false;
            }
            screen++;
            return true;
        }

        private void touch() {
            lastTouchedNanos = System.nanoTime();
        }
    }
}
