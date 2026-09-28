package dev.onelsey.bedrockpassport.integration;

import org.geysermc.event.PostOrder;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.event.FloodgateSubscriber;
import org.geysermc.floodgate.api.event.skin.SkinApplyEvent;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
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
    private FloodgateSubscriber<SkinApplyEvent> subscriber;

    public FloodgateSkinPolicy(String configuredPolicy, boolean skinsRestorerPresent, Logger logger) {
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
    }

    public void track(String xuid, UUID javaUuid, String javaName) {
        if (policy == Policy.OFF || xuid == null || javaUuid == null || javaName == null) {
            return;
        }
        purgeExpired();
        managedByXuid.put(xuid, new ManagedIdentity(javaUuid, javaName, System.nanoTime() + TRACK_TTL_NANOS));
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

        managedByXuid.remove(player.getXuid(), managed);
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
        managedByXuid.clear();
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
