package dev.onelsey.bedrockpassport.integration;

import org.bukkit.plugin.Plugin;
import org.geysermc.event.PostOrder;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.event.FloodgateSubscriber;
import org.geysermc.floodgate.api.event.skin.SkinApplyEvent;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.lang.reflect.Field;
 import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
 import java.util.logging.Logger;

public final class FloodgateSkinPolicy implements AutoCloseable {
    private static final long TRACK_TTL_NANOS = TimeUnit.MINUTES.toNanos(2);

    private final Policy policy;
    private final boolean skinsRestorerPresent;
    private final Logger logger;
    private final SkinApplyEvent.SkinData floodgateDefaultSkin;
    private final Map<String, ManagedIdentity> managedByXuid = new ConcurrentHashMap<>();
    private final Map<UUID, SkinApplyEvent.SkinData> originalBedrockSkinByUuid = new ConcurrentHashMap<>();
    private final Set<UUID> preferredBedrockSkinByUuid = ConcurrentHashMap.newKeySet();
    private FloodgateSubscriber<SkinApplyEvent> captureSubscriber;
    private FloodgateSubscriber<SkinApplyEvent> finalSubscriber;

    public FloodgateSkinPolicy(Plugin plugin, String configuredPolicy, boolean skinsRestorerPresent, Logger logger) {
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
    }

    public void track(String xuid, UUID javaUuid, String javaName) {
        if (policy == Policy.OFF || xuid == null || javaUuid == null || javaName == null) {
            return;
        }
        purgeExpired();
        ManagedIdentity managed = new ManagedIdentity(javaUuid, javaName, System.nanoTime() + TRACK_TTL_NANOS);
        managedByXuid.put(xuid, managed);
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
            preferredBedrockSkinByUuid.add(managed.javaUuid());
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

    private ManagedIdentity managedIdentity(FloodgatePlayer player) {
        ManagedIdentity managed = managedByXuid.get(player.getXuid());
        if (managed == null) {
            return null;
        }
        if (!managed.javaUuid().equals(player.getCorrectUniqueId()) || !managed.javaName().equals(player.getCorrectUsername())) {
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
            originalBedrockSkinByUuid.remove(managed.javaUuid());
            preferredBedrockSkinByUuid.remove(managed.javaUuid());
            return true;
        });
    }

    public void release(String xuid) {
        if (xuid == null || xuid.isBlank()) {
            return;
        }
        ManagedIdentity managed = managedByXuid.remove(xuid);
        if (managed != null) {
            originalBedrockSkinByUuid.remove(managed.javaUuid());
            preferredBedrockSkinByUuid.remove(managed.javaUuid());
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
        managedByXuid.clear();
        originalBedrockSkinByUuid.clear();
        preferredBedrockSkinByUuid.clear();
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
