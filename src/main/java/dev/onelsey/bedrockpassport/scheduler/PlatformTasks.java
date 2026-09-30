package dev.onelsey.bedrockpassport.scheduler;

import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public final class PlatformTasks {
    private static final Key FOLIA_BRAND = Key.key("papermc", "folia");

    private PlatformTasks() {
    }

    public static void executeGlobal(Plugin plugin, Runnable task) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(task, "task");
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
    }

    public static void executeForSender(Plugin plugin, CommandSender sender, Runnable task) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(task, "task");

        if (sender instanceof Entity entity) {
            entity.getScheduler().run(plugin, ignored -> task.run(), null);
            return;
        }
        if (sender instanceof BlockCommandSender blockSender) {
            plugin.getServer().getRegionScheduler().execute(
                    plugin,
                    blockSender.getBlock().getLocation(),
                    task
            );
            return;
        }
        if (sender instanceof ProxiedCommandSender proxied) {
            CommandSender caller = proxied.getCaller();
            if (caller != null && caller != sender) {
                executeForSender(plugin, caller, task);
                return;
            }
        }
        executeGlobal(plugin, task);
    }

    public static boolean isFolia() {
        return ServerBuildInfo.buildInfo().isBrandCompatible(FOLIA_BRAND);
    }
}
