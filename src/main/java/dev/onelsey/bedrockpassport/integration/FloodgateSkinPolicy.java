package dev.onelsey.bedrockpassport.integration;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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
import org.geysermc.floodgate.api.player.PropertyKey;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("deprecation")
public final class FloodgateSkinPolicy implements Listener, AutoCloseable {
    private static final long TRACK_TTL_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final int CONFIRMED_SKIN_MAX_ATTEMPTS = 40;
    private static final long CONFIRMED_SKIN_PERIOD_TICKS = 5L;

    private final Plugin plugin;
    private final Policy policy;
    private final boolean skinsRestorerPresent;
    private final Logger logger;
    private final SkinApplyEvent.SkinData floodgateDefaultSkin;
    private final Map<String, ManagedIdentity> managedByXuid = new ConcurrentHashMap<>();
    private final Map<UUID, ManagedIdentity> managedByJavaUuid = new ConcurrentHashMap<>();
    private final Map<UUID, SkinApplyEvent.SkinData> originalBedrockSkinByUuid = new ConcurrentHashMap<>();
    private FloodgateSubscriber<SkinApplyEvent> captureSubscriber;
    private FloodgateSubscriber<SkinApplyEvent> finalSubscriber;
    private Plugin skinsRestorerPlugin;
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

        captureSubscriber = FloodgateApi.getInstance().getEventBus().subscribe(
                SkinApplyEvent.class,
                this::captureOriginalBedrockSkin,
                PostOrder.FIRST
        );
        finalSubscriber = FloodgateApi.getInstance().getEventBus().subscribe(
                SkinApplyEvent.class,
                this::finalizeSkinApply,
                PostOrder.LAST
        );

