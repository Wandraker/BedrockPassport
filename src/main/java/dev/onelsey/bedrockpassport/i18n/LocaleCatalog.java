package dev.onelsey.bedrockpassport.i18n;

import java.util.List;
import java.util.Locale;

public final class LocaleCatalog {
    public static final List<String> SUPPORTED_LOCALES = List.of("en_US", "ru_RU");

    private LocaleCatalog() {
    }

    public static String canonicalize(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String normalized = input.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        for (String supported : SUPPORTED_LOCALES) {
            if (supported.toLowerCase(Locale.ROOT).equals(normalized)) {
                return supported;
            }
        }
        if (!normalized.contains("_")) {
            String prefix = normalized + "_";
            String match = null;
            for (String supported : SUPPORTED_LOCALES) {
                if (supported.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    if (match != null) {
                        return null;
                    }
                    match = supported;
                }
            }
            return match;
        }
        return null;
    }
}
