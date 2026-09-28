package dev.onelsey.bedrockpassport.integration;

import dev.onelsey.bedrockpassport.ui.ChatUi;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.Plugin;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.api.player.PropertyKey;
import org.geysermc.floodgate.util.DeviceOs;
import org.geysermc.floodgate.util.InputMode;
import org.geysermc.floodgate.util.LinkedPlayer;
import org.geysermc.floodgate.util.UiProfile;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@SuppressWarnings("deprecation")
public final class UntrustedFloodgateIdentityBridge implements Listener, AutoCloseable {
    private static final AttributeKey<FloodgatePlayer> PLAYER_ATTRIBUTE = AttributeKey.valueOf("floodgate-player");
    private static final long PENDING_TTL_NANOS = TimeUnit.MINUTES.toNanos(2);

    private final FloodgateApi api;
    private final Method addPlayerMethod;
    private final Logger logger;
    private final Map<UUID, PendingIdentity> pendingByJavaUuid = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    public UntrustedFloodgateIdentityBridge(Plugin plugin, Logger logger) {
        this.api = FloodgateApi.getInstance();
        this.logger = logger;
        try {
            this.addPlayerMethod = api.getClass().getMethod("addPlayer", FloodgatePlayer.class);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("Floodgate player replacement capability is unavailable", exception);
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BedrockPassport-UntrustedFloodgateIdentity");
            thread.setDaemon(true);
            return thread;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::purgeExpired, 30L, 30L, TimeUnit.SECONDS);
    }

    public void prepare(Channel channel, String xuid, UUID floodgateUuid, UUID javaUuid, String javaName) {
        if (channel == null || xuid == null || xuid.isBlank() || floodgateUuid == null || javaUuid == null || javaName == null || javaName.isBlank()) {
            throw new IllegalArgumentException("Incomplete Floodgate identity handoff data");
        }
        purgeExpired();
        pendingByJavaUuid.put(
                javaUuid,
                new PendingIdentity(channel, xuid, floodgateUuid, javaUuid, javaName, System.nanoTime() + PENDING_TTL_NANOS)
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        PendingIdentity pending = pendingByJavaUuid.get(event.getUniqueId());
        if (pending == null) {
            return;
        }
        if (!pending.javaName().equals(event.getName())) {
            failClosed(event, pending, "backend identity did not match the selected Passport identity", null);
            return;
        }

        try {
            FloodgatePlayer current = pending.channel().attr(PLAYER_ATTRIBUTE).get();
            if (current == null) {
                throw new IllegalStateException("Floodgate channel player attribute is missing");
            }
            if (!pending.xuid().equals(current.getXuid())) {
                throw new IllegalStateException("Floodgate XUID changed during Passport handoff");
            }
            if (!pending.floodgateUuid().equals(current.getJavaUniqueId())) {
                throw new IllegalStateException("Floodgate transport UUID changed during Passport handoff");
            }
            if (!pending.javaUuid().equals(current.getCorrectUniqueId()) || !pending.javaName().equals(current.getCorrectUsername())) {
                throw new IllegalStateException("Floodgate did not apply the selected Passport identity");
            }

            if (current instanceof PassportFloodgatePlayer passportPlayer) {
                if (!pending.javaUuid().equals(passportPlayer.getCorrectUniqueId()) || !pending.javaName().equals(passportPlayer.getCorrectUsername())) {
                    throw new IllegalStateException("Existing Passport Floodgate view has a different identity");
                }
                pendingByJavaUuid.remove(event.getUniqueId(), pending);
                return;
            }

            PassportFloodgatePlayer replacement = new PassportFloodgatePlayer(current, pending.javaUuid(), pending.javaName());
            FloodgatePlayer previous = replaceApiPlayer(replacement);
            if (previous != null && previous != current) {
                replaceApiPlayer(previous);
                throw new IllegalStateException("Floodgate player storage changed concurrently during Passport handoff");
            }

            pending.channel().attr(PLAYER_ATTRIBUTE).set(replacement);

            FloodgatePlayer resolved = api.getPlayer(pending.javaUuid());
            if (resolved != replacement || resolved.isLinked() || resolved.getLinkedPlayer() != null) {
                pending.channel().attr(PLAYER_ATTRIBUTE).set(current);
                replaceApiPlayer(current);
                throw new IllegalStateException("Floodgate untrusted identity verification failed");
            }

            pendingByJavaUuid.remove(event.getUniqueId(), pending);
        } catch (Throwable throwable) {
            failClosed(event, pending, "could not convert the temporary Floodgate link into an untrusted Passport identity", throwable);
        }
    }

    private FloodgatePlayer replaceApiPlayer(FloodgatePlayer player) throws InvocationTargetException, IllegalAccessException {
        return (FloodgatePlayer) addPlayerMethod.invoke(api, player);
    }

    private void failClosed(AsyncPlayerPreLoginEvent event, PendingIdentity pending, String reason, Throwable throwable) {
        pendingByJavaUuid.remove(event.getUniqueId(), pending);
        if (throwable == null) {
            logger.severe("BedrockPassport blocked login because " + reason + ".");
        } else {
            logger.log(Level.SEVERE, "BedrockPassport blocked login because it " + reason + ".", throwable);
        }
        event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                ChatUi.error("Identity handoff failed safely. Please reconnect.")
        );
    }

