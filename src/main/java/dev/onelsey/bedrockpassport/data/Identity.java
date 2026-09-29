package dev.onelsey.bedrockpassport.data;

import dev.onelsey.bedrockpassport.identity.IdentityProviderType;

import java.util.UUID;

public record Identity(
        long id,
        String xuid,
        IdentityProviderType providerType,
        String gameName,
        UUID javaUuid,
        String uuidMode,
        long createdAt,
        long lastUsed
) {
}
