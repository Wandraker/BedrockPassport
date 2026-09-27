package dev.onelsey.bedrockpassport;

import dev.onelsey.bedrockpassport.data.IdentityRepository;
import dev.onelsey.bedrockpassport.gate.GateMessages;
import dev.onelsey.bedrockpassport.gate.IdentityGate;
import dev.onelsey.bedrockpassport.integration.FloodgateIdentityBridge;
import dev.onelsey.bedrockpassport.integration.GeyserPendingSessionBridge;
import dev.onelsey.bedrockpassport.name.NamePolicy;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class BedrockPassportPlugin extends JavaPlugin {
    private IdentityRepository repository;
    private IdentityGate gate;
    private FloodgateIdentityBridge floodgateBridge;

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
            repository = new IdentityRepository(getDataFolder().toPath().resolve("passport.db"));
            GeyserPendingSessionBridge geyserBridge = new GeyserPendingSessionBridge(geyserPlugin);
            NamePolicy namePolicy = new NamePolicy(
                    getConfig().getInt("identity.min-name-length", 3),
                    getConfig().getInt("identity.max-name-length", 16),
                    getConfig().getString("identity.name-pattern", "^[A-Za-z0-9_]+$")
            );
            GateMessages messages = new GateMessages(
                    getConfig().getString("form.title", "BedrockPassport"),
                    getConfig().getString("form.text", "Choose the Java username you want to use on this server."),
                    getConfig().getString("form.input-label", "Java username"),
                    getConfig().getString("form.input-placeholder", "Example: Onelsey"),
                    getConfig().getString("form.invalid-name", "Use 3-16 characters: A-Z, a-z, 0-9 and _."),
                    getConfig().getString("form.name-taken", "That username is already assigned to another Bedrock account."),
                    getConfig().getString("form.internal-error", "BedrockPassport could not save your username. Please reconnect."),
                    getConfig().getString("form.timeout", "BedrockPassport nickname selection timed out. Reconnect and try again.")
            );
            gate = new IdentityGate(
                    repository,
                    geyserBridge,
                    namePolicy,
                    messages,
                    getConfig().getLong("identity.inactivity-timeout-seconds", 60L),
                    getConfig().getLong("compatibility.holding-world-init-timeout-seconds", 10L)
            );
            floodgateBridge = new FloodgateIdentityBridge(repository, gate, messages, getLogger());
            floodgateBridge.register();

            getLogger().info("BedrockPassport enabled. Geyser pending-session bridge capability check passed.");
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
