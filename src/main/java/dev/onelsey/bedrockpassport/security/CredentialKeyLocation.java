package dev.onelsey.bedrockpassport.security;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public record CredentialKeyLocation(Source source, Path file, String inlineKey) {
    public static final String ENVIRONMENT_VARIABLE = "BEDROCKPASSPORT_KEY";

    public enum Source {
        ENVIRONMENT,
        CONFIG_PATH,
        DEFAULT_FILE
    }

    public static CredentialKeyLocation resolve(String environmentValue, String configuredPath, Path defaultFile) {
        if (environmentValue != null && !environmentValue.isBlank()) {
            return new CredentialKeyLocation(Source.ENVIRONMENT, null, environmentValue.trim());
        }
        if (configuredPath != null && !configuredPath.isBlank()) {
            try {
                return new CredentialKeyLocation(Source.CONFIG_PATH, Path.of(configuredPath.trim()).toAbsolutePath(), null);
            } catch (InvalidPathException exception) {
                throw new IllegalArgumentException("security.credentials-key-path is not a valid path: " + configuredPath, exception);
            }
        }
        return new CredentialKeyLocation(Source.DEFAULT_FILE, defaultFile, null);
    }

    public boolean external() {
        return source != Source.DEFAULT_FILE;
    }

    public String describe() {
        return switch (source) {
            case ENVIRONMENT -> "env (" + ENVIRONMENT_VARIABLE + ")";
            case CONFIG_PATH -> "config path (" + file + ")";
            case DEFAULT_FILE -> "default (" + file + ")";
        };
    }
}
