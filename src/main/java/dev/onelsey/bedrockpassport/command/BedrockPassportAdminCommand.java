package dev.onelsey.bedrockpassport.command;

import dev.onelsey.bedrockpassport.BedrockPassportPlugin;
import dev.onelsey.bedrockpassport.security.SessionGuard;
import dev.onelsey.bedrockpassport.ui.ChatUi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BedrockPassportAdminCommand implements CommandExecutor, TabCompleter {
    private final BedrockPassportPlugin plugin;

    public BedrockPassportAdminCommand(BedrockPassportPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bedrockpassport.admin")) {
            sender.sendMessage(ChatUi.error("You do not have permission to use this command."));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            BedrockPassportPlugin.ReloadResult result = plugin.reloadPassport();
            sender.sendMessage(result.success() ? ChatUi.success(result.message()) : ChatUi.error(result.message()));
            return true;
        }

        if (args[0].equalsIgnoreCase("who")) {
            if (args.length < 2) {
                sender.sendMessage(ChatUi.warning("Usage: /" + label + " who <javaName>"));
                return true;
            }
            SessionGuard.SessionSnapshot snapshot = plugin.sessionSnapshot(args[1]);
            if (snapshot == null) {
                sender.sendMessage(ChatUi.warning("No active or pending session for " + args[1] + "."));
                return true;
            }
            sender.sendMessage(ChatUi.info("Session for " + snapshot.javaName()));
            sender.sendMessage(ChatUi.accentValue("state", snapshot.state()));
            sender.sendMessage(ChatUi.value("Java UUID", snapshot.javaUuid()));
            sender.sendMessage(ChatUi.value("source", snapshot.bedrock() ? "Bedrock" : "Java"));
            if (snapshot.bedrock()) {
                sender.sendMessage(ChatUi.accentValue("Xbox name", snapshot.bedrockUsername() == null ? "unknown" : snapshot.bedrockUsername()));
                sender.sendMessage(ChatUi.value("XUID", snapshot.xuid() == null ? "unknown" : snapshot.xuid()));
            }
            return true;
        }

        sender.sendMessage(ChatUi.warning("Usage: /" + label + " <status|reload|who <javaName>>"));
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatUi.info("BedrockPassport " + plugin.getDescription().getVersion()));
        if (plugin.runtimeReady()) {
            sender.sendMessage(ChatUi.goodValue("runtime", "ready"));
        } else {
            sender.sendMessage(ChatUi.value("runtime", "not ready"));
        }
        sender.sendMessage(ChatUi.accentValue("Passport selectors", plugin.activeSelectionCount()));
        sender.sendMessage(ChatUi.accentValue("tracked sessions", plugin.trackedSessionCount()));
        sender.sendMessage(ChatUi.accentValue("pending admissions", plugin.pendingAdmissionCount()));
        sender.sendMessage(ChatUi.accentValue("identity provider", plugin.identityProviderName()));
        sender.sendMessage(ChatUi.accentValue("identity trust", "untrusted handoff"));
        sender.sendMessage(ChatUi.accentValue("skin policy", plugin.skinPolicyName()));
        sender.sendMessage(ChatUi.value("SkinsRestorer", plugin.skinsRestorerPresent() ? "detected" : "not detected"));
        sender.sendMessage(ChatUi.value("config schema", plugin.getConfig().getInt("config-version", 0)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("bedrockpassport.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> values = new ArrayList<>();
            for (String value : List.of("status", "reload", "who")) {
                if (value.startsWith(prefix)) {
                    values.add(value);
                }
            }
            return values;
        }
        return List.of();
    }
}
