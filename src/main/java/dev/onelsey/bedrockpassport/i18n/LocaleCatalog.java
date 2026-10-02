package dev.onelsey.bedrockpassport.i18n;

import java.util.List;
import java.util.Locale;

public final class LocaleCatalog {
    public static final List<String> CONFIG_LOCALES = List.of("en_US", "ru_RU");
    public static final List<String> MESSAGE_LOCALES = List.of("en_US", "ru_RU", "uk_UA", "de_DE", "es_ES");

    private LocaleCatalog() {
    }

    public static String canonicalizeConfigLocale(String input) {
        return canonicalizeExactOrLanguage(input, CONFIG_LOCALES);
    }

    public static String canonicalizeMessageLocale(String input) {
        return canonicalizeExactOrLanguage(input, MESSAGE_LOCALES);
    }

    public static String matchPlayerLocale(String input) {
        return canonicalizeExactOrLanguage(input, MESSAGE_LOCALES);
    }

    private static String canonicalizeExactOrLanguage(String input, List<String> supportedLocales) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String normalized = input.trim().replace('-', '_').toLowerCase(Locale.ROOT);
        for (String supported : supportedLocales) {
            if (supported.toLowerCase(Locale.ROOT).equals(normalized)) {
                return supported;
            }
        }

        String language = normalized;
        int separator = normalized.indexOf('_');
        if (separator >= 0) {
            language = normalized.substring(0, separator);
        }

        String prefix = language + "_";
        String match = null;
        for (String supported : supportedLocales) {
            if (supported.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                if (match != null) {
                    return null;
                }
                match = supported;
            }
        }
        return match;
    }
}
