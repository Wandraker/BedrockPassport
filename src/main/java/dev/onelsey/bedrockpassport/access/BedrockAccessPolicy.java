package dev.onelsey.bedrockpassport.access;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class BedrockAccessPolicy {
    private final boolean allowlistEnabled;
    private final Set<String> playerNames;
    private final Set<String> xuids;

    private BedrockAccessPolicy(boolean allowlistEnabled, Set<String> playerNames, Set<String> xuids) {
        this.allowlistEnabled = allowlistEnabled;
        this.playerNames = Set.copyOf(playerNames);
        this.xuids = Set.copyOf(xuids);
    }

    public static BedrockAccessPolicy fromConfig(FileConfiguration config) {
        Set<String> players = new HashSet<>();
        for (String value : config.getStringList("access.allowlist.players")) {
            if (value != null && !value.isBlank()) {
                players.add(normalizeName(value));
            }
        }

        Set<String> xuids = new HashSet<>();
        for (String value : config.getStringList("access.allowlist.xuids")) {
            if (value != null && !value.isBlank()) {
                xuids.add(value.trim());
            }
        }

        return new BedrockAccessPolicy(
                config.getBoolean("access.allowlist.enabled", false),
                players,
                xuids
        );
    }

    public boolean allows(String bedrockUsername, String xuid) {
        if (!allowlistEnabled) {
            return true;
        }
        if (xuid != null && xuids.contains(xuid.trim())) {
            return true;
        }
        return bedrockUsername != null && playerNames.contains(normalizeName(bedrockUsername));
    }

    public boolean enabled() {
        return allowlistEnabled;
    }

    public int playerCount() {
        return playerNames.size();
    }

    public int xuidCount() {
        return xuids.size();
    }

    private static String normalizeName(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
