package dev.onelsey.bedrockpassport.gate;

public record GateMessages(
        String title,
        String text,
        String inputLabel,
        String inputPlaceholder,
        String invalidName,
        String nameTaken,
        String internalError,
        String timeout
) {
}
