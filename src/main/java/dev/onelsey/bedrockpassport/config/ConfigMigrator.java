package dev.onelsey.bedrockpassport.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class ConfigMigrator {
    public static final int CURRENT_VERSION = 2;

    private static final Map<String, String> V1_UI_DEFAULTS = Map.ofEntries(
            Map.entry("form.title", "BedrockPassport"),
            Map.entry("form.text", "Choose the Java username you want to use on this server. Server authentication will still handle login or registration after you connect."),
            Map.entry("form.input-label", "Java username"),
            Map.entry("selector.title", "BedrockPassport"),
            Map.entry("selector.text", "Choose the server account you want to use."),
            Map.entry("selector.last-used-suffix", "  (last used)"),
            Map.entry("selector.add-account", "+ Add account"),
            Map.entry("selector.manage-accounts", "Manage accounts"),
            Map.entry("manage.title", "BedrockPassport accounts"),
            Map.entry("manage.text", "Removing an account only removes it from this Bedrock Passport. Server data and authentication records are not deleted."),
            Map.entry("manage.remove-prefix", "Remove: "),
            Map.entry("manage.back", "Back"),
            Map.entry("manage.confirm-title", "Remove account"),
            Map.entry("manage.confirm-text", "Remove %account% from this Bedrock Passport? Server data and passwords are not deleted."),
            Map.entry("manage.confirm-button", "Remove from Passport"),
            Map.entry("manage.cancel-button", "Cancel")
    );

    private ConfigMigrator() {
    }

    public static MigrationResult migrate(JavaPlugin plugin) {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        int previousVersion = config.contains("config-version", true) ? config.getInt("config-version", 0) : 0;
        boolean changed = false;

        if (config.contains("compatibility.suspend-backend-read-timeout", true)) {
            boolean legacyValue = config.getBoolean("compatibility.suspend-backend-read-timeout", true);
            if (!config.contains("compatibility.suspend-geyser-downstream-read-timeout", true)) {
                config.set("compatibility.suspend-geyser-downstream-read-timeout", legacyValue);
            }
            if (!config.contains("compatibility.suspend-server-login-read-timeout", true)) {
                config.set("compatibility.suspend-server-login-read-timeout", legacyValue);
            }
            config.set("compatibility.suspend-backend-read-timeout", null);
            changed = true;
        }

        YamlConfiguration defaults;
        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                throw new IllegalStateException("Bundled config.yml is missing");
            }
            defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read bundled BedrockPassport config.yml", exception);
        }

        if (previousVersion < 2) {
            for (Map.Entry<String, String> entry : V1_UI_DEFAULTS.entrySet()) {
                String current = config.getString(entry.getKey());
                if (entry.getValue().equals(current)) {
                    config.set(entry.getKey(), defaults.get(entry.getKey()));
                    changed = true;
                }
            }
        }

        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || config.contains(key, true)) {
                continue;
            }
            config.set(key, defaults.get(key));
            changed = true;
        }

        if (!config.contains("config-version", true) || config.getInt("config-version", 0) != CURRENT_VERSION) {
            config.set("config-version", CURRENT_VERSION);
            changed = true;
        }

        if (changed) {
            plugin.saveConfig();
            plugin.reloadConfig();
        }
        return new MigrationResult(previousVersion, CURRENT_VERSION, changed);
    }

    public record MigrationResult(int previousVersion, int currentVersion, boolean changed) {
    }
}
