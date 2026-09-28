package dev.onelsey.bedrockpassport.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class ChatUi {
    private static final Component PREFIX = Component.text("BedrockPassport", NamedTextColor.AQUA)
            .decorate(TextDecoration.BOLD)
            .append(Component.text(" » ", NamedTextColor.DARK_GRAY));

    private ChatUi() {
    }

    public static Component info(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.WHITE));
    }

    public static Component success(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.GREEN));
    }

    public static Component warning(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.YELLOW));
    }

    public static Component error(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.RED));
    }

    public static Component value(String key, Object value) {
        return Component.text("  " + key + ": ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(value), NamedTextColor.WHITE));
    }

    public static Component goodValue(String key, Object value) {
        return Component.text("  " + key + ": ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(value), NamedTextColor.GREEN));
    }

    public static Component accentValue(String key, Object value) {
        return Component.text("  " + key + ": ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(value), NamedTextColor.AQUA));
    }
}
