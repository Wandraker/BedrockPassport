package dev.onelsey.bedrockpassport.name;

import java.util.Objects;
import java.util.regex.Pattern;

public final class NamePolicy {
    private final int minLength;
    private final int maxLength;
    private final Pattern pattern;

    public NamePolicy(int minLength, int maxLength, String regex) {
        if (minLength < 1 || maxLength < minLength) {
            throw new IllegalArgumentException("Invalid username length range");
        }
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.pattern = Pattern.compile(Objects.requireNonNull(regex, "regex"));
    }

    public String normalize(String input) {
        return input == null ? "" : input.trim();
    }

    public boolean valid(String name) {
        return name != null
                && name.length() >= minLength
                && name.length() <= maxLength
                && pattern.matcher(name).matches();
    }
}
