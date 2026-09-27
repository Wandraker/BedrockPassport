package dev.onelsey.bedrockpassport.gate;

public final class GateTimeoutException extends RuntimeException {
    public GateTimeoutException(String message) {
        super(message);
    }
}
