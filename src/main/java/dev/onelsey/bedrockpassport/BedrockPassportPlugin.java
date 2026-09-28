package dev.onelsey.bedrockpassport;

import dev.onelsey.bedrockpassport.data.IdentityRepository;
import dev.onelsey.bedrockpassport.gate.GateMessages;
import dev.onelsey.bedrockpassport.gate.IdentityGate;
import dev.onelsey.bedrockpassport.identity.JavaUuidResolver;
import dev.onelsey.bedrockpassport.integration.FloodgateIdentityBridge;
import dev.onelsey.bedrockpassport.integration.ServerLoginReadTimeoutGuard;
import dev.onelsey.bedrockpassport.integration.GeyserPendingSessionBridge;
import dev.onelsey.bedrockpassport.name.NamePolicy;
import dev.onelsey.bedrockpassport.security.NameCollisionPolicy;
import dev.onelsey.bedrockpassport.security.SessionGuard;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class BedrockPassportPlugin extends JavaPlugin {
    private IdentityRepository repository;
    private IdentityGate gate;
    private FloodgateIdentityBridge floodgateBridge;
    private SessionGuard sessionGuard;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();

        Plugin geyserPlugin = getServer().getPluginManager().getPlugin("Geyser-Spigot");
        if (geyserPlugin == null || !geyserPlugin.isEnabled()) {
            failEnable("Geyser-Spigot is required and must be enabled before BedrockPassport.", null);
            return;
        }

        try {
            boolean caseInsensitiveNames = getConfig().getBoolean("security.case-insensitive-bedrock-names", true);
            boolean firstSessionWins = getConfig().getBoolean("security.first-session-wins", true);
            String duplicateLoginMessage = getConfig().getString(
                    "security.duplicate-login-message",
                    "This server account is already online."
            );
            long inactivityTimeoutSeconds = getConfig().getLong("identity.inactivity-timeout-seconds", 60L);
            long pendingReservationSeconds = getConfig().getLong("security.pending-reservation-seconds", 45L);
            boolean legacySuspendReadTimeout = getConfig().getBoolean("compatibility.suspend-backend-read-timeout", true);
            boolean suspendGeyserDownstreamReadTimeout = getConfig().getBoolean(
                    "compatibility.suspend-geyser-downstream-read-timeout",
                    legacySuspendReadTimeout
            );
            boolean suspendServerLoginReadTimeout = getConfig().getBoolean(
                    "compatibility.suspend-server-login-read-timeout",
                    legacySuspendReadTimeout
            );

            NameCollisionPolicy nameCollisionPolicy = new NameCollisionPolicy(caseInsensitiveNames);
            repository = new IdentityRepository(getDataFolder().toPath().resolve("passport.db"), nameCollisionPolicy);
            GeyserPendingSessionBridge geyserBridge = new GeyserPendingSessionBridge(geyserPlugin, suspendGeyserDownstreamReadTimeout);
            NamePolicy namePolicy = new NamePolicy(
                    getConfig().getInt("identity.min-name-length", 3),
                    getConfig().getInt("identity.max-name-length", 16),
                    getConfig().getString("identity.name-pattern", "^[A-Za-z0-9_]+$")
            );
            JavaUuidResolver uuidResolver = new JavaUuidResolver(getServer());
            sessionGuard = new SessionGuard(
                    this,
                    firstSessionWins,
                    nameCollisionPolicy,
                    duplicateLoginMessage,
                    pendingReservationSeconds
            );
            GateMessages messages = new GateMessages(
                    getConfig().getString("form.title", "BedrockPassport"),
                    getConfig().getString("form.text", "Choose the Java username you want to use on this server."),
                    getConfig().getString("form.input-label", "Java username"),
                    getConfig().getString("form.input-placeholder", "Example: Onelsey"),
                    getConfig().getString("form.invalid-name", "Use 3-16 characters: A-Z, a-z, 0-9 and _."),
                    getConfig().getString("form.name-taken", "That username is already assigned to another Bedrock Passport."),
                    getConfig().getString("form.limit-reached", "Your Bedrock Passport has reached its account limit."),
                    getConfig().getString("form.account-in-use", "That server account is already online."),
                    getConfig().getString("form.passport-in-use", "This Bedrock/Xbox account already has a pending Passport session."),
                    getConfig().getString("form.internal-error", "BedrockPassport could not save your account. Please reconnect."),
                    getConfig().getString("form.timeout", "You did not choose an account in time. Reconnect and try again."),
                    getConfig().getString("selector.title", "BedrockPassport"),
                    getConfig().getString("selector.text", "Choose the server account you want to use."),
                    getConfig().getString("selector.last-used-suffix", "  (last used)"),
                    getConfig().getString("selector.add-account", "+ Add account"),
                    getConfig().getString("selector.manage-accounts", "Manage accounts"),
                    getConfig().getString("manage.title", "BedrockPassport accounts"),
                    getConfig().getString("manage.text", "Removing an account only removes it from this Bedrock Passport. Server data and authentication records are not deleted."),
                    getConfig().getString("manage.remove-prefix", "Remove: "),
                    getConfig().getString("manage.back", "Back"),
                    getConfig().getString("manage.confirm-title", "Remove account"),
                    getConfig().getString("manage.confirm-text", "Remove %account% from this Bedrock Passport? Server data and passwords are not deleted."),
                    getConfig().getString("manage.confirm-button", "Remove from Passport"),
                    getConfig().getString("manage.cancel-button", "Cancel")
            );
            gate = new IdentityGate(
                    repository,
                    geyserBridge,
                    namePolicy,
                    uuidResolver,
                    sessionGuard,
                    messages,
                    getConfig().getInt("identity.max-accounts-per-xuid", 3),
                    inactivityTimeoutSeconds,
                    getConfig().getLong("compatibility.holding-world-init-timeout-seconds", 10L),
                    getConfig().getLong("compatibility.form-transition-delay-millis", 250L)
            );
            floodgateBridge = new FloodgateIdentityBridge(
                    gate,
                    messages,
                    getLogger(),
                    new ServerLoginReadTimeoutGuard(suspendServerLoginReadTimeout)
            );
            floodgateBridge.register();

            getLogger().info("BedrockPassport enabled. Geyser pending-session bridge capability check passed.");
            getLogger().info("BedrockPassport account UUID mode: " + uuidResolver.mode() + ".");
            getLogger().info("BedrockPassport name collision mode: " + nameCollisionPolicy.mode() + ".");
            getLogger().info("BedrockPassport first-session-wins: " + firstSessionWins + ".");
            getLogger().info("BedrockPassport handles identity only. Password/login/register remains the responsibility of the server authentication plugin.");
        } catch (Throwable exception) {
            failEnable("BedrockPassport could not initialize its Geyser/Floodgate identity bridge.", exception);
        }
    }

    @Override
    public void onDisable() {
        if (floodgateBridge != null) {
            floodgateBridge.close();
        }
        if (gate != null) {
            gate.close();
        }
        if (sessionGuard != null) {
            sessionGuard.close();
        }
        if (repository != null) {
            try {
                repository.close();
            } catch (Exception exception) {
                getLogger().warning("Could not close passport database cleanly: " + exception.getMessage());
            }
        }
    }

    private void failEnable(String message, Throwable exception) {
        getLogger().severe(message);
        if (exception != null) {
            getLogger().severe(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        getServer().getPluginManager().disablePlugin(this);
    }
}
