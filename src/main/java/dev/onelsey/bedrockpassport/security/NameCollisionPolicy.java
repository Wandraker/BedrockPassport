package dev.onelsey.bedrockpassport.security;

import java.util.Locale;
import java.util.Objects;

public final class NameCollisionPolicy {
    private final boolean caseInsensitive;

    public NameCollisionPolicy(boolean caseInsensitive) {
        this.caseInsensitive = caseInsensitive;
    }

    public boolean caseInsensitive() {
        return caseInsensitive;
    }

    public String key(String username) {
        Objects.requireNonNull(username, "username");
        return caseInsensitive ? username.toLowerCase(Locale.ROOT) : username;
    }

    public String mode() {
        return caseInsensitive ? "case-insensitive" : "case-sensitive";
    }
}