        if (skinsRestorerPresent) {
            Plugin detected = plugin.getServer().getPluginManager().getPlugin("SkinsRestorer");
            if (detected != null && detected.isEnabled()) {
                skinsRestorerPlugin = detected;
                plugin.getServer().getPluginManager().registerEvents(this, plugin);
                bukkitListenerRegistered = true;
            }
        }
    }

    public void track(String xuid, UUID javaUuid, String javaName) {
        if (policy == Policy.OFF || xuid == null || javaUuid == null || javaName == null) {
            return;
        }

        purgeExpired();
        ManagedIdentity managed = new ManagedIdentity(
                xuid,
                javaUuid,
                javaName,
                System.nanoTime() + TRACK_TTL_NANOS
        );
        managedByXuid.put(xuid, managed);
        managedByJavaUuid.put(javaUuid, managed);
    }

    private void captureOriginalBedrockSkin(SkinApplyEvent event) {
        purgeExpired();
        ManagedIdentity managed = managedIdentity(event.player());
        if (managed == null) {
            return;
        }

        SkinApplyEvent.SkinData incomingSkin = event.newSkin();
        if (incomingSkin == null || sameSkin(incomingSkin, floodgateDefaultSkin)) {
            return;
        }

        originalBedrockSkinByUuid.put(managed.javaUuid(), incomingSkin);
        logger.fine("Captured original Floodgate/Bedrock skin for Passport identity " + managed.javaName() + ".");
    }

    private void finalizeSkinApply(SkinApplyEvent event) {
        purgeExpired();
        ManagedIdentity managed = managedIdentity(event.player());
        if (managed == null) {
            return;
        }

        SkinApplyEvent.SkinData originalBedrockSkin = originalBedrockSkinByUuid.remove(managed.javaUuid());
        if (originalBedrockSkin != null) {
            event.newSkin(originalBedrockSkin);
            event.setCancelled(false);
            logger.fine("Restored original Bedrock skin after other skin integrations for Passport identity " + managed.javaName() + ".");
            return;
        }

        SkinApplyEvent.SkinData currentSkin = event.currentSkin();
        boolean meaningfulCurrentSkin = currentSkin != null && !sameSkin(currentSkin, floodgateDefaultSkin);
        if (policy == Policy.PRESERVE && meaningfulCurrentSkin) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(false);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!skinsRestorerPresent || policy == Policy.OFF) {
            return;
        }

        purgeExpired();
        Player player = event.getPlayer();
        ManagedIdentity managed = managedByJavaUuid.get(player.getUniqueId());
        if (managed == null || !managed.javaName().equals(player.getName())) {
            return;
        }

        waitForConfirmedBedrockSkin(player, managed);
    }

    private void waitForConfirmedBedrockSkin(Player player, ManagedIdentity managed) {
        AtomicInteger attempts = new AtomicInteger();
        ScheduledTask scheduled = player.getScheduler().runAtFixedRate(
                plugin,
                task -> {
                    if (!player.isOnline()
                            || !managed.javaUuid().equals(player.getUniqueId())
                            || !managed.javaName().equals(player.getName())) {
                        task.cancel();
                        return;
                    }

                    FloodgatePlayer floodgatePlayer = FloodgateApi.getInstance().getPlayer(player.getUniqueId());
                    if (floodgatePlayer != null
                            && managed.xuid().equals(floodgatePlayer.getXuid())
                            && managed.javaUuid().equals(floodgatePlayer.getCorrectUniqueId())
                            && managed.javaName().equals(floodgatePlayer.getCorrectUsername())) {
                        Object uploaded = floodgatePlayer.getProperty(PropertyKey.SKIN_UPLOADED);
                        if (uploaded instanceof SkinApplyEvent.SkinData skinData
                                && !sameSkin(skinData, floodgateDefaultSkin)) {
                            task.cancel();
                            applyConfirmedBedrockSkin(player, managed, skinData);
                            return;
                        }
                    }

                    if (attempts.incrementAndGet() >= CONFIRMED_SKIN_MAX_ATTEMPTS) {
                        task.cancel();
                        logger.warning(
                                "BedrockPassport did not observe Floodgate SKIN_UPLOADED for Passport identity "
                                        + managed.javaName()
                                        + " within the post-join wait window. The Bedrock skin upload did not reach Floodgate."
                        );
                    }
                },
                null,
                1L,
                CONFIRMED_SKIN_PERIOD_TICKS
        );

        if (scheduled == null) {
            logger.fine("Could not schedule confirmed Bedrock skin wait for " + managed.javaName() + " because the player scheduler is retired.");
        }
    }

    private void applyConfirmedBedrockSkin(
            Player player,
            ManagedIdentity managed,
            SkinApplyEvent.SkinData skinData
    ) {
        Plugin skinsRestorer = skinsRestorerPlugin;
        if (skinsRestorer == null || !skinsRestorer.isEnabled()) {
            return;
        }

        try {
            ClassLoader loader = skinsRestorer.getClass().getClassLoader();
            Class<?> providerClass = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider", true, loader);
            Class<?> skinPropertyClass = Class.forName("net.skinsrestorer.api.property.SkinProperty", true, loader);

            Object api = providerClass.getMethod("get").invoke(null);
            Object property = skinPropertyClass
                    .getMethod("of", String.class, String.class)
                    .invoke(null, skinData.value(), skinData.signature());
            Object skinApplier = api.getClass().getMethod("getSkinApplier", Class.class).invoke(api, Player.class);

            Method applySkin = findSkinApplyMethod(skinApplier.getClass(), player.getClass(), skinPropertyClass);
            if (applySkin == null) {
                throw new NoSuchMethodException("SkinsRestorer SkinApplier.applySkin(player, SkinProperty) is unavailable");
            }

            applySkin.invoke(skinApplier, player, property);
            logger.fine("Applied confirmed Floodgate Bedrock skin to Passport identity " + managed.javaName() + ".");
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            logger.log(
                    Level.WARNING,
                    "Could not apply confirmed Bedrock skin to Passport identity " + managed.javaName() + ".",
                    cause
            );
        } catch (Throwable throwable) {
            logger.log(
                    Level.WARNING,
                    "Could not apply confirmed Bedrock skin to Passport identity " + managed.javaName() + ".",
                    throwable
            );
        }
    }

    private static Method findSkinApplyMethod(Class<?> type, Class<?> playerClass, Class<?> skinPropertyClass) {
        Method fallback = null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals("applySkin") || method.getParameterCount() != 2) {
                continue;
            }

            Class<?> playerParameter = method.getParameterTypes()[0];
            Class<?> propertyParameter = method.getParameterTypes()[1];
            if (!playerParameter.isAssignableFrom(playerClass)
                    || !propertyParameter.isAssignableFrom(skinPropertyClass)) {
                continue;
            }

            if (playerParameter == Player.class) {
                return method;
            }
            fallback = method;
        }
        return fallback;
    }

    private ManagedIdentity managedIdentity(FloodgatePlayer player) {
        ManagedIdentity managed = managedByXuid.get(player.getXuid());
        if (managed == null) {
            return null;
        }
        if (!managed.javaUuid().equals(player.getCorrectUniqueId())
                || !managed.javaName().equals(player.getCorrectUsername())) {
            return null;
        }
        return managed;
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
        managedByXuid.entrySet().removeIf(entry -> {
            ManagedIdentity managed = entry.getValue();
            if (managed.expiresAtNanos() > now) {
                return false;
            }
            managedByJavaUuid.remove(managed.javaUuid(), managed);
            originalBedrockSkinByUuid.remove(managed.javaUuid());
            return true;
        });
        managedByJavaUuid.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
    }

    public void release(String xuid) {
        if (xuid == null || xuid.isBlank()) {
            return;
        }

        ManagedIdentity managed = managedByXuid.remove(xuid);
        if (managed != null) {
            managedByJavaUuid.remove(managed.javaUuid(), managed);
            originalBedrockSkinByUuid.remove(managed.javaUuid());
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
        unsubscribe(captureSubscriber);
        unsubscribe(finalSubscriber);
        captureSubscriber = null;
        finalSubscriber = null;

        if (bukkitListenerRegistered) {
            HandlerList.unregisterAll(this);
            bukkitListenerRegistered = false;
        }

        skinsRestorerPlugin = null;
        managedByXuid.clear();
        managedByJavaUuid.clear();
        originalBedrockSkinByUuid.clear();
    }

    private void unsubscribe(FloodgateSubscriber<SkinApplyEvent> current) {
        if (current == null) {
            return;
        }
        try {
            FloodgateApi.getInstance().getEventBus().unsubscribe(current);
        } catch (Throwable throwable) {
            logger.fine("Could not unregister BedrockPassport Floodgate skin policy: " + throwable.getMessage());
        }
    }

    private record ManagedIdentity(
            String xuid,
            UUID javaUuid,
            String javaName,
            long expiresAtNanos
    ) {
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
            throw new IllegalArgumentException(
                    "Unsupported skins.policy: " + value + " (expected preserve, refresh or off)"
            );
        }
    }
}
