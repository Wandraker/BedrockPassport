package dev.onelsey.bedrockpassport.gate;

public final class GateClosedException extends RuntimeException {
    public GateClosedException() {
        super("Bedrock connection closed during passport selection");
    }
}
