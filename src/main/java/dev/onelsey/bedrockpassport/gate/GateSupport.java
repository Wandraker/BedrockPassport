package dev.onelsey.bedrockpassport.gate;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

final class GateSupport {
    private GateSupport() {
    }

    static String withError(String content, String error) {
        if (error == null || error.isBlank()) {
            return content;
        }
        return "§c" + error + "\n\n§r" + content;
    }

    static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
