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
    private volatile Map<String, String> messages = Map.of();

    public LocalizedMessages(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public synchronized void reload() {
        FileConfiguration config = plugin.getConfig();
        String configured = config.getString("language.messages", "en_US");
        String canonical = LocaleCatalog.canonicalize(configured);
        if (canonical == null) {
            throw new IllegalArgumentException("Unsupported message locale: " + configured
                    + ". Supported: " + String.join(", ", LocaleCatalog.SUPPORTED_LOCALES));
        }

        Map<String, String> merged = new LinkedHashMap<>();
        merged.putAll(readMessages(loadResource("locales/messages-defaults/en_US.yml")));
        if (!"en_US".equals(canonical)) {
            merged.putAll(readMessages(loadResource("locales/messages-defaults/" + canonical + ".yml")));
        }

        ConfigurationSection overrides = config.getConfigurationSection("messages.overrides");
        if (overrides != null) {
            for (String key : overrides.getKeys(true)) {
                if (!overrides.isConfigurationSection(key)) {
                    Object value = overrides.get(key);
                    if (value != null) {
                        merged.put(key, String.valueOf(value));
                    }
                }
            }
        }

        this.locale = canonical;
        this.messages = Map.copyOf(merged);
    }

    public String locale() {
        return locale;
    }

    public String text(String key) {
        return messages.getOrDefault(key, "[missing message: " + key + "]");
    }

    public String text(String key, Map<String, ?> placeholders) {
        String value = text(key);
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            value = value.replace("%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
        }
        return value;
    }

    public String prefixed(String key) {
        return "§bBedrockPassport §8» §f" + text(key);
    }

    public String prefixed(String key, Map<String, ?> placeholders) {
        return "§bBedrockPassport §8» §f" + text(key, placeholders);
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
