package dev.onelsey.bedrockpassport.data;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class IdentityRepository implements AutoCloseable {
    private final Connection connection;
    private final ExecutorService executor;

    public IdentityRepository(Path databaseFile) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BedrockPassport-Database");
            thread.setDaemon(true);
            return thread;
        });
        initialize();
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("CREATE TABLE IF NOT EXISTS identities(xuid TEXT PRIMARY KEY, game_name TEXT NOT NULL COLLATE NOCASE UNIQUE, floodgate_uuid TEXT NOT NULL, created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS identities_game_name_idx ON identities(game_name COLLATE NOCASE)");
        }
    }

    public CompletableFuture<Optional<Identity>> findByXuid(String xuid) {
        return submit(() -> findByXuidNow(xuid, true));
    }

    public CompletableFuture<ClaimResult> claim(String xuid, UUID floodgateUuid, String gameName) {
        return submit(() -> claimNow(xuid, floodgateUuid, gameName));
    }

    private Optional<Identity> findByXuidNow(String xuid, boolean touch) throws SQLException {
        Identity identity;
        try (PreparedStatement statement = connection.prepareStatement("SELECT xuid, game_name, floodgate_uuid FROM identities WHERE xuid=?")) {
            statement.setString(1, xuid);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                identity = new Identity(result.getString(1), result.getString(2), UUID.fromString(result.getString(3)));
            }
        }
        if (touch) {
            try (PreparedStatement update = connection.prepareStatement("UPDATE identities SET last_seen=? WHERE xuid=?")) {
                update.setLong(1, System.currentTimeMillis());
                update.setString(2, xuid);
                update.executeUpdate();
            }
        }
        return Optional.of(identity);
    }

    private ClaimResult claimNow(String xuid, UUID floodgateUuid, String gameName) throws SQLException {
        Optional<Identity> existing = findByXuidNow(xuid, false);
        if (existing.isPresent()) {
            return new ClaimResult(ClaimResult.Status.EXISTING, existing.get());
        }

        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO identities(xuid, game_name, floodgate_uuid, created_at, last_seen) VALUES(?,?,?,?,?)")) {
            statement.setString(1, xuid);
            statement.setString(2, gameName);
            statement.setString(3, floodgateUuid.toString());
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
            return new ClaimResult(ClaimResult.Status.CLAIMED, new Identity(xuid, gameName, floodgateUuid));
        } catch (SQLException exception) {
            if (!isConstraintViolation(exception)) {
                throw exception;
            }
            existing = findByXuidNow(xuid, false);
            if (existing.isPresent()) {
                return new ClaimResult(ClaimResult.Status.EXISTING, existing.get());
            }
            return new ClaimResult(ClaimResult.Status.NAME_TAKEN, null);
        }
    }

    private static boolean isConstraintViolation(SQLException exception) {
        return exception.getErrorCode() == 19
                || exception.getMessage() != null && exception.getMessage().toLowerCase().contains("constraint");
    }

    private <T> CompletableFuture<T> submit(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (SQLException exception) {
                throw new DatabaseException(exception);
            }
        }, executor);
    }

    @Override
    public void close() throws Exception {
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
        connection.close();
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    public static final class DatabaseException extends RuntimeException {
        public DatabaseException(SQLException cause) {
            super(cause);
        }
    }
}
