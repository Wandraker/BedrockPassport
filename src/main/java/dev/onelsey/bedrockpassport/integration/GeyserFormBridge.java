package dev.onelsey.bedrockpassport.integration;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

final class GeyserFormBridge {
    private final Method sessionIsClosed;
    private final Method sessionExecuteInEventLoop;
    private final Method sessionSendForm;

    private final Method customFormBuilder;
    private final Method customBuilderTitle;
    private final Method customBuilderLabel;
    private final Method customBuilderInput;
    private final Method customBuilderClosedOrInvalid;
    private final Method customBuilderValid;
    private final Method customBuilderBuild;
    private final Method customResponseAsInput;

    private final Method simpleFormBuilder;
    private final Method simpleBuilderTitle;
    private final Method simpleBuilderContent;
    private final Method simpleBuilderButton;
    private final Method simpleBuilderClosedOrInvalid;
    private final Method simpleBuilderValid;
    private final Method simpleBuilderBuild;
    private final Method simpleResponseClickedButtonId;

    private final Method modalFormBuilder;
    private final Method modalBuilderTitle;
    private final Method modalBuilderContent;
    private final Method modalBuilderButton1;
    private final Method modalBuilderButton2;
    private final Method modalBuilderClosedOrInvalid;
    private final Method modalBuilderValid;
    private final Method modalBuilderBuild;
    private final Method modalResponseClickedFirst;

    GeyserFormBridge(ClassLoader loader, Class<?> sessionClass) throws ReflectiveOperationException {
        Class<?> formClass = Class.forName("org.geysermc.cumulus.form.Form", true, loader);
        Class<?> customFormClass = Class.forName("org.geysermc.cumulus.form.CustomForm", true, loader);
        Class<?> customBuilderClass = Class.forName("org.geysermc.cumulus.form.CustomForm$Builder", true, loader);
        Class<?> customResponseClass = Class.forName("org.geysermc.cumulus.response.CustomFormResponse", true, loader);
        Class<?> simpleFormClass = Class.forName("org.geysermc.cumulus.form.SimpleForm", true, loader);
        Class<?> simpleBuilderClass = Class.forName("org.geysermc.cumulus.form.SimpleForm$Builder", true, loader);
        Class<?> simpleResponseClass = Class.forName("org.geysermc.cumulus.response.SimpleFormResponse", true, loader);
        Class<?> modalFormClass = Class.forName("org.geysermc.cumulus.form.ModalForm", true, loader);
        Class<?> modalBuilderClass = Class.forName("org.geysermc.cumulus.form.ModalForm$Builder", true, loader);
        Class<?> modalResponseClass = Class.forName("org.geysermc.cumulus.response.ModalFormResponse", true, loader);

        this.sessionIsClosed = sessionClass.getMethod("isClosed");
        this.sessionExecuteInEventLoop = sessionClass.getMethod("executeInEventLoop", Runnable.class);
        this.sessionSendForm = sessionClass.getMethod("sendForm", formClass);

        this.customFormBuilder = customFormClass.getMethod("builder");
        this.customBuilderTitle = customBuilderClass.getMethod("title", String.class);
        this.customBuilderLabel = customBuilderClass.getMethod("label", String.class);
        this.customBuilderInput = customBuilderClass.getMethod("input", String.class, String.class, String.class);
        this.customBuilderClosedOrInvalid = customBuilderClass.getMethod("closedOrInvalidResultHandler", Runnable.class);
        this.customBuilderValid = customBuilderClass.getMethod("validResultHandler", Consumer.class);
        this.customBuilderBuild = customBuilderClass.getMethod("build");
        this.customResponseAsInput = customResponseClass.getMethod("asInput");

        this.simpleFormBuilder = simpleFormClass.getMethod("builder");
        this.simpleBuilderTitle = simpleBuilderClass.getMethod("title", String.class);
        this.simpleBuilderContent = simpleBuilderClass.getMethod("content", String.class);
        this.simpleBuilderButton = simpleBuilderClass.getMethod("button", String.class);
        this.simpleBuilderClosedOrInvalid = simpleBuilderClass.getMethod("closedOrInvalidResultHandler", Runnable.class);
        this.simpleBuilderValid = simpleBuilderClass.getMethod("validResultHandler", Consumer.class);
        this.simpleBuilderBuild = simpleBuilderClass.getMethod("build");
        this.simpleResponseClickedButtonId = simpleResponseClass.getMethod("clickedButtonId");

        this.modalFormBuilder = modalFormClass.getMethod("builder");
        this.modalBuilderTitle = modalBuilderClass.getMethod("title", String.class);
        this.modalBuilderContent = modalBuilderClass.getMethod("content", String.class);
        this.modalBuilderButton1 = modalBuilderClass.getMethod("button1", String.class);
        this.modalBuilderButton2 = modalBuilderClass.getMethod("button2", String.class);
        this.modalBuilderClosedOrInvalid = modalBuilderClass.getMethod("closedOrInvalidResultHandler", Runnable.class);
        this.modalBuilderValid = modalBuilderClass.getMethod("validResultHandler", Consumer.class);
        this.modalBuilderBuild = modalBuilderClass.getMethod("build");
        this.modalResponseClickedFirst = modalResponseClass.getMethod("clickedFirst");
    }

