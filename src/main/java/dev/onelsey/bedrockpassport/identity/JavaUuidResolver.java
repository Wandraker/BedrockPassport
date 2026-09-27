package dev.onelsey.bedrockpassport.identity;

import org.bukkit.Server;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class JavaUuidResolver {
    private static final String MODE = "offline";

    public JavaUuidResolver(Server server) {
        Objects.requireNonNull(server, "server");
        if (server.getOnlineMode()) {
            throw new IllegalStateException(
                    "BedrockPassport currently requires online-mode=false. " +
                    "Selecting an arbitrary Java identity on an online-mode server cannot prove ownership of that Java account."
            );
        }
    }

    public String mode() {
        return MODE;
    }

    public CompletableFuture<UUID> resolve(String username) {
        Objects.requireNonNull(username, "username");
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        return CompletableFuture.completedFuture(uuid);
    }
}
