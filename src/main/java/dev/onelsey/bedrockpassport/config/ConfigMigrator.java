package dev.onelsey.bedrockpassport.config;

import dev.onelsey.bedrockpassport.i18n.LocaleCatalog;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class ConfigMigrator {
    public static final int CURRENT_VERSION = 4;

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

    private static final Map<String, String> MESSAGE_MIGRATION_PATHS = Map.ofEntries(
            Map.entry("security.duplicate-login-message", "security.duplicate-login"),
            Map.entry("form.title", "form.title"),
            Map.entry("form.text", "form.text"),
            Map.entry("form.input-label", "form.input-label"),
            Map.entry("form.input-placeholder", "form.input-placeholder"),
            Map.entry("form.invalid-name", "form.invalid-name"),
            Map.entry("form.name-taken", "form.name-taken"),
            Map.entry("form.limit-reached", "form.limit-reached"),
            Map.entry("form.account-in-use", "form.account-in-use"),
            Map.entry("form.passport-in-use", "form.passport-in-use"),
            Map.entry("form.internal-error", "form.internal-error"),
            Map.entry("form.timeout", "form.timeout"),
            Map.entry("selector.title", "selector.title"),
            Map.entry("selector.text", "selector.text"),
            Map.entry("selector.last-used-suffix", "selector.last-used-suffix"),
            Map.entry("selector.add-account", "selector.add-account"),
            Map.entry("selector.manage-accounts", "selector.manage-accounts"),
            Map.entry("manage.title", "manage.title"),
            Map.entry("manage.text", "manage.text"),
            Map.entry("manage.remove-prefix", "manage.remove-prefix"),
            Map.entry("manage.back", "manage.back"),
            Map.entry("manage.confirm-title", "manage.confirm-title"),
            Map.entry("manage.confirm-text", "manage.confirm-text"),
            Map.entry("manage.confirm-button", "manage.confirm-button"),
            Map.entry("manage.cancel-button", "manage.cancel-button")
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

        YamlConfiguration defaults = loadResource(plugin, "config.yml");
        YamlConfiguration englishMessages = loadResource(plugin, "locales/messages-defaults/en_US.yml");

        if (previousVersion < 3) {
            for (Map.Entry<String, String> entry : MESSAGE_MIGRATION_PATHS.entrySet()) {
                String path = entry.getKey();
                String current = config.getString(path);
                if (current == null) {
                    continue;
                }
                String messageKey = entry.getValue();
                String currentDefault = englishMessages.getString("messages." + messageKey);
                String legacyDefault = V1_UI_DEFAULTS.get(path);
                boolean stock = current.equals(currentDefault) || (legacyDefault != null && current.equals(legacyDefault));
                if (!stock) {
                    config.set("messages.overrides." + messageKey, current);
                }
            }
            config.set("form", null);
            config.set("selector", null);
            config.set("manage", null);
            config.set("security.duplicate-login-message", null);
            changed = true;
        }

        changed |= mergeMissing(config, defaults);

        if (!config.contains("config-version", true) || config.getInt("config-version", 0) != CURRENT_VERSION) {
            config.set("config-version", CURRENT_VERSION);
            changed = true;
        }

        String configLocale = requireConfigLocale(config.getString("language.config", "en_US"));
        requireMessageLocale(config.getString("language.messages", "en_US"), "messages");
        requireMessageLocale(config.getString("language.player-locale-fallback", "en_US"), "player locale fallback");

        clearComments(config);
        applyComments(config, loadResource(plugin, "locales/config-comments/" + configLocale + ".yml"));

        plugin.saveConfig();
        plugin.reloadConfig();
        return new MigrationResult(previousVersion, CURRENT_VERSION, changed);
    }

    private static String requireConfigLocale(String value) {
        String canonical = LocaleCatalog.canonicalizeConfigLocale(value);
        if (canonical == null) {
            throw new IllegalArgumentException("Unsupported configuration locale: " + value
                    + ". Supported: " + String.join(", ", LocaleCatalog.CONFIG_LOCALES));
        }
        return canonical;
    }

    private static String requireMessageLocale(String value, String purpose) {
        String canonical = LocaleCatalog.canonicalizeMessageLocale(value);
        if (canonical == null) {
            throw new IllegalArgumentException("Unsupported " + purpose + " locale: " + value
                    + ". Supported: " + String.join(", ", LocaleCatalog.MESSAGE_LOCALES));
        }
        return canonical;
    }

    private static boolean mergeMissing(FileConfiguration target, YamlConfiguration defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                if (!target.isConfigurationSection(key)) {
                    target.createSection(key);
                    changed = true;
                }
                continue;
            }
            if (!target.contains(key, true)) {
                target.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }

    private static YamlConfiguration loadResource(JavaPlugin plugin, String path) {
        try (InputStream stream = plugin.getResource(path)) {
            if (stream == null) {
                throw new IllegalStateException("Bundled resource is missing: " + path);
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read bundled resource: " + path, exception);
        }
    }

    private static void clearComments(FileConfiguration target) {
        for (String key : target.getKeys(true)) {
            target.setComments(key, List.of());
            target.setInlineComments(key, List.of());
        }
    }

    private static void applyComments(FileConfiguration target, YamlConfiguration comments) {
        for (String key : comments.getKeys(false)) {
            Object value = comments.get(key);
            if (value instanceof List<?> list) {
                target.setComments(key, list.stream().map(String::valueOf).toList());
            } else if (comments.isConfigurationSection(key)) {
                ConfigurationSection section = comments.getConfigurationSection(key);
                if (section != null) {
                    applyCommentsRecursive(target, section, key);
                }
            }
        }
    }

    private static void applyCommentsRecursive(FileConfiguration target, ConfigurationSection section, String prefix) {
        for (String child : section.getKeys(false)) {
            String path = prefix + "." + child;
            Object value = section.get(child);
            if (value instanceof List<?> list) {
                target.setComments(path, list.stream().map(String::valueOf).toList());
            } else if (value instanceof ConfigurationSection nested) {
                applyCommentsRecursive(target, nested, path);
            }
        }
    }

    public record MigrationResult(int previousVersion, int currentVersion, boolean changed) {
    }
}
