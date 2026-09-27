package dev.onelsey.bedrockpassport.data;

public record ClaimResult(Status status, Identity identity) {
    public enum Status {
        CLAIMED,
        EXISTING,
        NAME_TAKEN
    }

    public boolean accepted() {
        return status == Status.CLAIMED || status == Status.EXISTING;
    }
}
