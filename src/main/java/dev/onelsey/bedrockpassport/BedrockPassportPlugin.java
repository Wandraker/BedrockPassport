package dev.onelsey.bedrockpassport;

import dev.onelsey.bedrockpassport.access.BedrockAccessPolicy;
import dev.onelsey.bedrockpassport.command.BedrockPassportAdminCommand;
import dev.onelsey.bedrockpassport.config.ConfigMigrator;
import dev.onelsey.bedrockpassport.i18n.LocalizedMessages;
import dev.onelsey.bedrockpassport.data.IdentityRepository;
import dev.onelsey.bedrockpassport.gate.IdentityGate;
import dev.onelsey.bedrockpassport.gate.OnlineIdentityGate;
import dev.onelsey.bedrockpassport.identity.IdentityProviderType;
import dev.onelsey.bedrockpassport.identity.LocalIdentityProvider;
import dev.onelsey.bedrockpassport.integration.FloodgateIdentityBridge;
import dev.onelsey.bedrockpassport.integration.FloodgateSkinPolicy;
import dev.onelsey.bedrockpassport.integration.FloodgateOnlineIsolation;
import dev.onelsey.bedrockpassport.integration.GeyserOnlineAuthBridge;
import dev.onelsey.bedrockpassport.integration.GeyserOnlineSessionBridge;
import dev.onelsey.bedrockpassport.integration.GeyserPendingSessionBridge;
import dev.onelsey.bedrockpassport.integration.GeyserSessionLifecycleBridge;
import dev.onelsey.bedrockpassport.integration.ServerLoginReadTimeoutGuard;
import dev.onelsey.bedrockpassport.integration.UntrustedFloodgateIdentityBridge;
import dev.onelsey.bedrockpassport.name.NamePolicy;
import dev.onelsey.bedrockpassport.scheduler.PlatformTasks;
import dev.onelsey.bedrockpassport.security.CredentialVault;
import dev.onelsey.bedrockpassport.security.NameCollisionPolicy;
import dev.onelsey.bedrockpassport.security.SessionGuard;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class BedrockPassportPlugin extends JavaPlugin {
    private IdentityRepository repository;
    private IdentityGate gate;
    private FloodgateIdentityBridge floodgateBridge;
    private FloodgateSkinPolicy skinPolicy;
    private UntrustedFloodgateIdentityBridge untrustedIdentityBridge;
    private GeyserSessionLifecycleBridge geyserLifecycleBridge;
    private SessionGuard sessionGuard;
    private OnlineIdentityGate onlineGate;
    private FloodgateOnlineIsolation floodgateOnlineIsolation;
    private GeyserOnlineAuthBridge onlineAuthBridge;
    private GeyserOnlineSessionBridge onlineSessionBridge;
    private CredentialVault credentialVault;
    private LocalizedMessages localizedMessages;
    private BedrockAccessPolicy accessPolicy;
    private boolean onlineRuntime;
    private IdentityProviderType identityProviderType;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigMigrator.MigrationResult migration = ConfigMigrator.migrate(this);
        localizedMessages = new LocalizedMessages(this);
        localizedMessages.reload();
        if (migration.changed()) {
            getLogger().info(localizedMessages.text("console.config-migrated", Map.of(
                    "from", migration.previousVersion(),
                    "to", migration.currentVersion()
            )));
        }
        getDataFolder().mkdirs();

        PluginCommand command = getCommand("bedrockpassport");
        if (command == null) {
            failEnable("BedrockPassport command registration is missing from plugin.yml.", null);
            return;
        }
        BedrockPassportAdminCommand adminCommand = new BedrockPassportAdminCommand(this);
        command.setExecutor(adminCommand);
        command.setTabCompleter(adminCommand);

        getLogger().info(localizedMessages.text("console.threading", Map.of(
                "model", PlatformTasks.isFolia() ? "Folia regionized scheduler" : "Paper-compatible scheduler"
        )));

        try {
            startRuntime();
        } catch (Throwable exception) {
            failEnable("BedrockPassport could not initialize its identity runtime.", exception);
        }
    }

    private void startRuntime() throws Exception {
        if (getServer().getOnlineMode()) {
            startOnlineRuntime();
            return;
        }

        Plugin geyserPlugin = getServer().getPluginManager().getPlugin("Geyser-Spigot");
        if (geyserPlugin == null || !geyserPlugin.isEnabled()) {
            throw new IllegalStateException("Geyser-Spigot is required and must be enabled before BedrockPassport");
        }
        Plugin floodgatePlugin = getServer().getPluginManager().getPlugin("floodgate");
        if (floodgatePlugin == null || !floodgatePlugin.isEnabled()) {
            throw new IllegalStateException("Floodgate is required for the LOCAL BedrockPassport runtime on online-mode=false");
        }

        IdentityRepository nextRepository = null;
        BedrockAccessPolicy nextAccessPolicy = null;
        SessionGuard nextSessionGuard = null;
        IdentityGate nextGate = null;
        FloodgateIdentityBridge nextFloodgateBridge = null;
        FloodgateSkinPolicy nextSkinPolicy = null;
        UntrustedFloodgateIdentityBridge nextUntrustedIdentityBridge = null;
        GeyserSessionLifecycleBridge nextGeyserLifecycleBridge = null;

        try {
            nextAccessPolicy = BedrockAccessPolicy.fromConfig(getConfig());
            boolean caseInsensitiveNames = getConfig().getBoolean("security.case-insensitive-bedrock-names", true);
            boolean firstSessionWins = getConfig().getBoolean("security.first-session-wins", true);
            long inactivityTimeoutSeconds = getConfig().getLong("identity.inactivity-timeout-seconds", 60L);
            long pendingReservationSeconds = getConfig().getLong("security.pending-reservation-seconds", 45L);
            boolean suspendGeyserDownstreamReadTimeout = getConfig().getBoolean(
                    "compatibility.suspend-geyser-downstream-read-timeout",
                    true
            );
            boolean suspendServerLoginReadTimeout = getConfig().getBoolean(
                    "compatibility.suspend-server-login-read-timeout",
                    true
            );

            NameCollisionPolicy nameCollisionPolicy = new NameCollisionPolicy(caseInsensitiveNames);
            nextRepository = new IdentityRepository(getDataFolder().toPath().resolve("passport.db"), nameCollisionPolicy);
            GeyserPendingSessionBridge geyserBridge = new GeyserPendingSessionBridge(geyserPlugin, suspendGeyserDownstreamReadTimeout);
            NamePolicy namePolicy = new NamePolicy(
                    getConfig().getInt("identity.min-name-length", 3),
                    getConfig().getInt("identity.max-name-length", 16),
                    getConfig().getString("identity.name-pattern", "^[A-Za-z0-9_]+$")
            );
            LocalIdentityProvider identityProvider = new LocalIdentityProvider();
            nextUntrustedIdentityBridge = new UntrustedFloodgateIdentityBridge(this, getLogger(), localizedMessages);
            nextSessionGuard = new SessionGuard(
                    this,
                    firstSessionWins,
                    nameCollisionPolicy,
                    localizedMessages,
                    pendingReservationSeconds
            );
            boolean skinsRestorerPresent = getServer().getPluginManager().isPluginEnabled("SkinsRestorer");
            nextSkinPolicy = new FloodgateSkinPolicy(
                    this,
                    getConfig().getString("skins.policy", "preserve"),
                    skinsRestorerPresent,
                    getLogger()
            );
            nextSkinPolicy.register();
            nextGate = new IdentityGate(
                    nextRepository,
                    geyserBridge,
                    namePolicy,
                    identityProvider,
                    nextSessionGuard,
                    localizedMessages,
                    getConfig().getInt("identity.max-accounts-per-xuid", 3),
                    inactivityTimeoutSeconds,
                    getConfig().getLong("compatibility.form-transition-delay-millis", 250L)
            );
            nextFloodgateBridge = new FloodgateIdentityBridge(
                    nextGate,
                    nextAccessPolicy,
                    localizedMessages,
                    getLogger(),
                    new ServerLoginReadTimeoutGuard(suspendServerLoginReadTimeout),
                    nextSkinPolicy,
                    nextUntrustedIdentityBridge
            );
            nextFloodgateBridge.register();
            nextGeyserLifecycleBridge = new GeyserSessionLifecycleBridge(
                    this,
                    nextGate,
                    nextSessionGuard,
                    nextSkinPolicy,
                    nextUntrustedIdentityBridge
            );
            nextGeyserLifecycleBridge.register();

            repository = nextRepository;
            accessPolicy = nextAccessPolicy;
            sessionGuard = nextSessionGuard;
            gate = nextGate;
            floodgateBridge = nextFloodgateBridge;
            skinPolicy = nextSkinPolicy;
            untrustedIdentityBridge = nextUntrustedIdentityBridge;
            geyserLifecycleBridge = nextGeyserLifecycleBridge;
            onlineRuntime = false;
            identityProviderType = identityProvider.type();

            getLogger().info(localizedMessages.text("console.local-enabled"));
            getLogger().info(localizedMessages.text("console.identity-provider", Map.of("provider", identityProvider.type().storageKey())));
            getLogger().info(localizedMessages.text("console.uuid-mode", Map.of("mode", identityProvider.uuidMode())));
            getLogger().info(localizedMessages.text("console.collision-mode", Map.of("mode", nameCollisionPolicy.mode())));
            getLogger().info(localizedMessages.text("console.first-session-wins", Map.of("value", firstSessionWins)));
            getLogger().info(localizedMessages.text("console.floodgate-trust"));
            getLogger().info(localizedMessages.text("console.skin-policy", Map.of("policy", nextSkinPolicy.policyName(), "detected", nextSkinPolicy.skinsRestorerPresent())));
            getLogger().info(localizedMessages.text("console.saved-shortcuts"));
            getLogger().info(localizedMessages.text("console.local-auth-layer"));
            logAccessPolicy(nextAccessPolicy);
        } catch (Throwable exception) {
            if (nextGeyserLifecycleBridge != null) {
                nextGeyserLifecycleBridge.close();
            }
            if (nextFloodgateBridge != null) {
                nextFloodgateBridge.close();
            }
            if (nextGate != null) {
                nextGate.close();
            }
            if (nextSkinPolicy != null) {
                nextSkinPolicy.close();
            }
            if (nextUntrustedIdentityBridge != null) {
                nextUntrustedIdentityBridge.close();
            }
            if (nextSessionGuard != null) {
                nextSessionGuard.close();
            }
            if (nextRepository != null) {
                nextRepository.close();
            }
            throw exception;
        }
    }

    private void startOnlineRuntime() throws Exception {
        Plugin geyserPlugin = getServer().getPluginManager().getPlugin("Geyser-Spigot");
        if (geyserPlugin == null || !geyserPlugin.isEnabled()) {
            throw new IllegalStateException("Geyser-Spigot is required and must be enabled before BedrockPassport");
        }

        IdentityRepository nextRepository = null;
        CredentialVault nextCredentialVault = null;
        BedrockAccessPolicy nextAccessPolicy = null;
        FloodgateOnlineIsolation nextFloodgateOnlineIsolation = null;
        GeyserOnlineAuthBridge nextOnlineAuthBridge = null;
        OnlineIdentityGate nextOnlineGate = null;
        GeyserOnlineSessionBridge nextOnlineSessionBridge = null;

        try {
            nextAccessPolicy = BedrockAccessPolicy.fromConfig(getConfig());
            boolean caseInsensitiveNames = getConfig().getBoolean("security.case-insensitive-bedrock-names", true);
            long inactivityTimeoutSeconds = getConfig().getLong("identity.inactivity-timeout-seconds", 60L);
            long holdingWorldInitTimeoutSeconds = getConfig().getLong("compatibility.holding-world-init-timeout-seconds", 10L);

            NameCollisionPolicy nameCollisionPolicy = new NameCollisionPolicy(caseInsensitiveNames);
            nextRepository = new IdentityRepository(getDataFolder().toPath().resolve("passport.db"), nameCollisionPolicy);
            nextCredentialVault = new CredentialVault(getDataFolder().toPath().resolve("credentials.key"));

            Plugin floodgatePlugin = getServer().getPluginManager().getPlugin("floodgate");
            nextFloodgateOnlineIsolation = new FloodgateOnlineIsolation(floodgatePlugin, getLogger());

            GeyserPendingSessionBridge pendingBridge = new GeyserPendingSessionBridge(geyserPlugin, false);
            nextOnlineAuthBridge = new GeyserOnlineAuthBridge(geyserPlugin, getLogger());
            nextOnlineGate = new OnlineIdentityGate(
                    nextRepository,
                    nextCredentialVault,
                    pendingBridge,
                    nextOnlineAuthBridge,
                    getLogger(),
                    localizedMessages,
                    getConfig().getInt("identity.max-accounts-per-xuid", 3),
                    inactivityTimeoutSeconds
            );
            nextOnlineSessionBridge = new GeyserOnlineSessionBridge(
                    this,
                    pendingBridge,
                    nextOnlineAuthBridge,
                    nextOnlineGate,
                    nextAccessPolicy,
                    localizedMessages,
                    getLogger(),
                    holdingWorldInitTimeoutSeconds
            );
            nextOnlineSessionBridge.register();

            repository = nextRepository;
            credentialVault = nextCredentialVault;
            accessPolicy = nextAccessPolicy;
            floodgateOnlineIsolation = nextFloodgateOnlineIsolation;
            onlineAuthBridge = nextOnlineAuthBridge;
            onlineGate = nextOnlineGate;
            onlineSessionBridge = nextOnlineSessionBridge;
            onlineRuntime = true;
            identityProviderType = IdentityProviderType.JAVA_ACCOUNT;

            getLogger().info(localizedMessages.text("console.online-enabled"));
            getLogger().info(localizedMessages.text("console.identity-provider", Map.of("provider", IdentityProviderType.JAVA_ACCOUNT.storageKey())));
            getLogger().info(localizedMessages.text("console.enforce-online"));
            if (nextFloodgateOnlineIsolation.isolated()) {
                getLogger().info(localizedMessages.text("console.floodgate-isolated"));
            }
            getLogger().info(localizedMessages.text("console.credential-storage"));
            logAccessPolicy(nextAccessPolicy);
        } catch (Throwable exception) {
            if (nextOnlineSessionBridge != null) {
                nextOnlineSessionBridge.close();
            }
            if (nextOnlineGate != null) {
                nextOnlineGate.close();
            }
            if (nextOnlineAuthBridge != null) {
                nextOnlineAuthBridge.close();
            }
            if (nextFloodgateOnlineIsolation != null) {
                nextFloodgateOnlineIsolation.close();
            }
            if (nextRepository != null) {
                nextRepository.close();
            }
            throw exception;
        }
    }

    public synchronized ReloadResult reloadPassport() {
        int selectors = activeSelectionCount();
        int pendingAdmissions = pendingAdmissionCount();
        if (selectors > 0 || pendingAdmissions > 0) {
            return new ReloadResult(false, localizedMessages.text("runtime.reload-refused", Map.of(
                    "selectors", selectors,
                    "pending", pendingAdmissions
            )));
        }

        try {
            ConfigMigrator.MigrationResult migration = ConfigMigrator.migrate(this);
            localizedMessages.reload();
            stopRuntime();
            startRuntime();
            String suffix = migration.changed()
                    ? localizedMessages.text("runtime.reload-migration-suffix", Map.of(
                            "from", migration.previousVersion(),
                            "to", migration.currentVersion()
                    ))
                    : "";
            return new ReloadResult(true, localizedMessages.text("runtime.reload-success", Map.of("suffix", suffix)));
        } catch (Throwable exception) {
            String error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            getLogger().severe(localizedMessages.text("console.reload-failed", Map.of("error", error)));
            return new ReloadResult(false, localizedMessages.text("runtime.reload-failed"));
        }
    }

    @Override
    public void onDisable() {
        stopRuntime();
    }

    private synchronized void stopRuntime() {
        FloodgateIdentityBridge oldBridge = floodgateBridge;
        FloodgateSkinPolicy oldSkinPolicy = skinPolicy;
        UntrustedFloodgateIdentityBridge oldUntrustedIdentityBridge = untrustedIdentityBridge;
        GeyserSessionLifecycleBridge oldGeyserLifecycleBridge = geyserLifecycleBridge;
        IdentityGate oldGate = gate;
        SessionGuard oldGuard = sessionGuard;
        OnlineIdentityGate oldOnlineGate = onlineGate;
        FloodgateOnlineIsolation oldFloodgateOnlineIsolation = floodgateOnlineIsolation;
        GeyserOnlineAuthBridge oldOnlineAuthBridge = onlineAuthBridge;
        GeyserOnlineSessionBridge oldOnlineSessionBridge = onlineSessionBridge;
        IdentityRepository oldRepository = repository;

        floodgateBridge = null;
        skinPolicy = null;
        untrustedIdentityBridge = null;
        geyserLifecycleBridge = null;
        gate = null;
        sessionGuard = null;
        onlineGate = null;
        floodgateOnlineIsolation = null;
        onlineAuthBridge = null;
        onlineSessionBridge = null;
        credentialVault = null;
        accessPolicy = null;
        repository = null;
        onlineRuntime = false;
        identityProviderType = null;

        if (oldOnlineSessionBridge != null) {
            oldOnlineSessionBridge.close();
        }
        if (oldOnlineGate != null) {
            oldOnlineGate.close();
        }
        if (oldOnlineAuthBridge != null) {
            oldOnlineAuthBridge.close();
        }
        if (oldFloodgateOnlineIsolation != null) {
            oldFloodgateOnlineIsolation.close();
        }
        if (oldGeyserLifecycleBridge != null) {
            oldGeyserLifecycleBridge.close();
        }
        if (oldBridge != null) {
            oldBridge.close();
        }
        if (oldGate != null) {
            oldGate.close();
        }
        if (oldSkinPolicy != null) {
            oldSkinPolicy.close();
        }
        if (oldUntrustedIdentityBridge != null) {
            oldUntrustedIdentityBridge.close();
        }
        if (oldGuard != null) {
            oldGuard.close();
        }
        if (oldRepository != null) {
            oldRepository.close();
        }
    }

    public boolean runtimeReady() {
        if (onlineRuntime) {
            return repository != null && credentialVault != null && onlineGate != null
                    && onlineAuthBridge != null && onlineSessionBridge != null
                    && identityProviderType == IdentityProviderType.JAVA_ACCOUNT;
        }
        return repository != null && gate != null && floodgateBridge != null && skinPolicy != null
                && untrustedIdentityBridge != null && geyserLifecycleBridge != null && sessionGuard != null
                && identityProviderType == IdentityProviderType.LOCAL;
    }

    public int activeSelectionCount() {
        OnlineIdentityGate currentOnline = onlineGate;
        if (currentOnline != null) {
            return currentOnline.activeSelectionCount();
        }
        IdentityGate current = gate;
        return current == null ? 0 : current.activeSelectionCount();
    }

    public int trackedSessionCount() {
        GeyserOnlineAuthBridge currentOnline = onlineAuthBridge;
        if (currentOnline != null) {
            return currentOnline.heldSessionCount();
        }
        SessionGuard current = sessionGuard;
        return current == null ? 0 : current.trackedSessionCount();
    }

    public int pendingAdmissionCount() {
        SessionGuard current = sessionGuard;
        return current == null ? 0 : current.pendingAdmissionCount();
    }

    public String identityProviderName() {
        IdentityProviderType current = identityProviderType;
        return current == null ? "inactive" : current.storageKey();
    }

    public String identityTrustName() {
        return onlineRuntime ? "verified Java account" : "untrusted handoff";
    }

    public String threadingModelName() {
        return PlatformTasks.isFolia() ? "Folia regionized" : "Paper-compatible";
    }

    public String skinPolicyName() {
        if (onlineRuntime) {
            return "Geyser online profile";
        }
        FloodgateSkinPolicy current = skinPolicy;
        return current == null ? "inactive" : current.policyName();
    }

    public boolean skinsRestorerPresent() {
        FloodgateSkinPolicy current = skinPolicy;
        return current != null && current.skinsRestorerPresent();
    }

    public boolean allowlistEnabled() {
        BedrockAccessPolicy current = accessPolicy;
        return current != null && current.enabled();
    }

    public int allowlistPlayerCount() {
        BedrockAccessPolicy current = accessPolicy;
        return current == null ? 0 : current.playerCount();
    }

    public int allowlistXuidCount() {
        BedrockAccessPolicy current = accessPolicy;
        return current == null ? 0 : current.xuidCount();
    }

    public String configLocaleName() {
        return getConfig().getString("language.config", "en_US");
    }

    public String messagesLocaleName() {
        LocalizedMessages current = localizedMessages;
        return current == null ? getConfig().getString("language.messages", "en_US") : current.locale();
    }

    public boolean playerLocaleEnabled() {
        LocalizedMessages current = localizedMessages;
        return current != null && current.usePlayerLocale();
    }

    public String playerLocaleFallbackName() {
        LocalizedMessages current = localizedMessages;
        return current == null
                ? getConfig().getString("language.player-locale-fallback", "en_US")
                : current.playerLocaleFallback();
    }

    public String message(String key) {
        return localizedMessages.text(key);
    }

    public String message(String key, Map<String, ?> placeholders) {
        return localizedMessages.text(key, placeholders);
    }

    private void logAccessPolicy(BedrockAccessPolicy policy) {
        if (policy == null) {
            return;
        }
        getLogger().info(localizedMessages.text("console.access-policy", Map.of(
                "state", policy.enabled() ? localizedMessages.text("admin.status.enabled") : localizedMessages.text("admin.status.disabled"),
                "players", policy.playerCount(),
                "xuids", policy.xuidCount()
        )));
    }

    public SessionGuard.SessionSnapshot sessionSnapshot(String javaName) {
        SessionGuard current = sessionGuard;
        return current == null ? null : current.findByJavaName(javaName);
    }

    public CompletableFuture<LoginResetResult> resetSavedJavaLogin(String javaName) {
        String target = javaName == null ? "" : javaName.trim();
        if (target.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new LoginResetResult(false, false, localizedMessages.text("runtime.java-name-required"))
            );
        }

        IdentityRepository currentRepository = repository;
        if (currentRepository == null) {
            return CompletableFuture.completedFuture(
                    new LoginResetResult(false, false, localizedMessages.text("runtime.inactive"))
            );
        }

        OnlineIdentityGate currentGate = onlineGate;
        if (currentGate != null && !currentGate.beginCredentialMaintenance()) {
            return CompletableFuture.completedFuture(
                    new LoginResetResult(false, false,
                            localizedMessages.text("runtime.reset-busy"))
            );
        }

        CompletableFuture<LoginResetResult> future = currentRepository.deleteCredentialByJavaName(target)
                .handle((count, error) -> {
                    if (error != null) {
                        Throwable cause = unwrapCompletion(error);
                        getLogger().severe(localizedMessages.text("console.reset-one-failed", Map.of(
                                "name", target,
                                "error", cause.getClass().getSimpleName() + ": " + cause.getMessage()
                        )));
                        return new LoginResetResult(false, false, localizedMessages.text("runtime.reset-one-failed"));
                    }
                    if (count == 0) {
                        return new LoginResetResult(true, false,
                                localizedMessages.text("runtime.reset-one-not-found", Map.of("name", target)));
                    }
                    return new LoginResetResult(true, true,
                            localizedMessages.text("runtime.reset-one-success", Map.of("count", count, "name", target)));
                });

        if (currentGate != null) {
            future = future.whenComplete((result, error) -> currentGate.endCredentialMaintenance());
        }
        return future;
    }

    public CompletableFuture<LoginResetResult> resetAllSavedJavaLogins() {
        IdentityRepository currentRepository = repository;
        if (currentRepository == null) {
            return CompletableFuture.completedFuture(
                    new LoginResetResult(false, false, localizedMessages.text("runtime.inactive"))
            );
        }

        OnlineIdentityGate currentGate = onlineGate;
        if (currentGate != null && !currentGate.beginCredentialMaintenance()) {
            return CompletableFuture.completedFuture(
                    new LoginResetResult(false, false,
                            localizedMessages.text("runtime.reset-global-busy"))
            );
        }

        CompletableFuture<LoginResetResult> future = currentRepository
                .deleteAllCredentials(IdentityProviderType.JAVA_ACCOUNT)
                .thenApply(count -> {
                    try {
                        rotateCredentialKey();
                    } catch (IOException exception) {
                        getLogger().severe(localizedMessages.text("console.reset-global-partial", Map.of(
                                "count", count,
                                "error", exception.getClass().getSimpleName() + ": " + exception.getMessage()
                        )));
                        return new LoginResetResult(false, true,
                                localizedMessages.text("runtime.reset-global-partial", Map.of("count", count)));
                    }
                    getLogger().warning(localizedMessages.text("console.reset-global-success"));
                    return new LoginResetResult(true, true,
                            localizedMessages.text("runtime.reset-global-success", Map.of("count", count)));
                })
                .exceptionally(error -> {
                    Throwable cause = unwrapCompletion(error);
                    getLogger().severe(localizedMessages.text("console.reset-global-failed", Map.of(
                            "error", cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    )));
                    return new LoginResetResult(false, false, localizedMessages.text("runtime.reset-global-failed"));
                });

        if (currentGate != null) {
            future = future.whenComplete((result, error) -> currentGate.endCredentialMaintenance());
        }
        return future;
    }

    private void rotateCredentialKey() throws IOException {
        CredentialVault currentVault = credentialVault;
        if (currentVault != null) {
            currentVault.rotate();
            return;
        }
        CredentialVault.rotateKeyFile(getDataFolder().toPath().resolve("credentials.key"));
    }

    private static Throwable unwrapCompletion(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void failEnable(String message, Throwable exception) {
        getLogger().severe(message);
        if (exception != null) {
            getLogger().severe(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        getServer().getPluginManager().disablePlugin(this);
    }

    public record ReloadResult(boolean success, String message) {
    }

    public record LoginResetResult(boolean success, boolean changed, String message) {
    }
}
