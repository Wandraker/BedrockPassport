package dev.onelsey.bedrockpassport.scheduler;

import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.key.Key;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.function.Consumer;

public final class PlatformTasks {
    private static final Key FOLIA_BRAND = Key.key("papermc", "folia");

    private PlatformTasks() {
    }

    public static void executeGlobal(Plugin plugin, Runnable task) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(task, "task");
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
    }

    public static Consumer<Runnable> captureSenderExecutor(Plugin plugin, CommandSender sender) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(sender, "sender");

        if (sender instanceof Entity entity) {
            return task -> {
                Objects.requireNonNull(task, "task");
                entity.getScheduler().run(plugin, ignored -> task.run(), null);
            };
        }
        if (sender instanceof BlockCommandSender blockSender) {
            var location = blockSender.getBlock().getLocation();
            return task -> {
                Objects.requireNonNull(task, "task");
                plugin.getServer().getRegionScheduler().execute(plugin, location, task);
            };
        }
        if (sender instanceof ProxiedCommandSender proxied) {
            CommandSender caller = proxied.getCaller();
            if (caller != null && caller != sender) {
                return captureSenderExecutor(plugin, caller);
            }
        }
        return task -> executeGlobal(plugin, task);
    }

    public static boolean isFolia() {
        return ServerBuildInfo.buildInfo().isBrandCompatible(FOLIA_BRAND);
    }
}
