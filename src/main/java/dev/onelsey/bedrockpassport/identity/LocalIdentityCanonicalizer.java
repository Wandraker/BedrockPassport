package dev.onelsey.bedrockpassport.identity;

import dev.onelsey.bedrockpassport.data.Identity;
import dev.onelsey.bedrockpassport.data.IdentityRepository;
import dev.onelsey.bedrockpassport.integration.AuthMeNameResolver;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class LocalIdentityCanonicalizer {
    private final IdentityRepository repository;
    private final LocalIdentityProvider provider;
    private final AuthMeNameResolver authMe;
    private final boolean passportCaseInsensitive;

    public LocalIdentityCanonicalizer(
            IdentityRepository repository,
            LocalIdentityProvider provider,
            AuthMeNameResolver authMe,
            boolean passportCaseInsensitive
    ) {
        this.repository = repository;
        this.provider = provider;
        this.authMe = authMe;
        this.passportCaseInsensitive = passportCaseInsensitive;
    }

    public CompletableFuture<CanonicalLocalIdentity> resolve(String requestedName) {
        Optional<String> authMeCanonical = authMe.registeredCanonicalName(requestedName);
        if (authMeCanonical.isPresent()) {
            String canonicalName = authMeCanonical.get();
            return provider.resolve(canonicalName)
                    .thenApply(uuid -> new CanonicalLocalIdentity(canonicalName, uuid, Source.AUTHME));
        }

        boolean caseInsensitive = passportCaseInsensitive || authMe.available();
        return repository.findCanonicalIdentityByName(
                        IdentityProviderType.LOCAL,
                        requestedName,
                        caseInsensitive
                )
                .thenCompose(existing -> {
                    if (existing.isPresent()) {
                        Identity identity = existing.get();
                        if (identity.javaUuid() != null && provider.uuidMode().equals(identity.uuidMode())) {
                            return CompletableFuture.completedFuture(
                                    new CanonicalLocalIdentity(identity.gameName(), identity.javaUuid(), Source.PASSPORT)
                            );
                        }
                        return provider.resolve(identity.gameName())
                                .thenApply(uuid -> new CanonicalLocalIdentity(identity.gameName(), uuid, Source.PASSPORT));
                    }

                    return provider.resolve(requestedName)
                            .thenApply(uuid -> new CanonicalLocalIdentity(requestedName, uuid, Source.NEW));
                });
    }

    public String uuidMode() {
        return provider.uuidMode();
    }

    public record CanonicalLocalIdentity(
            String gameName,
            UUID javaUuid,
            Source source
    ) {
    }

    public enum Source {
        AUTHME,
        PASSPORT,
        NEW
    }
}