    private void purgeExpired() {
        long now = System.nanoTime();
        pendingByJavaUuid.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        cleanupExecutor.shutdownNow();
        pendingByJavaUuid.clear();
    }

    private record PendingIdentity(
            Channel channel,
            String xuid,
            UUID floodgateUuid,
            UUID javaUuid,
            String javaName,
            long expiresAtNanos
    ) {
    }

    private static final class PassportFloodgatePlayer implements FloodgatePlayer {
        private final FloodgatePlayer delegate;
        private final UUID correctUuid;
        private final String correctUsername;

        private PassportFloodgatePlayer(FloodgatePlayer delegate, UUID correctUuid, String correctUsername) {
            this.delegate = delegate;
            this.correctUuid = correctUuid;
            this.correctUsername = correctUsername;
        }

        @Override
        public String getJavaUsername() {
            return delegate.getJavaUsername();
        }

        @Override
        public UUID getJavaUniqueId() {
            return delegate.getJavaUniqueId();
        }

        @Override
        public UUID getCorrectUniqueId() {
            return correctUuid;
        }

        @Override
        public String getCorrectUsername() {
            return correctUsername;
        }

        @Override
        public String getVersion() {
            return delegate.getVersion();
        }

        @Override
        public String getUsername() {
            return delegate.getUsername();
        }

        @Override
        public String getXuid() {
            return delegate.getXuid();
        }

        @Override
        public DeviceOs getDeviceOs() {
            return delegate.getDeviceOs();
        }

        @Override
        public String getLanguageCode() {
            return delegate.getLanguageCode();
        }

        @Override
        public UiProfile getUiProfile() {
            return delegate.getUiProfile();
        }

        @Override
        public InputMode getInputMode() {
            return delegate.getInputMode();
        }

        @Override
        public boolean isFromProxy() {
            return delegate.isFromProxy();
        }

        @Override
        public LinkedPlayer getLinkedPlayer() {
            return null;
        }

        @Override
        public boolean isLinked() {
            return false;
        }

        @Override
        public boolean hasProperty(PropertyKey key) {
            return delegate.hasProperty(key);
        }

        @Override
        public boolean hasProperty(String key) {
            return delegate.hasProperty(key);
        }

        @Override
        public <T> T getProperty(PropertyKey key) {
            return delegate.getProperty(key);
        }

        @Override
        public <T> T getProperty(String key) {
            return delegate.getProperty(key);
        }

        @Override
        public <T> T removeProperty(PropertyKey key) {
            return delegate.removeProperty(key);
        }

        @Override
        public <T> T removeProperty(String key) {
            return delegate.removeProperty(key);
        }

        @Override
        public <T> T addProperty(PropertyKey key, Object value) {
            return delegate.addProperty(key, value);
        }

        @Override
        public <T> T addProperty(String key, Object value) {
            return delegate.addProperty(key, value);
        }
    }
}
