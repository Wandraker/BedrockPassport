package dev.onelsey.bedrockpassport.identity;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class LocalIdentityProvider {
    private static final String UUID_MODE = "offline";

    public IdentityProviderType type() {
        return IdentityProviderType.LOCAL;
    }

    public String uuidMode() {
        return UUID_MODE;
    }

    public CompletableFuture<UUID> resolve(String username) {
        Objects.requireNonNull(username, "username");
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        return CompletableFuture.completedFuture(uuid);
    }
}
