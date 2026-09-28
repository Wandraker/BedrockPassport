package dev.onelsey.bedrockpassport.integration;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.geysermc.event.PostOrder;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.event.FloodgateSubscriber;
import org.geysermc.floodgate.api.event.skin.SkinApplyEvent;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class FloodgateSkinPolicy implements Listener, AutoCloseable {
    private static final long TRACK_TTL_NANOS = TimeUnit.MINUTES.toNanos(2);

    private final Plugin plugin;
    private final Policy policy;
    private final boolean skinsRestorerPresent;
    private final Logger logger;
    private final SkinApplyEvent.SkinData floodgateDefaultSkin;
    private final Map<String, ManagedIdentity> managedByXuid = new ConcurrentHashMap<>();
    private final Map<UUID, ManagedIdentity> pendingJoinRestoreByUuid = new ConcurrentHashMap<>();
    private FloodgateSubscriber<SkinApplyEvent> subscriber;
    private boolean bukkitListenerRegistered;

    public FloodgateSkinPolicy(Plugin plugin, String configuredPolicy, boolean skinsRestorerPresent, Logger logger) {
        this.plugin = plugin;
        this.policy = Policy.parse(configuredPolicy);
        this.skinsRestorerPresent = skinsRestorerPresent;
        this.logger = logger;
        this.floodgateDefaultSkin = loadFloodgateDefaultSkin(logger);
    }

    public void register() {
        if (policy == Policy.OFF) {
            return;
        }
        subscriber = FloodgateApi.getInstance().getEventBus().subscribe(
                SkinApplyEvent.class,
                this::onSkinApply,
                PostOrder.LAST
        );
        if (skinsRestorerPresent) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            bukkitListenerRegistered = true;
        }
    }

    public void track(String xuid, UUID javaUuid, String javaName) {
        if (policy == Policy.OFF || xuid == null || javaUuid == null || javaName == null) {
            return;
        }
        purgeExpired();
        ManagedIdentity managed = new ManagedIdentity(javaUuid, javaName, System.nanoTime() + TRACK_TTL_NANOS);
        managedByXuid.put(xuid, managed);
        if (skinsRestorerPresent) {
            pendingJoinRestoreByUuid.put(javaUuid, managed);
        }
    }

    private void onSkinApply(SkinApplyEvent event) {
        purgeExpired();
        FloodgatePlayer player = event.player();
        ManagedIdentity managed = managedByXuid.get(player.getXuid());
        if (managed == null) {
            return;
        }
        if (!managed.javaUuid().equals(player.getCorrectUniqueId()) || !managed.javaName().equals(player.getCorrectUsername())) {
            return;
        }

        SkinApplyEvent.SkinData currentSkin = event.currentSkin();
        boolean meaningfulCurrentSkin = currentSkin != null && !sameSkin(currentSkin, floodgateDefaultSkin);
        if (policy == Policy.PRESERVE && meaningfulCurrentSkin) {
            event.setCancelled(true);
        } else {
            event.setCancelled(false);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!skinsRestorerPresent || policy == Policy.OFF) {
            return;
        }
        purgeExpired();
        Player player = event.getPlayer();
        ManagedIdentity managed = pendingJoinRestoreByUuid.remove(player.getUniqueId());
        if (managed == null || !managed.javaName().equals(player.getName())) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> restoreSkinsRestorerSkin(player, managed));
    }

    private void restoreSkinsRestorerSkin(Player player, ManagedIdentity managed) {
        if (!player.isOnline() || !managed.javaUuid().equals(player.getUniqueId()) || !managed.javaName().equals(player.getName())) {
            return;
        }

        Plugin skinsRestorer = Bukkit.getPluginManager().getPlugin("SkinsRestorer");
        if (skinsRestorer == null || !skinsRestorer.isEnabled()) {
            return;
        }

        try {
            ClassLoader loader = skinsRestorer.getClass().getClassLoader();
            Class<?> providerClass = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider", true, loader);
            Object api = providerClass.getMethod("get").invoke(null);
            Object playerStorage = api.getClass().getMethod("getPlayerStorage").invoke(api);
            Object storedResult = playerStorage.getClass().getMethod("getSkinOfPlayer", UUID.class).invoke(playerStorage, managed.javaUuid());
            if (!(storedResult instanceof Optional<?> optional) || optional.isEmpty()) {
                logger.fine("No stored SkinsRestorer skin is linked to Passport identity " + managed.javaName() + "; keeping the normal Floodgate/Bedrock skin path.");
                return;
            }

            Object skinProperty = optional.get();
            Object skinApplier = api.getClass().getMethod("getSkinApplier", Class.class).invoke(api, Player.class);
            Method applySkin = findStoredSkinApplyMethod(skinApplier.getClass(), player.getClass(), skinProperty.getClass());
            if (applySkin == null) {
                throw new NoSuchMethodException("SkinsRestorer SkinApplier.applySkin(player, storedSkin) is unavailable");
            }
            applySkin.invoke(skinApplier, player, skinProperty);
            logger.fine("Restored stored SkinsRestorer skin for Passport identity " + managed.javaName() + ".");
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            logger.log(Level.WARNING, "Could not restore the SkinsRestorer join skin for Passport identity " + managed.javaName() + ".", cause);
        } catch (Throwable throwable) {
            logger.log(Level.WARNING, "Could not restore the SkinsRestorer join skin for Passport identity " + managed.javaName() + ".", throwable);
        }
    }

    private static Method findStoredSkinApplyMethod(Class<?> type, Class<?> playerClass, Class<?> propertyClass) {
        Method fallback = null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals("applySkin") || method.getParameterCount() != 2) {
                continue;
            }
            Class<?> playerParameter = method.getParameterTypes()[0];
            Class<?> propertyParameter = method.getParameterTypes()[1];
            if (!playerParameter.isAssignableFrom(playerClass) || !propertyParameter.isAssignableFrom(propertyClass)) {
                continue;
            }
            if (playerParameter == Player.class) {
                return method;
            }
            fallback = method;
        }
        return fallback;
    }

    private static SkinApplyEvent.SkinData loadFloodgateDefaultSkin(Logger logger) {
        try {
            ClassLoader loader = FloodgateApi.class.getClassLoader();
            Class<?> skinDataImpl = Class.forName("org.geysermc.floodgate.skin.SkinDataImpl", false, loader);
            Field field = skinDataImpl.getField("DEFAULT_SKIN");
            Object value = field.get(null);
            if (value instanceof SkinApplyEvent.SkinData skinData) {
                return skinData;
            }
        } catch (Throwable throwable) {
            logger.fine("Could not resolve Floodgate placeholder skin: " + throwable.getMessage());
        }
        return null;
    }

    private static boolean sameSkin(SkinApplyEvent.SkinData first, SkinApplyEvent.SkinData second) {
        return first != null
                && second != null
                && first.value().equals(second.value())
                && first.signature().equals(second.signature());
    }

    private void purgeExpired() {
        long now = System.nanoTime();
        managedByXuid.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
        pendingJoinRestoreByUuid.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
    }

    public void release(String xuid) {
        if (xuid == null || xuid.isBlank()) {
            return;
        }
        ManagedIdentity managed = managedByXuid.remove(xuid);
        if (managed != null) {
            pendingJoinRestoreByUuid.remove(managed.javaUuid(), managed);
        }
    }

    public String policyName() {
        return policy.configName;
    }

    public boolean skinsRestorerPresent() {
        return skinsRestorerPresent;
    }

    @Override
    public void close() {
        FloodgateSubscriber<SkinApplyEvent> current = subscriber;
        subscriber = null;
        if (current != null) {
            try {
                FloodgateApi.getInstance().getEventBus().unsubscribe(current);
            } catch (Throwable throwable) {
                logger.fine("Could not unregister BedrockPassport Floodgate skin policy: " + throwable.getMessage());
            }
        }
        if (bukkitListenerRegistered) {
            HandlerList.unregisterAll(this);
            bukkitListenerRegistered = false;
        }
        managedByXuid.clear();
        pendingJoinRestoreByUuid.clear();
    }

    private record ManagedIdentity(UUID javaUuid, String javaName, long expiresAtNanos) {
    }

    private enum Policy {
        PRESERVE("preserve"),
        REFRESH("refresh"),
        OFF("off");

        private final String configName;

        Policy(String configName) {
            this.configName = configName;
        }

        private static Policy parse(String value) {
            String normalized = value == null ? "preserve" : value.trim().toLowerCase(Locale.ROOT);
            for (Policy policy : values()) {
                if (policy.configName.equals(normalized)) {
                    return policy;
                }
            }
            throw new IllegalArgumentException("Unsupported skins.policy: " + value + " (expected preserve, refresh or off)");
        }
    }
}
