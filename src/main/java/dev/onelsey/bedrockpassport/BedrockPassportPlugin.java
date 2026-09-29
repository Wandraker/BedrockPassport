package dev.onelsey.bedrockpassport;

import dev.onelsey.bedrockpassport.command.BedrockPassportAdminCommand;
import dev.onelsey.bedrockpassport.config.ConfigMigrator;
import dev.onelsey.bedrockpassport.data.IdentityRepository;
import dev.onelsey.bedrockpassport.gate.GateMessages;
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
import dev.onelsey.bedrockpassport.security.CredentialVault;
import dev.onelsey.bedrockpassport.security.NameCollisionPolicy;
import dev.onelsey.bedrockpassport.security.SessionGuard;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

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
    private boolean onlineRuntime;
    private IdentityProviderType identityProviderType;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigMigrator.MigrationResult migration = ConfigMigrator.migrate(this);
        if (migration.changed()) {
            getLogger().info("Migrated BedrockPassport config schema " + migration.previousVersion() + " -> " + migration.currentVersion() + ".");
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
        SessionGuard nextSessionGuard = null;
        IdentityGate nextGate = null;
        FloodgateIdentityBridge nextFloodgateBridge = null;
        FloodgateSkinPolicy nextSkinPolicy = null;
        UntrustedFloodgateIdentityBridge nextUntrustedIdentityBridge = null;
        GeyserSessionLifecycleBridge nextGeyserLifecycleBridge = null;

        try {
            boolean caseInsensitiveNames = getConfig().getBoolean("security.case-insensitive-bedrock-names", true);
            boolean firstSessionWins = getConfig().getBoolean("security.first-session-wins", true);
            String duplicateLoginMessage = getConfig().getString(
                    "security.duplicate-login-message",
                    "This server account is already online."
            );
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
            nextUntrustedIdentityBridge = new UntrustedFloodgateIdentityBridge(this, getLogger());
            nextSessionGuard = new SessionGuard(
                    this,
                    firstSessionWins,
                    nameCollisionPolicy,
                    duplicateLoginMessage,
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
            GateMessages messages = new GateMessages(
                    getConfig().getString("form.title", "§l§bBedrockPassport"),
                    getConfig().getString("form.text", "§fChoose the Java account you want to use.\n§7Authentication still happens on the server after this step."),
                    getConfig().getString("form.input-label", "§bJava username"),
                    getConfig().getString("form.input-placeholder", "Example: Onelsey"),
                    getConfig().getString("form.invalid-name", "Use 3-16 characters: A-Z, a-z, 0-9 and _."),
                    getConfig().getString("form.name-taken", "That account is already saved in this Passport."),
                    getConfig().getString("form.limit-reached", "Your Bedrock Passport has reached its account limit."),
                    getConfig().getString("form.account-in-use", "That server account is already online."),
                    getConfig().getString("form.passport-in-use", "This Bedrock/Xbox account already has a pending Passport session."),
                    getConfig().getString("form.internal-error", "BedrockPassport could not save your account. Please reconnect."),
                    getConfig().getString("form.timeout", "You did not choose an account in time. Reconnect and try again."),
                    getConfig().getString("selector.title", "§l§bBedrockPassport"),
                    getConfig().getString("selector.text", "§fChoose your server account.\n§7The last used account is marked with §a✓§7."),
                    getConfig().getString("selector.last-used-suffix", " §a✓"),
                    getConfig().getString("selector.add-account", "§a＋ Add account"),
                    getConfig().getString("selector.manage-accounts", "§e⚙ Manage accounts"),
                    getConfig().getString("manage.title", "§l§bPassport accounts"),
                    getConfig().getString("manage.text", "§fManage saved account shortcuts.\n§7Removing one does not delete server data or passwords."),
                    getConfig().getString("manage.remove-prefix", "§c✕ "),
                    getConfig().getString("manage.back", "§b← Back"),
                    getConfig().getString("manage.confirm-title", "§l§cRemove account"),
                    getConfig().getString("manage.confirm-text", "§fRemove %account% from this Bedrock Passport?\n§7Server data and passwords are not deleted."),
                    getConfig().getString("manage.confirm-button", "§cRemove"),
                    getConfig().getString("manage.cancel-button", "§bCancel")
            );
            nextGate = new IdentityGate(
                    nextRepository,
                    geyserBridge,
                    namePolicy,
                    identityProvider,
                    nextSessionGuard,
                    messages,
                    getConfig().getInt("identity.max-accounts-per-xuid", 3),
                    inactivityTimeoutSeconds,
                    getConfig().getLong("compatibility.holding-world-init-timeout-seconds", 10L),
                    getConfig().getLong("compatibility.form-transition-delay-millis", 250L)
            );
            nextFloodgateBridge = new FloodgateIdentityBridge(
                    nextGate,
                    messages,
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
            sessionGuard = nextSessionGuard;
            gate = nextGate;
            floodgateBridge = nextFloodgateBridge;
            skinPolicy = nextSkinPolicy;
            untrustedIdentityBridge = nextUntrustedIdentityBridge;
            geyserLifecycleBridge = nextGeyserLifecycleBridge;
            onlineRuntime = false;
            identityProviderType = identityProvider.type();

            getLogger().info("BedrockPassport enabled. Geyser pending-session bridge capability check passed.");
            getLogger().info("BedrockPassport identity provider: " + identityProvider.type().storageKey() + ".");
            getLogger().info("BedrockPassport account UUID mode: " + identityProvider.uuidMode() + ".");
            getLogger().info("BedrockPassport name collision mode: " + nameCollisionPolicy.mode() + ".");
            getLogger().info("BedrockPassport first-session-wins: " + firstSessionWins + ".");
            getLogger().info("BedrockPassport Floodgate identity trust: untrusted Passport handoff.");
            getLogger().info("BedrockPassport skin policy: " + nextSkinPolicy.policyName() + "; SkinsRestorer detected: " + nextSkinPolicy.skinsRestorerPresent() + ".");
            getLogger().info("BedrockPassport saved identities are convenience shortcuts, not ownership claims.");
            getLogger().info("BedrockPassport handles identity only. Password/login/register remains the responsibility of the server authentication plugin.");
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
        FloodgateOnlineIsolation nextFloodgateOnlineIsolation = null;
        GeyserOnlineAuthBridge nextOnlineAuthBridge = null;
        OnlineIdentityGate nextOnlineGate = null;
        GeyserOnlineSessionBridge nextOnlineSessionBridge = null;

        try {
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
                    getConfig().getInt("identity.max-accounts-per-xuid", 3),
                    inactivityTimeoutSeconds,
                    getConfig().getString("selector.title", "§l§bBedrockPassport"),
                    "§fChoose a verified Java account.\n§7Microsoft/Minecraft authentication is required for new accounts.",
                    getConfig().getString("selector.last-used-suffix", " §a✓"),
                    "§a＋ Add Java account",
                    "§e⚙ Manage Java accounts",
                    "§l§bJava accounts",
                    "§fManage verified Java accounts in this Bedrock Passport.\n§7Removing one only removes the saved sign-in from BedrockPassport.",
                    getConfig().getString("manage.remove-prefix", "§c✕ "),
                    getConfig().getString("manage.back", "§b← Back"),
                    getConfig().getString("manage.confirm-title", "§l§cRemove account"),
                    "§fRemove %account% from this Bedrock Passport?\n§7The Java/Microsoft account itself is not deleted.",
                    getConfig().getString("manage.confirm-button", "§cRemove"),
                    getConfig().getString("manage.cancel-button", "§bCancel")
            );
            nextOnlineSessionBridge = new GeyserOnlineSessionBridge(
                    this,
                    pendingBridge,
                    nextOnlineAuthBridge,
                    nextOnlineGate,
                    getLogger(),
                    holdingWorldInitTimeoutSeconds
            );
            nextOnlineSessionBridge.register();

            repository = nextRepository;
            credentialVault = nextCredentialVault;
            floodgateOnlineIsolation = nextFloodgateOnlineIsolation;
            onlineAuthBridge = nextOnlineAuthBridge;
            onlineGate = nextOnlineGate;
            onlineSessionBridge = nextOnlineSessionBridge;
            onlineRuntime = true;
            identityProviderType = IdentityProviderType.JAVA_ACCOUNT;

            getLogger().info("BedrockPassport enabled in JAVA_ACCOUNT development mode.");
            getLogger().info("BedrockPassport identity provider: " + IdentityProviderType.JAVA_ACCOUNT.storageKey() + ".");
            getLogger().info("BedrockPassport enforces Geyser Java auth-type online while JAVA_ACCOUNT mode is active.");
            if (nextFloodgateOnlineIsolation.isolated()) {
                getLogger().info("BedrockPassport isolated Floodgate packet handling from JAVA_ACCOUNT backend logins.");
            }
            getLogger().info("Verified Java auth chains are stored encrypted in passport.db using plugins/BedrockPassport/credentials.key.");
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
            return new ReloadResult(false,
                    "BedrockPassport reload refused: " + selectors + " selector(s) and " + pendingAdmissions + " pending login(s) are still active.");
        }

        try {
            ConfigMigrator.MigrationResult migration = ConfigMigrator.migrate(this);
            stopRuntime();
            startRuntime();
            String suffix = migration.changed()
                    ? " Config schema migrated " + migration.previousVersion() + " -> " + migration.currentVersion() + "."
                    : "";
            return new ReloadResult(true, "BedrockPassport reloaded successfully." + suffix);
        } catch (Throwable exception) {
            getLogger().severe("BedrockPassport reload failed: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            return new ReloadResult(false, "BedrockPassport reload failed. Check the console; runtime is not active until reload succeeds or the server restarts.");
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

    public SessionGuard.SessionSnapshot sessionSnapshot(String javaName) {
        SessionGuard current = sessionGuard;
        return current == null ? null : current.findByJavaName(javaName);
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
}
