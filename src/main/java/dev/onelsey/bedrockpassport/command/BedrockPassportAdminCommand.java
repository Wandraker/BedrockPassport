package dev.onelsey.bedrockpassport.command;

import dev.onelsey.bedrockpassport.BedrockPassportPlugin;
import dev.onelsey.bedrockpassport.security.SessionGuard;
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
            sender.sendMessage("You do not have permission to use BedrockPassport admin commands.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            BedrockPassportPlugin.ReloadResult result = plugin.reloadPassport();
            sender.sendMessage(result.message());
            return true;
        }

        if (args[0].equalsIgnoreCase("who")) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /" + label + " who <javaName>");
                return true;
            }
            SessionGuard.SessionSnapshot snapshot = plugin.sessionSnapshot(args[1]);
            if (snapshot == null) {
                sender.sendMessage("BedrockPassport: no active or pending session for " + args[1] + ".");
                return true;
            }
            sender.sendMessage("BedrockPassport session for " + snapshot.javaName() + ":");
            sender.sendMessage("  state: " + snapshot.state());
            sender.sendMessage("  java UUID: " + snapshot.javaUuid());
            if (snapshot.bedrock()) {
                sender.sendMessage("  source: Bedrock");
                sender.sendMessage("  Xbox name: " + (snapshot.bedrockUsername() == null ? "unknown" : snapshot.bedrockUsername()));
                sender.sendMessage("  XUID: " + (snapshot.xuid() == null ? "unknown" : snapshot.xuid()));
            } else {
                sender.sendMessage("  source: Java");
            }
            return true;
        }

        sender.sendMessage("Usage: /" + label + " <status|reload|who <javaName>>");
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("BedrockPassport " + plugin.getDescription().getVersion());
        sender.sendMessage("  runtime: " + (plugin.runtimeReady() ? "ready" : "not ready"));
        sender.sendMessage("  Passport selectors: " + plugin.activeSelectionCount());
        sender.sendMessage("  tracked sessions: " + plugin.trackedSessionCount());
        sender.sendMessage("  pending admissions: " + plugin.pendingAdmissionCount());
        sender.sendMessage("  config schema: " + plugin.getConfig().getInt("config-version", 0));
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
