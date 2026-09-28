package dev.onelsey.bedrockpassport.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class ConfigMigrator {
    public static final int CURRENT_VERSION = 1;

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

        try (InputStream stream = plugin.getResource("config.yml")) {
            if (stream == null) {
                throw new IllegalStateException("Bundled config.yml is missing");
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)
            );
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key) || config.contains(key, true)) {
                    continue;
                }
                config.set(key, defaults.get(key));
                changed = true;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not migrate BedrockPassport config.yml", exception);
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
