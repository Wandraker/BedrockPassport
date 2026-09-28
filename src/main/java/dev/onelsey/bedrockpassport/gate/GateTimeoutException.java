package dev.onelsey.bedrockpassport.gate;

public final class GateTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GateTimeoutException(String message) {
        super(message);
    }
}
