package dev.onelsey.bedrockpassport.gate;

public final class PassportSessionBusyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PassportSessionBusyException(String message) {
        super(message);
    }
}