    void showNicknameForm(
            Object session,
            String title,
            String text,
            String inputLabel,
            String placeholder,
            String initialValue,
            String error,
            Consumer<String> onSubmit,
            Runnable onClosed,
            Consumer<Throwable> onFailure
    ) {
        dispatch(session, onFailure, () -> {
            Object builder = customFormBuilder.invoke(null);
            customBuilderTitle.invoke(builder, title);
            if (error != null && !error.isBlank()) {
                customBuilderLabel.invoke(builder, "§c" + error);
            }
            customBuilderLabel.invoke(builder, text);
            customBuilderInput.invoke(builder, inputLabel, placeholder, initialValue == null ? "" : initialValue);
            customBuilderClosedOrInvalid.invoke(builder, onClosed);
            Consumer<Object> responseConsumer = response -> invokeResponse(onFailure, () -> onSubmit.accept((String) customResponseAsInput.invoke(response)));
            customBuilderValid.invoke(builder, responseConsumer);
            sendBuiltForm(session, builder, customBuilderBuild, onFailure, "nickname form");
        });
    }

    void showMenu(
            Object session,
            String title,
            String content,
            List<String> buttons,
            Consumer<Integer> onSelected,
            Runnable onClosed,
            Consumer<Throwable> onFailure
    ) {
        dispatch(session, onFailure, () -> {
            Object builder = simpleFormBuilder.invoke(null);
            simpleBuilderTitle.invoke(builder, title);
            simpleBuilderContent.invoke(builder, content);
            for (String button : buttons) {
                simpleBuilderButton.invoke(builder, button);
            }
            simpleBuilderClosedOrInvalid.invoke(builder, onClosed);
            Consumer<Object> responseConsumer = response -> invokeResponse(onFailure, () -> onSelected.accept((int) simpleResponseClickedButtonId.invoke(response)));
            simpleBuilderValid.invoke(builder, responseConsumer);
            sendBuiltForm(session, builder, simpleBuilderBuild, onFailure, "passport menu");
        });
    }

    void showConfirmation(
            Object session,
            String title,
            String content,
            String confirmButton,
            String cancelButton,
            Consumer<Boolean> onResult,
            Runnable onClosed,
            Consumer<Throwable> onFailure
    ) {
        dispatch(session, onFailure, () -> {
            Object builder = modalFormBuilder.invoke(null);
            modalBuilderTitle.invoke(builder, title);
            modalBuilderContent.invoke(builder, content);
            modalBuilderButton1.invoke(builder, confirmButton);
            modalBuilderButton2.invoke(builder, cancelButton);
            modalBuilderClosedOrInvalid.invoke(builder, onClosed);
            Consumer<Object> responseConsumer = response -> invokeResponse(onFailure, () -> onResult.accept((boolean) modalResponseClickedFirst.invoke(response)));
            modalBuilderValid.invoke(builder, responseConsumer);
            sendBuiltForm(session, builder, modalBuilderBuild, onFailure, "confirmation form");
        });
    }

    private void dispatch(Object session, Consumer<Throwable> onFailure, ReflectiveTask task) {
        try {
            sessionExecuteInEventLoop.invoke(session, (Runnable) () -> {
                try {
                    if ((boolean) sessionIsClosed.invoke(session)) {
                        onFailure.accept(new IllegalStateException("Geyser session closed before passport form could be shown"));
                        return;
                    }
                    task.run();
                } catch (Throwable throwable) {
                    onFailure.accept(GeyserPendingSessionBridge.bridgeFailure(throwable));
                }
            });
        } catch (Throwable throwable) {
            onFailure.accept(GeyserPendingSessionBridge.bridgeFailure(throwable));
        }
    }

    private void sendBuiltForm(Object session, Object builder, Method buildMethod, Consumer<Throwable> onFailure, String name) throws ReflectiveOperationException {
        Object form = buildMethod.invoke(builder);
        Object sent = sessionSendForm.invoke(session, form);
        if (sent instanceof Boolean success && !success) {
            onFailure.accept(new IllegalStateException("Geyser rejected the " + name));
        }
    }

    private static void invokeResponse(Consumer<Throwable> onFailure, ReflectiveTask task) {
        try {
            task.run();
        } catch (Throwable throwable) {
            onFailure.accept(GeyserPendingSessionBridge.bridgeFailure(throwable));
        }
    }

    @FunctionalInterface
    private interface ReflectiveTask {
        void run() throws Throwable;
    }
}
