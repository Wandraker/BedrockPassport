package dev.onelsey.bedrockpassport.data;

import dev.onelsey.bedrockpassport.identity.IdentityProviderType;
import dev.onelsey.bedrockpassport.security.NameCollisionPolicy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class IdentityRepository implements AutoCloseable {
    private final Connection connection;
    private final ExecutorService executor;
    private final NameCollisionPolicy nameCollisionPolicy;

    public IdentityRepository(Path databaseFile, NameCollisionPolicy nameCollisionPolicy) throws SQLException {
        this.nameCollisionPolicy = nameCollisionPolicy;
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BedrockPassport-Database");
            thread.setDaemon(true);
            return thread;
        });
        initialize();
        initializeCredentialSchema();
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }

        if (!tableExists("identities")) {
            createCurrentSchema();
            return;
        }

        List<String> columns = tableColumns("identities");
        if (columns.contains("id") && columns.contains("name_key") && columns.contains("java_uuid") && columns.contains("uuid_mode")) {
            if (hasUniqueIndexExactly("name_key")
                    && !hasUniqueIndexExactly("xuid", "name_key")
                    && !hasUniqueIndexExactly("xuid", "provider_type", "name_key")) {
                migrateGlobalNameOwnershipSchema();
            } else if (!columns.contains("provider_type")) {
                addProviderTypeColumn();
            }
            createIndexes();
            rebuildNameKeys();
            return;
        }

        if (columns.contains("id") && columns.contains("java_uuid") && columns.contains("uuid_mode")) {
            migrateLegacyMultiIdentitySchema();
            rebuildNameKeys();
            return;
        }

        if (columns.contains("xuid") && columns.contains("game_name") && columns.contains("floodgate_uuid")) {
            migrateLegacySingleIdentitySchema();
            rebuildNameKeys();
            return;
        }

        throw new SQLException("Unsupported BedrockPassport identities table schema");
    }

    private void initializeCredentialSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS identity_credentials(" +
                    "identity_id INTEGER PRIMARY KEY," +
                    "credential_format TEXT NOT NULL," +
                    "encrypted_blob TEXT NOT NULL," +
                    "updated_at INTEGER NOT NULL," +
                    "FOREIGN KEY(identity_id) REFERENCES identities(id) ON DELETE CASCADE" +
                    ")");
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private List<String> tableColumns(String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                columns.add(result.getString("name"));
            }
        }
        return columns;
    }

    private boolean hasUniqueIndexExactly(String... expectedColumns) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet indexes = statement.executeQuery("PRAGMA index_list(identities)")) {
            while (indexes.next()) {
                if (indexes.getInt("unique") != 1) {
                    continue;
                }
                String indexName = indexes.getString("name");
                List<String> columns = new ArrayList<>();
                try (Statement infoStatement = connection.createStatement(); ResultSet info = infoStatement.executeQuery("PRAGMA index_info('" + indexName.replace("'", "''") + "')")) {
                    while (info.next()) {
                        columns.add(info.getString("name"));
                    }
                }
                if (columns.equals(List.of(expectedColumns))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addProviderTypeColumn() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE identities ADD COLUMN provider_type TEXT NOT NULL DEFAULT 'local'");
        }
    }

    private void migrateGlobalNameOwnershipSchema() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS identities_xuid_last_used_idx");
            statement.execute("DROP INDEX IF EXISTS identities_game_name_idx");
            statement.execute("DROP INDEX IF EXISTS identities_name_key_idx");
            statement.execute("DROP INDEX IF EXISTS identities_xuid_name_key_uq");
            statement.execute("ALTER TABLE identities RENAME TO identities_legacy_global_name_owner");
            createCurrentSchema(statement);
            statement.execute("INSERT INTO identities(id, xuid, provider_type, game_name, name_key, java_uuid, uuid_mode, created_at, last_used) " +
                    "SELECT id, xuid, 'local', game_name, name_key, java_uuid, uuid_mode, created_at, last_used FROM identities_legacy_global_name_owner");
            statement.execute("DROP TABLE identities_legacy_global_name_owner");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void migrateLegacySingleIdentitySchema() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS identities_game_name_idx");
            statement.execute("ALTER TABLE identities RENAME TO identities_legacy_v1");
            createCurrentSchema(statement);
            statement.execute("INSERT INTO identities(xuid, provider_type, game_name, name_key, java_uuid, uuid_mode, created_at, last_used) " +
                    "SELECT xuid, 'local', game_name, '__legacy_v1_' || rowid, NULL, NULL, created_at, last_seen FROM identities_legacy_v1");
            statement.execute("DROP TABLE identities_legacy_v1");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void migrateLegacyMultiIdentitySchema() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS identities_game_name_idx");
            statement.execute("DROP INDEX IF EXISTS identities_xuid_last_used_idx");
            statement.execute("ALTER TABLE identities RENAME TO identities_legacy_v2");
            createCurrentSchema(statement);
            statement.execute("INSERT INTO identities(id, xuid, provider_type, game_name, name_key, java_uuid, uuid_mode, created_at, last_used) " +
                    "SELECT id, xuid, 'local', game_name, '__legacy_v2_' || id, java_uuid, uuid_mode, created_at, last_used FROM identities_legacy_v2");
            statement.execute("DROP TABLE identities_legacy_v2");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void createCurrentSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            createCurrentSchema(statement);
        }
    }

    private void createCurrentSchema(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS identities(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "xuid TEXT NOT NULL," +
                "provider_type TEXT NOT NULL DEFAULT 'local'," +
                "game_name TEXT NOT NULL," +
                "name_key TEXT NOT NULL," +
                "java_uuid TEXT," +
                "uuid_mode TEXT," +
                "created_at INTEGER NOT NULL," +
                "last_used INTEGER NOT NULL" +
                ")");
        createIndexes(statement);
    }

    private void createIndexes() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            createIndexes(statement);
        }
    }

    private void createIndexes(Statement statement) throws SQLException {
        statement.execute("DROP INDEX IF EXISTS identities_xuid_name_key_uq");
        statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS identities_xuid_provider_name_key_uq ON identities(xuid, provider_type, name_key)");
        statement.execute("CREATE INDEX IF NOT EXISTS identities_xuid_last_used_idx ON identities(xuid, last_used DESC, id ASC)");
        statement.execute("CREATE INDEX IF NOT EXISTS identities_name_key_idx ON identities(name_key)");
        statement.execute("CREATE INDEX IF NOT EXISTS identities_game_name_idx ON identities(game_name)");
    }

    private void rebuildNameKeys() throws SQLException {
        Map<Long, String> targets = new HashMap<>();
        Map<String, String> owners = new HashMap<>();
        boolean alreadyCurrent = true;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT id, xuid, provider_type, game_name, name_key FROM identities ORDER BY id")) {
            while (result.next()) {
                long id = result.getLong("id");
                String xuid = result.getString("xuid");
                IdentityProviderType providerType = parseProviderType(result.getString("provider_type"));
                String gameName = result.getString("game_name");
                String target = nameCollisionPolicy.key(gameName);
                String ownershipKey = xuid + '\u0000' + providerType.storageKey() + '\u0000' + target;
                String previous = owners.putIfAbsent(ownershipKey, gameName);
                if (previous != null) {
                    throw new SQLException("BedrockPassport cannot enable " + nameCollisionPolicy.mode() +
                            " name matching because one Passport contains conflicting identities: " + previous + " and " + gameName);
                }
                targets.put(id, target);
                if (!target.equals(result.getString("name_key"))) {
                    alreadyCurrent = false;
                }
            }
        }
        if (alreadyCurrent) {
            return;
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement temporary = connection.prepareStatement("UPDATE identities SET name_key=? WHERE id=?");
             PreparedStatement target = connection.prepareStatement("UPDATE identities SET name_key=? WHERE id=?")) {
            for (long id : targets.keySet()) {
                temporary.setString(1, "__bp_rekey_" + id + "_" + UUID.randomUUID());
                temporary.setLong(2, id);
                temporary.addBatch();
            }
            temporary.executeBatch();
            for (Map.Entry<Long, String> entry : targets.entrySet()) {
                target.setString(1, entry.getValue());
                target.setLong(2, entry.getKey());
                target.addBatch();
            }
            target.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public CompletableFuture<List<Identity>> listByXuid(String xuid) {
        return submit(() -> listByXuidNow(xuid));
    }

    public CompletableFuture<List<Identity>> listByXuid(String xuid, IdentityProviderType providerType) {
        return submit(() -> listByXuidNow(xuid, providerType));
    }

    public CompletableFuture<Optional<StoredCredential>> findCredential(long identityId, String xuid) {
        return submit(() -> findCredentialNow(identityId, xuid));
    }

    public CompletableFuture<Void> saveCredential(
            long identityId,
            String xuid,
            String credentialFormat,
            String encryptedBlob
    ) {
        return submit(() -> {
            saveCredentialNow(identityId, xuid, credentialFormat, encryptedBlob);
            return null;
        });
    }

    public CompletableFuture<ClaimResult> claim(
            String xuid,
            IdentityProviderType providerType,
            String gameName,
            UUID javaUuid,
            String uuidMode,
            int maxAccounts
    ) {
        return submit(() -> claimNow(xuid, providerType, gameName, javaUuid, uuidMode, maxAccounts));
    }

    public CompletableFuture<Identity> updateJavaIdentity(long identityId, String xuid, UUID javaUuid, String uuidMode) {
        return submit(() -> updateJavaIdentityNow(identityId, xuid, javaUuid, uuidMode));
    }

    public CompletableFuture<Identity> markUsed(long identityId, String xuid) {
        return submit(() -> markUsedNow(identityId, xuid));
    }

    public CompletableFuture<Boolean> remove(long identityId, String xuid) {
        return submit(() -> removeNow(identityId, xuid));
    }

    private List<Identity> listByXuidNow(String xuid) throws SQLException {
        List<Identity> identities = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, xuid, provider_type, game_name, java_uuid, uuid_mode, created_at, last_used FROM identities WHERE xuid=? ORDER BY last_used DESC, id ASC")) {
            statement.setString(1, xuid);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    identities.add(readIdentity(result));
                }
            }
        }
        return identities;
    }

    private List<Identity> listByXuidNow(String xuid, IdentityProviderType providerType) throws SQLException {
        List<Identity> identities = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, xuid, provider_type, game_name, java_uuid, uuid_mode, created_at, last_used " +
                        "FROM identities WHERE xuid=? AND provider_type=? ORDER BY last_used DESC, id ASC")) {
            statement.setString(1, xuid);
            statement.setString(2, providerType.storageKey());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    identities.add(readIdentity(result));
                }
            }
        }
        return identities;
    }

    private Optional<StoredCredential> findCredentialNow(long identityId, String xuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT c.identity_id, c.credential_format, c.encrypted_blob, c.updated_at " +
                        "FROM identity_credentials c JOIN identities i ON i.id=c.identity_id " +
                        "WHERE c.identity_id=? AND i.xuid=?")) {
            statement.setLong(1, identityId);
            statement.setString(2, xuid);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredCredential(
                        result.getLong("identity_id"),
                        result.getString("credential_format"),
                        result.getString("encrypted_blob"),
                        result.getLong("updated_at")
                ));
            }
        }
    }

    private void saveCredentialNow(
            long identityId,
            String xuid,
            String credentialFormat,
            String encryptedBlob
    ) throws SQLException {
        if (findByIdNow(identityId, xuid).isEmpty()) {
            throw new SQLException("Identity no longer exists");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO identity_credentials(identity_id, credential_format, encrypted_blob, updated_at) VALUES(?,?,?,?) " +
                        "ON CONFLICT(identity_id) DO UPDATE SET credential_format=excluded.credential_format, " +
                        "encrypted_blob=excluded.encrypted_blob, updated_at=excluded.updated_at")) {
            statement.setLong(1, identityId);
            statement.setString(2, credentialFormat);
            statement.setString(3, encryptedBlob);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private Optional<Identity> findByXuidAndNameNow(String xuid, IdentityProviderType providerType, String gameName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, xuid, provider_type, game_name, java_uuid, uuid_mode, created_at, last_used FROM identities WHERE xuid=? AND provider_type=? AND name_key=?")) {
            statement.setString(1, xuid);
            statement.setString(2, providerType.storageKey());
            statement.setString(3, nameCollisionPolicy.key(gameName));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readIdentity(result)) : Optional.empty();
            }
        }
    }

    private ClaimResult claimNow(
            String xuid,
            IdentityProviderType providerType,
            String gameName,
            UUID javaUuid,
            String uuidMode,
            int maxAccounts
    ) throws SQLException {
        Optional<Identity> existing = findByXuidAndNameNow(xuid, providerType, gameName);
        if (existing.isPresent()) {
            Identity identity = existing.get();
            if (identity.javaUuid() == null || identity.uuidMode() == null || !uuidMode.equals(identity.uuidMode())) {
                identity = updateJavaIdentityNow(identity.id(), xuid, javaUuid, uuidMode);
            }
            return new ClaimResult(ClaimResult.Status.EXISTING, identity);
        }

        if (maxAccounts > 0 && countByXuidNow(xuid, providerType) >= maxAccounts) {
            return new ClaimResult(ClaimResult.Status.LIMIT_REACHED, null);
        }

        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO identities(xuid, provider_type, game_name, name_key, java_uuid, uuid_mode, created_at, last_used) VALUES(?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, xuid);
            statement.setString(2, providerType.storageKey());
            statement.setString(3, gameName);
            statement.setString(4, nameCollisionPolicy.key(gameName));
            statement.setString(5, javaUuid.toString());
            statement.setString(6, uuidMode);
            statement.setLong(7, now);
            statement.setLong(8, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("SQLite did not return the new identity id");
                }
                return new ClaimResult(ClaimResult.Status.CLAIMED,
                        new Identity(keys.getLong(1), xuid, providerType, gameName, javaUuid, uuidMode, now, now));
            }
        } catch (SQLException exception) {
            if (!isConstraintViolation(exception)) {
                throw exception;
            }
            existing = findByXuidAndNameNow(xuid, providerType, gameName);
            if (existing.isPresent()) {
                return new ClaimResult(ClaimResult.Status.EXISTING, existing.get());
            }
            throw exception;
        }
    }

    private int countByXuidNow(String xuid, IdentityProviderType providerType) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM identities WHERE xuid=? AND provider_type=?")) {
            statement.setString(1, xuid);
            statement.setString(2, providerType.storageKey());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private Identity updateJavaIdentityNow(long identityId, String xuid, UUID javaUuid, String uuidMode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE identities SET java_uuid=?, uuid_mode=? WHERE id=? AND xuid=?")) {
            statement.setString(1, javaUuid.toString());
            statement.setString(2, uuidMode);
            statement.setLong(3, identityId);
            statement.setString(4, xuid);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Identity no longer exists");
            }
        }
        return findByIdNow(identityId, xuid).orElseThrow(() -> new SQLException("Identity disappeared after update"));
    }

    private Identity markUsedNow(long identityId, String xuid) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("UPDATE identities SET last_used=? WHERE id=? AND xuid=?")) {
            statement.setLong(1, now);
            statement.setLong(2, identityId);
            statement.setString(3, xuid);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Identity no longer exists");
            }
        }
        return findByIdNow(identityId, xuid).orElseThrow(() -> new SQLException("Identity disappeared after last-used update"));
    }

    private boolean removeNow(long identityId, String xuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM identities WHERE id=? AND xuid=?")) {
            statement.setLong(1, identityId);
            statement.setString(2, xuid);
            return statement.executeUpdate() == 1;
        }
    }

    private Optional<Identity> findByIdNow(long identityId, String xuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, xuid, provider_type, game_name, java_uuid, uuid_mode, created_at, last_used FROM identities WHERE id=? AND xuid=?")) {
            statement.setLong(1, identityId);
            statement.setString(2, xuid);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readIdentity(result)) : Optional.empty();
            }
        }
    }

    private static Identity readIdentity(ResultSet result) throws SQLException {
        String javaUuid = result.getString("java_uuid");
        return new Identity(
                result.getLong("id"),
                result.getString("xuid"),
                parseProviderType(result.getString("provider_type")),
                result.getString("game_name"),
                javaUuid == null ? null : UUID.fromString(javaUuid),
                result.getString("uuid_mode"),
                result.getLong("created_at"),
                result.getLong("last_used")
        );
    }

    private static IdentityProviderType parseProviderType(String value) throws SQLException {
        try {
            return IdentityProviderType.fromStorage(value);
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Unsupported identity provider type in passport.db: " + value, exception);
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
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            throw new DatabaseException(exception);
        }
    }

    public record StoredCredential(
            long identityId,
            String credentialFormat,
            String encryptedBlob,
            long updatedAt
    ) {
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    public static final class DatabaseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DatabaseException(SQLException cause) {
            super(cause);
        }
    }
}
