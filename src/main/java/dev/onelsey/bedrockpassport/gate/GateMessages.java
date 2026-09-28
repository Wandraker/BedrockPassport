package dev.onelsey.bedrockpassport.gate;

public record GateMessages(
        String title,
        String text,
        String inputLabel,
        String inputPlaceholder,
        String invalidName,
        String nameTaken,
        String limitReached,
        String accountInUse,
        String passportInUse,
        String internalError,
        String timeout,
        String selectorTitle,
        String selectorText,
        String lastUsedSuffix,
        String addAccount,
        String manageAccounts,
        String manageTitle,
        String manageText,
        String removePrefix,
        String back,
        String removeConfirmTitle,
        String removeConfirmText,
        String removeConfirmButton,
        String cancelButton
) {
}
