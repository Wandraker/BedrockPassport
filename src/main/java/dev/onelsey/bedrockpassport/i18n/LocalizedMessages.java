package dev.onelsey.bedrockpassport.i18n;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class LocalizedMessages {
    private final JavaPlugin plugin;
    private volatile String locale = "en_US";
    private volatile boolean usePlayerLocale;
    private volatile String playerLocaleFallback = "en_US";
    private volatile Map<String, Map<String, String>> messagesByLocale = Map.of();

    public LocalizedMessages(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public synchronized void reload() {
        FileConfiguration config = plugin.getConfig();

        String configured = config.getString("language.messages", "en_US");
        String canonical = LocaleCatalog.canonicalizeMessageLocale(configured);
        if (canonical == null) {
            throw new IllegalArgumentException("Unsupported message locale: " + configured
                    + ". Supported: " + String.join(", ", LocaleCatalog.MESSAGE_LOCALES));
        }

        String configuredFallback = config.getString("language.player-locale-fallback", "en_US");
        String canonicalFallback = LocaleCatalog.canonicalizeMessageLocale(configuredFallback);
        if (canonicalFallback == null) {
            throw new IllegalArgumentException("Unsupported player locale fallback: " + configuredFallback
                    + ". Supported: " + String.join(", ", LocaleCatalog.MESSAGE_LOCALES));
        }

        Map<String, String> english = readMessages(loadResource("locales/messages-defaults/en_US.yml"));
        Map<String, String> overrides = readOverrides(config);
        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();

        for (String supported : LocaleCatalog.MESSAGE_LOCALES) {
            Map<String, String> merged = new LinkedHashMap<>(english);
            if (!"en_US".equals(supported)) {
                merged.putAll(readMessages(loadResource("locales/messages-defaults/" + supported + ".yml")));
            }
            merged.putAll(overrides);
            loaded.put(supported, Map.copyOf(merged));
        }

        this.locale = canonical;
        this.usePlayerLocale = config.getBoolean("language.use-player-locale", true);
        this.playerLocaleFallback = canonicalFallback;
        this.messagesByLocale = Map.copyOf(loaded);
    }

    public String locale() {
        return locale;
    }

    public boolean usePlayerLocale() {
        return usePlayerLocale;
    }

    public String playerLocaleFallback() {
        return playerLocaleFallback;
    }

    public String resolvePlayerLocale(String clientLocale) {
        if (!usePlayerLocale) {
            return locale;
        }
        String matched = LocaleCatalog.matchPlayerLocale(clientLocale);
        return matched == null ? playerLocaleFallback : matched;
    }

    public String text(String key) {
        return text(locale, key);
    }

    public String text(String selectedLocale, String key) {
        Map<String, String> selected = messagesByLocale.get(selectedLocale);
        if (selected == null) {
            selected = messagesByLocale.get(locale);
        }
        if (selected == null) {
            selected = messagesByLocale.get("en_US");
        }
        return selected == null
                ? "[missing message: " + key + "]"
                : selected.getOrDefault(key, "[missing message: " + key + "]");
    }

    public String text(String key, Map<String, ?> placeholders) {
        return text(locale, key, placeholders);
    }

    public String text(String selectedLocale, String key, Map<String, ?> placeholders) {
        String value = text(selectedLocale, key);
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            value = value.replace("%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
        }
        return value;
    }

    public String prefixed(String key) {
        return prefixed(locale, key);
    }

    public String prefixed(String selectedLocale, String key) {
        return "§bBedrockPassport §8» §f" + text(selectedLocale, key);
    }

    public String prefixed(String key, Map<String, ?> placeholders) {
        return prefixed(locale, key, placeholders);
    }

    public String prefixed(String selectedLocale, String key, Map<String, ?> placeholders) {
        return "§bBedrockPassport §8» §f" + text(selectedLocale, key, placeholders);
    }

    private static Map<String, String> readOverrides(FileConfiguration config) {
        Map<String, String> result = new LinkedHashMap<>();
        ConfigurationSection overrides = config.getConfigurationSection("messages.overrides");
        if (overrides == null) {
            return result;
        }
        for (String key : overrides.getKeys(true)) {
            if (!overrides.isConfigurationSection(key)) {
                Object value = overrides.get(key);
                if (value != null) {
                    result.put(key, String.valueOf(value));
                }
            }
        }
        return result;
    }

    private YamlConfiguration loadResource(String path) {
        try (InputStream input = plugin.getResource(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled locale resource: " + path);
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load locale resource: " + path, exception);
        }
    }

    private static Map<String, String> readMessages(YamlConfiguration yaml) {
        Map<String, String> result = new LinkedHashMap<>();
        ConfigurationSection section = yaml.getConfigurationSection("messages");
        if (section == null) {
            return result;
        }
        for (String key : section.getKeys(true)) {
            if (!section.isConfigurationSection(key)) {
                Object value = section.get(key);
                if (value != null) {
                    result.put(key, String.valueOf(value));
                }
            }
        }
        return result;
    }
}
