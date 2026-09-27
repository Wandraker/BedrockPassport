package dev.onelsey.bedrockpassport.data;

import java.util.UUID;

public record Identity(
        long id,
        String xuid,
        String gameName,
        UUID javaUuid,
        String uuidMode,
        long createdAt,
        long lastUsed
) {
}
