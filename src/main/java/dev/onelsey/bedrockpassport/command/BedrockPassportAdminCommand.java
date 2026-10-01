package dev.onelsey.bedrockpassport.command;

import dev.onelsey.bedrockpassport.BedrockPassportPlugin;
import dev.onelsey.bedrockpassport.scheduler.PlatformTasks;
import dev.onelsey.bedrockpassport.security.SessionGuard;
import dev.onelsey.bedrockpassport.ui.ChatUi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class BedrockPassportAdminCommand implements CommandExecutor, TabCompleter {
    private final BedrockPassportPlugin plugin;

    public BedrockPassportAdminCommand(BedrockPassportPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bedrockpassport.admin")) {
            sender.sendMessage(ChatUi.error(plugin.message("admin.no-permission")));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            Consumer<Runnable> replyExecutor = PlatformTasks.captureSenderExecutor(plugin, sender);
            sender.sendMessage(ChatUi.info(plugin.message("admin.reload-start")));
            PlatformTasks.executeGlobal(plugin, () -> {
                BedrockPassportPlugin.ReloadResult result = plugin.reloadPassport();
                replyExecutor.accept(() -> sender.sendMessage(result.success()
                        ? ChatUi.success(result.message())
                        : ChatUi.error(result.message())));
            });
            return true;
        }

        if (args[0].equalsIgnoreCase("who")) {
            if (args.length < 2) {
                sender.sendMessage(ChatUi.warning(plugin.message("admin.who-usage", Map.of("label", label))));
                return true;
            }
            SessionGuard.SessionSnapshot snapshot = plugin.sessionSnapshot(args[1]);
            if (snapshot == null) {
                sender.sendMessage(ChatUi.warning(plugin.message("admin.no-session", Map.of("name", args[1]))));
                return true;
            }
            sender.sendMessage(ChatUi.info(plugin.message("admin.session-header", Map.of("name", snapshot.javaName()))));
            sender.sendMessage(ChatUi.accentValue(plugin.message("admin.who.state"), snapshot.state()));
            sender.sendMessage(ChatUi.value(plugin.message("admin.who.java-uuid"), snapshot.javaUuid()));
            sender.sendMessage(ChatUi.value(plugin.message("admin.who.source"),
                    snapshot.bedrock() ? plugin.message("admin.who.bedrock") : plugin.message("admin.who.java")));
            if (snapshot.bedrock()) {
                sender.sendMessage(ChatUi.accentValue(plugin.message("admin.who.xbox-name"),
                        snapshot.bedrockUsername() == null ? plugin.message("admin.who.unknown") : snapshot.bedrockUsername()));
                sender.sendMessage(ChatUi.value(plugin.message("admin.who.xuid"),
                        snapshot.xuid() == null ? plugin.message("admin.who.unknown") : snapshot.xuid()));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reset-login")) {
            handleLoginReset(sender, label, args);
            return true;
        }

        sender.sendMessage(ChatUi.warning(plugin.message("admin.usage", Map.of("label", label))));
        return true;
    }

    private void handleLoginReset(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatUi.warning(plugin.message("admin.reset-usage", Map.of("label", label))));
            sender.sendMessage(ChatUi.warning(plugin.message("admin.reset-global-usage", Map.of("label", label))));
            return;
        }

        if (args[1].equalsIgnoreCase("--all")) {
            if (args.length != 3 || !args[2].equalsIgnoreCase("confirm")) {
                sender.sendMessage(ChatUi.warning(plugin.message("admin.reset-global-warning")));
                sender.sendMessage(ChatUi.warning(plugin.message("admin.reset-global-confirm", Map.of("label", label))));
                return;
            }
            Consumer<Runnable> replyExecutor = PlatformTasks.captureSenderExecutor(plugin, sender);
            sender.sendMessage(ChatUi.info(plugin.message("admin.reset-global-start")));
            sendLoginResetResult(sender, replyExecutor, plugin.resetAllSavedJavaLogins());
            return;
        }

        if (args.length != 2) {
            sender.sendMessage(ChatUi.warning(plugin.message("admin.reset-usage", Map.of("label", label))));
            return;
        }

        Consumer<Runnable> replyExecutor = PlatformTasks.captureSenderExecutor(plugin, sender);
        sender.sendMessage(ChatUi.info(plugin.message("admin.reset-one-start", Map.of("name", args[1]))));
        sendLoginResetResult(sender, replyExecutor, plugin.resetSavedJavaLogin(args[1]));
    }

    private void sendLoginResetResult(
            CommandSender sender,
            Consumer<Runnable> replyExecutor,
            CompletableFuture<BedrockPassportPlugin.LoginResetResult> future
    ) {
        future.whenComplete((result, error) -> replyExecutor.accept(() -> {
            if (error != null) {
                sender.sendMessage(ChatUi.error(plugin.message("admin.reset-failed")));
                return;
            }
            if (!result.success()) {
                sender.sendMessage(ChatUi.error(result.message()));
            } else if (result.changed()) {
                sender.sendMessage(ChatUi.success(result.message()));
            } else {
                sender.sendMessage(ChatUi.warning(result.message()));
            }
        }));
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatUi.info("BedrockPassport " + plugin.getDescription().getVersion()));
        sender.sendMessage(plugin.runtimeReady()
                ? ChatUi.goodValue(plugin.message("admin.status.runtime"), plugin.message("admin.status.ready"))
                : ChatUi.value(plugin.message("admin.status.runtime"), plugin.message("admin.status.not-ready")));
        sender.sendMessage(ChatUi.accentValue(plugin.message("admin.status.selectors"), plugin.activeSelectionCount()));
        sender.sendMessage(ChatUi.accentValue(plugin.message("admin.status.tracked"), plugin.trackedSessionCount()));
        sender.sendMessage(ChatUi.accentValue(plugin.message("admin.status.pending"), plugin.pendingAdmissionCount()));
        sender.sendMessage(ChatUi.accentValue(plugin.message("admin.status.provider"), plugin.identityProviderName()));
        sender.sendMessage(ChatUi.accentValue(plugin.message("admin.status.trust"), plugin.identityTrustName()));
        sender.sendMessage(ChatUi.accentValue(plugin.message("admin.status.threading"), plugin.threadingModelName()));
        sender.sendMessage(ChatUi.accentValue(plugin.message("admin.status.skin"), plugin.skinPolicyName()));
        sender.sendMessage(ChatUi.value(plugin.message("admin.status.skinsrestorer"),
                plugin.skinsRestorerPresent() ? plugin.message("admin.status.detected") : plugin.message("admin.status.not-detected")));
        sender.sendMessage(ChatUi.value(plugin.message("admin.status.access"),
                plugin.allowlistEnabled() ? plugin.message("admin.status.enabled") : plugin.message("admin.status.disabled")));
        sender.sendMessage(ChatUi.value(plugin.message("admin.status.access-players"), plugin.allowlistPlayerCount()));
        sender.sendMessage(ChatUi.value(plugin.message("admin.status.access-xuids"), plugin.allowlistXuidCount()));
        sender.sendMessage(ChatUi.value(plugin.message("admin.status.config-locale"), plugin.configLocaleName()));
        sender.sendMessage(ChatUi.value(plugin.message("admin.status.messages-locale"), plugin.messagesLocaleName()));
        sender.sendMessage(ChatUi.value(plugin.message("admin.status.schema"), plugin.getConfig().getInt("config-version", 0)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("bedrockpassport.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> values = new ArrayList<>();
            for (String value : List.of("status", "reload", "who", "reset-login")) {
                if (value.startsWith(prefix)) {
                    values.add(value);
                }
            }
            return values;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reset-login")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return "--all".startsWith(prefix) ? List.of("--all") : List.of();
        }
        if (args.length == 3
                && args[0].equalsIgnoreCase("reset-login")
                && args[1].equalsIgnoreCase("--all")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return "confirm".startsWith(prefix) ? List.of("confirm") : List.of();
        }
        return List.of();
    }
}
