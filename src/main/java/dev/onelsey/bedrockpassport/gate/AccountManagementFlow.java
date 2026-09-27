package dev.onelsey.bedrockpassport.gate;

import dev.onelsey.bedrockpassport.data.Identity;
import dev.onelsey.bedrockpassport.data.IdentityRepository;
import dev.onelsey.bedrockpassport.integration.GeyserPendingSessionBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static dev.onelsey.bedrockpassport.gate.GateSupport.unwrap;

final class AccountManagementFlow {
    private final IdentityRepository repository;
    private final GeyserPendingSessionBridge geyser;
    private final GateMessages messages;
    private final Consumer<GateState> home;

    AccountManagementFlow(
            IdentityRepository repository,
            GeyserPendingSessionBridge geyser,
            GateMessages messages,
            Consumer<GateState> home
    ) {
        this.repository = repository;
        this.geyser = geyser;
        this.messages = messages;
        this.home = home;
    }

    void show(GateState state, List<Identity> accounts) {
        if (!canShow(state)) {
            return;
        }
        if (accounts.isEmpty()) {
            home.accept(state);
            return;
        }

        long screen = state.beginScreen();
        List<String> buttons = new ArrayList<>();
        for (Identity identity : accounts) {
            buttons.add(messages.removePrefix() + identity.gameName());
        }
        int backIndex = buttons.size();
        buttons.add(messages.back());

        geyser.showMenu(
                state.handle,
                messages.manageTitle(),
                messages.manageText(),
                buttons,
                index -> {
                    if (!state.claimAction(screen)) {
                        return;
                    }
                    state.touch();
                    if (index >= 0 && index < accounts.size()) {
                        showRemoveConfirmation(state, accounts.get(index));
                    } else if (index == backIndex) {
                        home.accept(state);
                    } else {
                        state.result.completeExceptionally(new IllegalStateException("Unexpected BedrockPassport management button: " + index));
                    }
                },
                () -> reopenHomeAfterClose(state, screen),
                state.result::completeExceptionally
        );
    }

    private void showRemoveConfirmation(GateState state, Identity identity) {
        if (!canShow(state)) {
            return;
        }

        long screen = state.beginScreen();
        String content = messages.removeConfirmText().replace("%account%", identity.gameName());
        geyser.showConfirmation(
                state.handle,
                messages.removeConfirmTitle(),
                content,
                messages.removeConfirmButton(),
                messages.cancelButton(),
                confirmed -> {
                    if (!state.claimAction(screen)) {
                        return;
                    }
                    state.touch();
                    if (!confirmed) {
                        reload(state);
                        return;
                    }
                    repository.remove(identity.id(), state.xuid).whenComplete((removed, error) -> {
                        if (state.result.isDone()) {
                            return;
                        }
                        if (error != null) {
                            state.result.completeExceptionally(unwrap(error));
                            return;
                        }
                        if (!removed) {
                            state.result.completeExceptionally(new IllegalStateException("Identity was already removed"));
                            return;
                        }
                        state.lastInput = "";
                        home.accept(state);
                    });
                },
                () -> {
                    if (!state.claimAction(screen)) {
                        return;
                    }
                    try {
                        geyser.schedule(state.handle, () -> reload(state), 700L);
                    } catch (Throwable throwable) {
                        state.result.completeExceptionally(throwable);
                    }
                },
                state.result::completeExceptionally
        );
    }

    private void reload(GateState state) {
        repository.listByXuid(state.xuid).whenComplete((accounts, error) -> {
            if (state.result.isDone()) {
                return;
            }
            if (error != null) {
                state.result.completeExceptionally(unwrap(error));
                return;
            }
            if (accounts.isEmpty()) {
                home.accept(state);
            } else {
                show(state, accounts);
            }
        });
    }

    private void reopenHomeAfterClose(GateState state, long screen) {
        if (!state.claimAction(screen)) {
            return;
        }
        try {
            geyser.schedule(state.handle, () -> home.accept(state), 700L);
        } catch (Throwable throwable) {
            state.result.completeExceptionally(throwable);
        }
    }

    private boolean canShow(GateState state) {
        if (state.result.isDone() || state.handle == null) {
            return false;
        }
        try {
            if (geyser.isClosed(state.handle)) {
                state.result.completeExceptionally(new GateClosedException());
                return false;
            }
            return true;
        } catch (Throwable throwable) {
            state.result.completeExceptionally(throwable);
            return false;
        }
    }
}
