package dev.onelsey.bedrockpassport.gate;

public final class GateClosedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GateClosedException() {
        super("Bedrock connection closed during passport selection");
    }
}
