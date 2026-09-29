package dev.onelsey.bedrockpassport.identity;

import java.util.Locale;

public enum IdentityProviderType {
    LOCAL("local"),
    JAVA_ACCOUNT("java_account");

    private final String storageKey;

    IdentityProviderType(String storageKey) {
        this.storageKey = storageKey;
    }

    public String storageKey() {
        return storageKey;
    }

    public static IdentityProviderType fromStorage(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (IdentityProviderType type : values()) {
            if (type.storageKey.equals(normalized) || type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown identity provider type: " + value);
    }
}
