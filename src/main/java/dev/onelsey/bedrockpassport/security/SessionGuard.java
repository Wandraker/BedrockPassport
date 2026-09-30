package dev.onelsey.bedrockpassport.security;

import dev.onelsey.bedrockpassport.data.Identity;
import dev.onelsey.bedrockpassport.ui.ChatUi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.geysermc.floodgate.api.FloodgateApi;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.geysermc.floodgate.api.player.FloodgatePlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SessionGuard implements Listener, AutoCloseable {
    private final Object lock = new Object();
    private final boolean enabled;
    private final NameCollisionPolicy nameCollisionPolicy;
    private final String duplicateLoginMessage;
    private final long pendingReservationNanos;
    private final Map<UUID, ActiveSession> activeByUuid = new HashMap<>();
    private final Map<String, ActiveSession> activeBedrockByExactName = new HashMap<>();
    private final Map<String, UUID> activeBedrockByXuid = new HashMap<>();
    private final Map<UUID, PendingBedrock> pendingBedrockByUuid = new HashMap<>();
    private final Map<String, UUID> pendingBedrockByXuid = new HashMap<>();
    private final Map<UUID, PendingJava> pendingJavaByUuid = new HashMap<>();
    private final ScheduledExecutorService cleanupExecutor;
    private volatile boolean initialSynchronizationComplete;
    private volatile boolean closed;

    public SessionGuard(
            Plugin plugin,
            boolean enabled,
            NameCollisionPolicy nameCollisionPolicy,
            String duplicateLoginMessage,
            long pendingReservationSeconds
    ) {
        this.enabled = enabled;
        this.nameCollisionPolicy = Objects.requireNonNull(nameCollisionPolicy, "nameCollisionPolicy");
        this.duplicateLoginMessage = Objects.requireNonNull(duplicateLoginMessage, "duplicateLoginMessage");
        this.pendingReservationNanos = TimeUnit.SECONDS.toNanos(Math.max(5L, pendingReservationSeconds));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        synchronizeOnlinePlayers(plugin);
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BedrockPassport-SessionGuard");
            thread.setDaemon(true);
            return thread;
        });
        this.cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredReservations, 1L, 1L, TimeUnit.SECONDS);
    }

    public boolean enabled() {
        return enabled;
    }

    private void synchronizeOnlinePlayers(Plugin plugin) {
        Player[] players = Bukkit.getOnlinePlayers().toArray(Player[]::new);
        if (players.length == 0) {
            initialSynchronizationComplete = true;
            return;
        }

        initialSynchronizationComplete = false;
        AtomicInteger remaining = new AtomicInteger(players.length);
        for (Player player : players) {
            AtomicBoolean completed = new AtomicBoolean();
            Runnable finish = () -> {
                if (completed.compareAndSet(false, true) && remaining.decrementAndGet() == 0 && !closed) {
                    initialSynchronizationComplete = true;
                }
            };

            ScheduledTask scheduled = player.getScheduler().run(
                    plugin,
                    ignored -> {
                        try {
                            if (!closed) {
                                trackJoin(player);
                            }
                        } finally {
                            finish.run();
                        }
                    },
                    finish
            );
            if (scheduled == null) {
                finish.run();
            }
        }
    }

    public boolean isBedrockXuidBusy(String xuid) {
        if (!enabled || xuid == null) {
            return false;
        }
        if (!initialSynchronizationComplete) {
            return true;
        }
        synchronized (lock) {
            cleanupExpiredLocked(System.nanoTime());
            return activeBedrockByXuid.containsKey(xuid) || pendingBedrockByXuid.containsKey(xuid);
        }
    }

    public ReservationResult reserveForBedrock(Identity identity, String xuid) {
        if (!enabled) {
            return ReservationResult.RESERVED;
        }
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(identity.javaUuid(), "identity.javaUuid");
        Objects.requireNonNull(xuid, "xuid");
        if (!initialSynchronizationComplete) {
            return ReservationResult.IN_USE;
        }

        synchronized (lock) {
            long now = System.nanoTime();
            cleanupExpiredLocked(now);
            String nameKey = nameCollisionPolicy.key(identity.gameName());

            if (activeByUuid.containsKey(identity.javaUuid())
                    || hasActiveNameKeyLocked(nameKey)
                    || pendingBedrockByUuid.containsKey(identity.javaUuid())
                    || hasPendingBedrockNameKeyLocked(nameKey)
                    || pendingJavaByUuid.containsKey(identity.javaUuid())
                    || hasPendingJavaNameKeyLocked(nameKey)
                    || activeBedrockByXuid.containsKey(xuid)
                    || pendingBedrockByXuid.containsKey(xuid)) {
                return ReservationResult.IN_USE;
            }

            PendingBedrock reservation = new PendingBedrock(
                    identity.javaUuid(),
                    identity.gameName(),
                    nameKey,
                    xuid,
                    now + pendingReservationNanos
            );
            pendingBedrockByUuid.put(identity.javaUuid(), reservation);
            pendingBedrockByXuid.put(xuid, identity.javaUuid());
            return ReservationResult.RESERVED;
        }
    }

    public void releaseBedrockReservation(UUID javaUuid, String xuid) {
        if (!enabled || javaUuid == null || xuid == null) {
            return;
        }
        synchronized (lock) {
            PendingBedrock reservation = pendingBedrockByUuid.get(javaUuid);
            if (reservation != null && reservation.xuid().equals(xuid)) {
                pendingBedrockByUuid.remove(javaUuid);
                pendingBedrockByXuid.remove(xuid, javaUuid);
            }
        }
    }

    public void releasePendingBedrockByXuid(String xuid) {
        if (!enabled || xuid == null || xuid.isBlank()) {
            return;
        }
        synchronized (lock) {
            UUID javaUuid = pendingBedrockByXuid.remove(xuid);
            if (javaUuid == null) {
                return;
            }
            PendingBedrock reservation = pendingBedrockByUuid.get(javaUuid);
            if (reservation != null && reservation.xuid().equals(xuid)) {
                pendingBedrockByUuid.remove(javaUuid, reservation);
            }
        }
    }


    public int trackedSessionCount() {
        synchronized (lock) {
            cleanupExpiredLocked(System.nanoTime());
            return activeByUuid.size() + pendingBedrockByUuid.size() + pendingJavaByUuid.size();
        }
    }

    public int pendingAdmissionCount() {
        synchronized (lock) {
            cleanupExpiredLocked(System.nanoTime());
            return pendingBedrockByUuid.size() + pendingJavaByUuid.size();
        }
    }

    public SessionSnapshot findByJavaName(String javaName) {
        if (javaName == null || javaName.isBlank()) {
            return null;
        }
        String key = nameCollisionPolicy.key(javaName);
        synchronized (lock) {
            cleanupExpiredLocked(System.nanoTime());
            for (ActiveSession active : activeByUuid.values()) {
                if (active.nameKey().equals(key)) {
                    return new SessionSnapshot(
                            active.name(),
                            active.uuid(),
                            active.bedrock(),
                            active.xuid(),
                            active.bedrockUsername(),
                            "ONLINE"
                    );
                }
            }
            for (PendingBedrock pending : pendingBedrockByUuid.values()) {
                if (pending.nameKey().equals(key)) {
                    return new SessionSnapshot(
                            pending.name(),
                            pending.uuid(),
                            true,
                            pending.xuid(),
                            null,
                            "BEDROCK_PENDING"
                    );
                }
            }
            for (PendingJava pending : pendingJavaByUuid.values()) {
                if (pending.nameKey().equals(key)) {
                    return new SessionSnapshot(
                            pending.name(),
                            pending.uuid(),
                            false,
                            null,
                            null,
                            "JAVA_PENDING"
                    );
                }
            }
            return null;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLoginProtect(AsyncPlayerPreLoginEvent event) {
        if (!enabled || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        if (!initialSynchronizationComplete) {
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    ChatUi.error("Passport session state is synchronizing. Please reconnect.")
            );
            return;
        }

        String incomingBedrockXuid = floodgateXuid(event.getUniqueId(), event.getName());
        synchronized (lock) {
            cleanupExpiredLocked(System.nanoTime());

            if (incomingBedrockXuid != null) {
                PendingBedrock ownReservation = pendingBedrockByUuid.get(event.getUniqueId());
                if (ownReservation != null && ownReservation.xuid().equals(incomingBedrockXuid)) {
                    return;
                }
                ActiveSession active = activeByUuid.get(event.getUniqueId());
                if (active != null) {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, ChatUi.error(duplicateLoginMessage));
                }
                return;
            }

            ActiveSession active = activeByUuid.get(event.getUniqueId());
            if (active != null && active.bedrock()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, ChatUi.error(duplicateLoginMessage));
                return;
            }

            ActiveSession sameExactName = activeBedrockByExactName.get(event.getName());
            if (sameExactName != null) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, ChatUi.error(duplicateLoginMessage));
                return;
            }

            PendingBedrock pending = pendingBedrockByUuid.get(event.getUniqueId());
            if (pending != null || hasPendingBedrockExactNameLocked(event.getName())) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, ChatUi.error(duplicateLoginMessage));
                return;
            }

            long now = System.nanoTime();
            pendingJavaByUuid.put(
                    event.getUniqueId(),
                    new PendingJava(
                            event.getUniqueId(),
                            event.getName(),
                            nameCollisionPolicy.key(event.getName()),
                            now + pendingReservationNanos
                    )
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLoginObserve(AsyncPlayerPreLoginEvent event) {
        if (!enabled) {
            return;
        }

        String bedrockXuid = floodgateXuid(event.getUniqueId(), event.getName());
        if (bedrockXuid != null) {
            if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                releaseBedrockReservation(event.getUniqueId(), bedrockXuid);
            }
            return;
        }
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            synchronized (lock) {
                pendingJavaByUuid.remove(event.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        trackJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        untrack(event.getPlayer());
    }

    private void trackJoin(Player player) {
        if (closed) {
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        FloodgatePlayer floodgatePlayer = currentFloodgatePlayer(uuid, name);
        boolean bedrock = floodgatePlayer != null;
        String xuid = bedrock ? floodgatePlayer.getXuid() : null;
        String bedrockUsername = bedrock ? floodgatePlayer.getUsername() : null;
        ActiveSession active = new ActiveSession(uuid, name, nameCollisionPolicy.key(name), bedrock, xuid, bedrockUsername, player);

        synchronized (lock) {
            cleanupExpiredLocked(System.nanoTime());
            ActiveSession previous = activeByUuid.put(uuid, active);
            if (previous != null && previous.bedrock()) {
                activeBedrockByExactName.remove(previous.name(), previous);
                if (previous.xuid() != null) {
                    activeBedrockByXuid.remove(previous.xuid(), uuid);
                }
            }
            pendingJavaByUuid.remove(uuid);
            PendingBedrock pendingBedrock = pendingBedrockByUuid.remove(uuid);
            if (pendingBedrock != null) {
                pendingBedrockByXuid.remove(pendingBedrock.xuid(), uuid);
            }
            if (bedrock) {
                activeBedrockByExactName.put(name, active);
                activeBedrockByXuid.put(xuid, uuid);
            }
        }
    }

    private void untrack(Player player) {
        if (closed) {
            return;
        }
        UUID uuid = player.getUniqueId();
        synchronized (lock) {
            ActiveSession active = activeByUuid.get(uuid);
            if (active == null || active.player() != player) {
                return;
            }
            activeByUuid.remove(uuid, active);
            if (!active.bedrock()) {
                return;
            }
            activeBedrockByExactName.remove(active.name(), active);
            if (active.xuid() != null) {
                activeBedrockByXuid.remove(active.xuid(), uuid);
            }
        }
    }

    private String floodgateXuid(UUID uuid, String name) {
        FloodgatePlayer floodgatePlayer = currentFloodgatePlayer(uuid, name);
        return floodgatePlayer == null ? null : floodgatePlayer.getXuid();
    }

    private FloodgatePlayer currentFloodgatePlayer(UUID uuid, String name) {
        for (FloodgatePlayer player : FloodgateApi.getInstance().getPlayers()) {
            if (uuid.equals(player.getCorrectUniqueId()) && name.equals(player.getCorrectUsername())) {
                return player;
            }
        }
        return null;
    }

    private boolean hasActiveNameKeyLocked(String nameKey) {
        for (ActiveSession active : activeByUuid.values()) {
            if (active.nameKey().equals(nameKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingBedrockNameKeyLocked(String nameKey) {
        for (PendingBedrock pending : pendingBedrockByUuid.values()) {
            if (pending.nameKey().equals(nameKey)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingBedrockExactNameLocked(String name) {
        for (PendingBedrock pending : pendingBedrockByUuid.values()) {
            if (pending.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingJavaNameKeyLocked(String nameKey) {
        for (PendingJava pending : pendingJavaByUuid.values()) {
            if (pending.nameKey().equals(nameKey)) {
                return true;
            }
        }
        return false;
    }

    private void cleanupExpiredReservations() {
        if (!enabled || closed) {
            return;
        }
        synchronized (lock) {
            cleanupExpiredLocked(System.nanoTime());
        }
    }

    private void cleanupExpiredLocked(long now) {
        Iterator<Map.Entry<UUID, PendingBedrock>> bedrockIterator = pendingBedrockByUuid.entrySet().iterator();
        while (bedrockIterator.hasNext()) {
            Map.Entry<UUID, PendingBedrock> entry = bedrockIterator.next();
            PendingBedrock pending = entry.getValue();
            if (pending.expiresAtNanos() <= now) {
                bedrockIterator.remove();
                pendingBedrockByXuid.remove(pending.xuid(), entry.getKey());
            }
        }

        Iterator<Map.Entry<UUID, PendingJava>> javaIterator = pendingJavaByUuid.entrySet().iterator();
        while (javaIterator.hasNext()) {
            if (javaIterator.next().getValue().expiresAtNanos() <= now) {
                javaIterator.remove();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        initialSynchronizationComplete = false;
        cleanupExecutor.shutdownNow();
        synchronized (lock) {
            activeByUuid.clear();
            activeBedrockByExactName.clear();
            activeBedrockByXuid.clear();
            pendingBedrockByUuid.clear();
            pendingBedrockByXuid.clear();
            pendingJavaByUuid.clear();
        }
    }

    private record ActiveSession(
            UUID uuid,
            String name,
            String nameKey,
            boolean bedrock,
            String xuid,
            String bedrockUsername,
            Player player
    ) {
    }

    private record PendingBedrock(UUID uuid, String name, String nameKey, String xuid, long expiresAtNanos) {
    }

    private record PendingJava(UUID uuid, String name, String nameKey, long expiresAtNanos) {
    }

    public record SessionSnapshot(
            String javaName,
            UUID javaUuid,
            boolean bedrock,
            String xuid,
            String bedrockUsername,
            String state
    ) {
    }

    public enum ReservationResult {
        RESERVED,
        IN_USE
    }
}
