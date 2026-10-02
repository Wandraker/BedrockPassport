package dev.onelsey.bedrockpassport.gate;

import dev.onelsey.bedrockpassport.i18n.LocalizedMessages;

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
    public static GateMessages localized(LocalizedMessages messages, String locale) {
        return new GateMessages(
                messages.text(locale, "form.title"),
                messages.text(locale, "form.text"),
                messages.text(locale, "form.input-label"),
                messages.text(locale, "form.input-placeholder"),
                messages.text(locale, "form.invalid-name"),
                messages.text(locale, "form.name-taken"),
                messages.text(locale, "form.limit-reached"),
                messages.text(locale, "form.account-in-use"),
                messages.text(locale, "form.passport-in-use"),
                messages.text(locale, "form.internal-error"),
                messages.text(locale, "form.timeout"),
                messages.text(locale, "selector.title"),
                messages.text(locale, "selector.text"),
                messages.text(locale, "selector.last-used-suffix"),
                messages.text(locale, "selector.add-account"),
                messages.text(locale, "selector.manage-accounts"),
                messages.text(locale, "manage.title"),
                messages.text(locale, "manage.text"),
                messages.text(locale, "manage.remove-prefix"),
                messages.text(locale, "manage.back"),
                messages.text(locale, "manage.confirm-title"),
                messages.text(locale, "manage.confirm-text"),
                messages.text(locale, "manage.confirm-button"),
                messages.text(locale, "manage.cancel-button")
        );
    }
}
