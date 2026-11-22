package com.bekvon.bukkit.residence.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import com.bekvon.bukkit.residence.crossserver.RemoteResidenceEntry;
import com.bekvon.bukkit.residence.gui.ResidenceGuiMetadata;

public class DatabaseStorageManager {

    public static final class PendingTeleport {
        private final UUID playerUuid;
        private final String targetServer;
        private final String residenceName;
        private final boolean bypassPermission;
        private final long expiresAt;

        public PendingTeleport(UUID playerUuid, String targetServer, String residenceName, boolean bypassPermission, long expiresAt) {
            this.playerUuid = playerUuid;
            this.targetServer = targetServer;
            this.residenceName = residenceName;
            this.bypassPermission = bypassPermission;
            this.expiresAt = expiresAt;
        }

        public UUID getPlayerUuid() {
            return playerUuid;
        }

        public String getTargetServer() {
            return targetServer;
        }

        public String getResidenceName() {
            return residenceName;
        }

        public boolean isBypassPermission() {
            return bypassPermission;
        }

        public long getExpiresAt() {
            return expiresAt;
        }
    }

    public static final class SocialState {
        private final boolean liked;
        private final boolean pinned;

        public SocialState(boolean liked, boolean pinned) {
            this.liked = liked;
            this.pinned = pinned;
        }

        public boolean isLiked() {
            return liked;
        }

        public boolean isPinned() {
            return pinned;
        }
    }

    public static final class ReservationResult {
        private final boolean reserved;
        private final UUID displacedOwner;

        public ReservationResult(boolean reserved, UUID displacedOwner) {
            this.reserved = reserved;
            this.displacedOwner = displacedOwner;
        }

        public boolean isReserved() {
            return reserved;
        }

        public UUID getDisplacedOwner() {
            return displacedOwner;
        }
    }

    private final StorageType storageType;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String tablePrefix;
    private final Object sqliteWriteLock = new Object();
    private volatile Driver resolvedDriver = null;
    private volatile boolean initialized = false;

    public DatabaseStorageManager(
            StorageType storageType,
            File dataFolder,
            String sqliteFile,
            String host,
            int port,
            String database,
            String username,
            String password,
            String tablePrefix,
            String parameters) {
        this.storageType = storageType == null ? StorageType.YAML : storageType;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        String cleanPrefix = tablePrefix == null ? "res_" : tablePrefix.replaceAll("[^a-zA-Z0-9_]", "");
        this.tablePrefix = cleanPrefix.isEmpty() ? "res_" : cleanPrefix;
        this.jdbcUrl = buildJdbcUrl(this.storageType, dataFolder, sqliteFile, host, port, database, parameters);
    }

    private static String buildJdbcUrl(
            StorageType type,
            File dataFolder,
            String sqliteFile,
            String host,
            int port,
            String database,
            String parameters) {
        String cleanParams = parameters == null ? "" : parameters.trim();
        if (cleanParams.startsWith("?"))
            cleanParams = cleanParams.substring(1);

        switch (type) {
        case SQLITE: {
            File dbFile = new File(sqliteFile == null || sqliteFile.trim().isEmpty() ? "Save/residence.db" : sqliteFile);
            if (!dbFile.isAbsolute() && dataFolder != null) {
                dbFile = new File(dataFolder, dbFile.getPath());
            }
            if (dbFile.getParentFile() != null)
                dbFile.getParentFile().mkdirs();
            return "jdbc:sqlite:" + dbFile.getAbsolutePath();
        }
        case MYSQL:
        case MARIADB:
            return "jdbc:mariadb://" + host + ":" + port + "/" + database + (cleanParams.isEmpty() ? "" : "?" + cleanParams);
        case POSTGRESQL: {
            if ("useSSL=false&autoReconnect=true&characterEncoding=utf8".equalsIgnoreCase(cleanParams)) {
                cleanParams = "";
            }
            return "jdbc:postgresql://" + host + ":" + port + "/" + database + (cleanParams.isEmpty() ? "" : "?" + cleanParams);
        }
        default:
            return "";
        }
    }

    public StorageType getStorageType() {
        return storageType;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public void initialize() throws SQLException {
        if (!storageType.isDatabase())
            return;
        loadDriver();
        try (Connection conn = openConnection()) {
            createTables(conn);
        }
        initialized = true;
    }

    private void loadDriver() {
        String[] candidates;
        switch (storageType) {
        case SQLITE:
            candidates = new String[] { "org.sqlite.JDBC" };
            break;
        case MYSQL:
        case MARIADB:
            candidates = new String[] {
                    "com.bekvon.bukkit.residence.libs.mariadb.Driver",
                    "org." + "mariadb.jdbc.Driver"
            };
            break;
        case POSTGRESQL:
            candidates = new String[] {
                    "com.bekvon.bukkit.residence.libs.postgresql.Driver",
                    "org." + "postgresql.Driver"
            };
            break;
        default:
            return;
        }
        for (String driver : candidates) {
            try {
                Class<?> clazz = Class.forName(driver);
                if (Driver.class.isAssignableFrom(clazz)) {
                    resolvedDriver = (Driver) clazz.getDeclaredConstructor().newInstance();
                }
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    public Connection openConnection() throws SQLException {
        Connection conn = null;
        if (storageType != StorageType.SQLITE && resolvedDriver != null) {
            Properties props = new Properties();
            if (!username.isEmpty())
                props.setProperty("user", username);
            if (!password.isEmpty())
                props.setProperty("password", password);
            conn = resolvedDriver.connect(jdbcUrl, props);
        }
        if (conn == null) {
            conn = storageType == StorageType.SQLITE
                    ? DriverManager.getConnection(jdbcUrl)
                    : DriverManager.getConnection(jdbcUrl, username, password);
        }
        if (storageType == StorageType.SQLITE) {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA busy_timeout = 5000");
                st.execute("PRAGMA journal_mode = WAL");
            } catch (SQLException ignored) {
            }
        }
        return conn;
    }

    private void createTables(Connection conn) throws SQLException {
        String longText = (storageType == StorageType.MYSQL || storageType == StorageType.MARIADB) ? "LONGTEXT" : "TEXT";

        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "world_data ("
                    + "server_name VARCHAR(64) NOT NULL,"
                    + "data_type VARCHAR(32) NOT NULL,"
                    + "data_key VARCHAR(128) NOT NULL,"
                    + "payload " + longText + " NOT NULL,"
                    + "updated_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (server_name, data_type, data_key)"
                    + ")");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "residence_index ("
                    + "server_name VARCHAR(64) NOT NULL,"
                    + "res_key VARCHAR(128) NOT NULL,"
                    + "res_name VARCHAR(128) NOT NULL,"
                    + "res_uuid VARCHAR(36),"
                    + "world_name VARCHAR(128) NOT NULL,"
                    + "owner_uuid VARCHAR(36),"
                    + "owner_name VARCHAR(64) NOT NULL,"
                    + "tp_x DOUBLE PRECISION NOT NULL,"
                    + "tp_y DOUBLE PRECISION NOT NULL,"
                    + "tp_z DOUBLE PRECISION NOT NULL,"
                    + "tp_yaw REAL NOT NULL,"
                    + "tp_pitch REAL NOT NULL,"
                    + "hidden SMALLINT NOT NULL,"
                    + "tp_public SMALLINT NOT NULL,"
                    + "listed SMALLINT NOT NULL,"
                    + "alias VARCHAR(128),"
                    + "description VARCHAR(512),"
                    + "icon VARCHAR(64),"
                    + "likes INT NOT NULL DEFAULT 0,"
                    + "create_time BIGINT NOT NULL,"
                    + "updated_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (server_name, res_key)"
                    + ")");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "gui_meta ("
                    + "server_name VARCHAR(64) NOT NULL,"
                    + "res_key VARCHAR(128) NOT NULL,"
                    + "alias VARCHAR(128),"
                    + "description VARCHAR(512),"
                    + "icon VARCHAR(64),"
                    + "listed SMALLINT NOT NULL DEFAULT 1,"
                    + "updated_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (server_name, res_key)"
                    + ")");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "gui_social ("
                    + "player_uuid VARCHAR(36) NOT NULL,"
                    + "server_name VARCHAR(64) NOT NULL,"
                    + "res_key VARCHAR(128) NOT NULL,"
                    + "liked SMALLINT NOT NULL DEFAULT 0,"
                    + "pinned SMALLINT NOT NULL DEFAULT 0,"
                    + "updated_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (player_uuid, server_name, res_key)"
                    + ")");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "quota_locks ("
                    + "owner_uuid VARCHAR(36) NOT NULL PRIMARY KEY)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "quota_reservations ("
                    + "server_name VARCHAR(64) NOT NULL,"
                    + "res_key VARCHAR(128) NOT NULL,"
                    + "owner_uuid VARCHAR(36) NOT NULL,"
                    + "PRIMARY KEY (server_name, res_key))");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + tablePrefix + "cross_teleports ("
                    + "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "target_server VARCHAR(64) NOT NULL,"
                    + "res_name VARCHAR(128) NOT NULL,"
                    + "bypass_perm SMALLINT NOT NULL DEFAULT 0,"
                    + "created_at BIGINT NOT NULL,"
                    + "expires_at BIGINT NOT NULL"
                    + ")");
        }
    }

    private static String normalizeServerKey(String serverName) {
        if (serverName == null || serverName.trim().isEmpty())
            return "default";
        return serverName.trim().toLowerCase();
    }

    private boolean isMySqlFamily() {
        return storageType == StorageType.MYSQL || storageType == StorageType.MARIADB;
    }

    public void saveWorldData(String serverName, String dataType, String dataKey, String payloadYaml) throws SQLException {
        saveDocuments(serverName, Collections.singletonMap(dataType, Collections.singletonMap(dataKey, payloadYaml)));
    }

    public void saveDocuments(String serverName, Map<String, Map<String, String>> documents) throws SQLException {
        long now = System.currentTimeMillis();
        String srvKey = normalizeServerKey(serverName);
        String sql = isMySqlFamily()
                ? "INSERT INTO " + tablePrefix + "world_data (server_name, data_type, data_key, payload, updated_at) VALUES (?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE payload = VALUES(payload), updated_at = VALUES(updated_at)"
                : "INSERT INTO " + tablePrefix + "world_data (server_name, data_type, data_key, payload, updated_at) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (server_name, data_type, data_key) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at";
        synchronized (sqliteWriteLock) {
            try (Connection conn = openConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement statement = conn.prepareStatement(sql)) {
                    for (Entry<String, Map<String, String>> type : documents.entrySet()) {
                        for (Entry<String, String> document : type.getValue().entrySet()) {
                            statement.setString(1, srvKey);
                            statement.setString(2, type.getKey());
                            statement.setString(3, document.getKey());
                            statement.setString(4, document.getValue());
                            statement.setLong(5, now);
                            statement.addBatch();
                        }
                    }
                    statement.executeBatch();
                    conn.commit();
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                }
            }
        }
    }

    public Map<String, Map<String, String>> loadDocuments(String serverName) throws SQLException {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        String sql = "SELECT data_type, data_key, payload FROM " + tablePrefix + "world_data WHERE LOWER(server_name) = ?";
        try (Connection conn = openConnection(); PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, normalizeServerKey(serverName));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next())
                    result.computeIfAbsent(rows.getString("data_type"), key -> new LinkedHashMap<>())
                            .put(rows.getString("data_key"), rows.getString("payload"));
            }
        }
        return result;
    }

    public Map<String, String> loadWorldDataByType(String serverName, String dataType) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        String srvKey = normalizeServerKey(serverName);
        String sql = "SELECT data_key, payload FROM " + tablePrefix + "world_data WHERE LOWER(server_name) = ? AND data_type = ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, srvKey);
            ps.setString(2, dataType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("data_key"), rs.getString("payload"));
                }
            }
        }
        return result;
    }

    public String loadSingleData(String serverName, String dataType, String dataKey) throws SQLException {
        String srvKey = normalizeServerKey(serverName);
        String sql = "SELECT payload FROM " + tablePrefix + "world_data WHERE LOWER(server_name) = ? AND data_type = ? AND data_key = ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, srvKey);
            ps.setString(2, dataType);
            ps.setString(3, dataKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("payload") : null;
            }
        }
    }

    public void syncLocalServerIndex(String serverName, Collection<RemoteResidenceEntry> entries) throws SQLException {
        long now = System.currentTimeMillis();
        String srvKey = normalizeServerKey(serverName);
        synchronized (sqliteWriteLock) {
            try (Connection conn = openConnection()) {
                boolean prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    Set<String> keepKeys = new HashSet<>();
                    String upsertSql = isMySqlFamily()
                            ? "INSERT INTO " + tablePrefix + "residence_index ("
                                    + "server_name, res_key, res_name, res_uuid, world_name, owner_uuid, owner_name, "
                                    + "tp_x, tp_y, tp_z, tp_yaw, tp_pitch, hidden, tp_public, listed, alias, description, icon, likes, create_time, updated_at"
                                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                    + "ON DUPLICATE KEY UPDATE "
                                    + "res_name=VALUES(res_name), res_uuid=VALUES(res_uuid), world_name=VALUES(world_name), "
                                    + "owner_uuid=VALUES(owner_uuid), owner_name=VALUES(owner_name), "
                                    + "tp_x=VALUES(tp_x), tp_y=VALUES(tp_y), tp_z=VALUES(tp_z), tp_yaw=VALUES(tp_yaw), tp_pitch=VALUES(tp_pitch), "
                                    + "hidden=VALUES(hidden), tp_public=VALUES(tp_public), listed=VALUES(listed), "
                                    + "alias=VALUES(alias), description=VALUES(description), icon=VALUES(icon), "
                                    + "likes=VALUES(likes), create_time=VALUES(create_time), updated_at=VALUES(updated_at)"
                            : "INSERT INTO " + tablePrefix + "residence_index ("
                                    + "server_name, res_key, res_name, res_uuid, world_name, owner_uuid, owner_name, "
                                    + "tp_x, tp_y, tp_z, tp_yaw, tp_pitch, hidden, tp_public, listed, alias, description, icon, likes, create_time, updated_at"
                                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                    + "ON CONFLICT (server_name, res_key) DO UPDATE SET "
                                    + "res_name=excluded.res_name, res_uuid=excluded.res_uuid, world_name=excluded.world_name, "
                                    + "owner_uuid=excluded.owner_uuid, owner_name=excluded.owner_name, "
                                    + "tp_x=excluded.tp_x, tp_y=excluded.tp_y, tp_z=excluded.tp_z, tp_yaw=excluded.tp_yaw, tp_pitch=excluded.tp_pitch, "
                                    + "hidden=excluded.hidden, tp_public=excluded.tp_public, listed=excluded.listed, "
                                    + "alias=excluded.alias, description=excluded.description, icon=excluded.icon, "
                                    + "likes=excluded.likes, create_time=excluded.create_time, updated_at=excluded.updated_at";

                    try (PreparedStatement ins = conn.prepareStatement(upsertSql)) {
                        for (RemoteResidenceEntry e : entries) {
                            String resKey = e.getResKey().toLowerCase(Locale.ROOT);
                            keepKeys.add(resKey);
                            ins.setString(1, srvKey);
                            ins.setString(2, resKey);
                            ins.setString(3, e.getResName());
                            ins.setString(4, e.getResUuid() == null ? null : e.getResUuid().toString());
                            ins.setString(5, e.getWorldName());
                            ins.setString(6, e.getOwnerUuid() == null ? null : e.getOwnerUuid().toString());
                            ins.setString(7, e.getOwnerName());
                            ins.setDouble(8, e.getTpX());
                            ins.setDouble(9, e.getTpY());
                            ins.setDouble(10, e.getTpZ());
                            ins.setFloat(11, e.getTpYaw());
                            ins.setFloat(12, e.getTpPitch());
                            ins.setInt(13, e.isHidden() ? 1 : 0);
                            ins.setInt(14, e.isTpPublic() ? 1 : 0);
                            ins.setInt(15, e.isListed() ? 1 : 0);
                            ins.setString(16, e.getAlias());
                            ins.setString(17, e.getDescription());
                            ins.setString(18, e.getIcon());
                            ins.setInt(19, e.getLikes());
                            ins.setLong(20, e.getCreateTime());
                            ins.setLong(21, now);
                            ins.executeUpdate();
                        }
                    }

                    // Remove deleted residences and any legacy non-normalized server_name rows for this server
                    List<String[]> staleRows = new ArrayList<>();
                    try (PreparedStatement sel = conn.prepareStatement("SELECT server_name, res_key FROM " + tablePrefix + "residence_index WHERE LOWER(server_name) = ?")) {
                        sel.setString(1, srvKey);
                        try (ResultSet rs = sel.executeQuery()) {
                            while (rs.next()) {
                                String rowServer = rs.getString("server_name");
                                String k = rs.getString("res_key");
                                if (!srvKey.equals(rowServer) || !keepKeys.contains(k))
                                    staleRows.add(new String[] { rowServer, k });
                            }
                        }
                    }
                    if (!staleRows.isEmpty()) {
                        try (PreparedStatement del = conn.prepareStatement("DELETE FROM " + tablePrefix + "residence_index WHERE server_name = ? AND res_key = ?")) {
                            for (String[] stale : staleRows) {
                                del.setString(1, stale[0]);
                                del.setString(2, stale[1]);
                                del.executeUpdate();
                            }
                        }
                    }

                    try (PreparedStatement clear = conn.prepareStatement("DELETE FROM " + tablePrefix + "quota_reservations "
                            + "WHERE server_name = ? AND res_key = ? AND owner_uuid = ?")) {
                        for (RemoteResidenceEntry entry : entries) {
                            if (entry.getOwnerUuid() == null)
                                continue;
                            clear.setString(1, srvKey);
                            clear.setString(2, entry.getResKey().toLowerCase(Locale.ROOT));
                            clear.setString(3, entry.getOwnerUuid().toString());
                            clear.addBatch();
                        }
                        clear.executeBatch();
                    }
                    conn.commit();
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(prevAuto);
                }
            }
        }
    }

    public List<RemoteResidenceEntry> fetchAllIndexedResidences(String localServerName) throws SQLException {
        List<RemoteResidenceEntry> list = new ArrayList<>();
        String sql = "SELECT server_name, res_key, res_name, res_uuid, world_name, owner_uuid, owner_name, "
                + "tp_x, tp_y, tp_z, tp_yaw, tp_pitch, hidden, tp_public, listed, alias, description, icon, likes, create_time "
                + "FROM " + tablePrefix + "residence_index";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String srv = rs.getString("server_name");
                UUID resUuid = parseUuid(rs.getString("res_uuid"));
                UUID ownerUuid = parseUuid(rs.getString("owner_uuid"));
                list.add(new RemoteResidenceEntry(
                        srv,
                        rs.getString("res_key"),
                        rs.getString("res_name"),
                        resUuid,
                        rs.getString("world_name"),
                        ownerUuid,
                        rs.getString("owner_name"),
                        rs.getDouble("tp_x"),
                        rs.getDouble("tp_y"),
                        rs.getDouble("tp_z"),
                        rs.getFloat("tp_yaw"),
                        rs.getFloat("tp_pitch"),
                        rs.getInt("hidden") != 0,
                        rs.getInt("tp_public") != 0,
                        rs.getInt("listed") != 0,
                        rs.getString("alias"),
                        rs.getString("description"),
                        rs.getString("icon"),
                        rs.getInt("likes"),
                        rs.getLong("create_time"),
                        localServerName != null && localServerName.equalsIgnoreCase(srv)));
            }
        }
        return list;
    }

    public int countRemoteResidencesForOwner(UUID ownerUuid, String excludeServerName) throws SQLException {
        if (ownerUuid == null)
            return 0;
        String excluded = (excludeServerName == null || excludeServerName.trim().isEmpty())
                ? ""
                : normalizeServerKey(excludeServerName);
        String sql = "SELECT COUNT(*) FROM " + tablePrefix + "residence_index WHERE owner_uuid = ? AND LOWER(server_name) <> ?";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, excluded);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public boolean reserveResidence(UUID owner, String serverName, String residenceKey, int limit) throws SQLException {
        return reserveResidenceWithPrevious(owner, serverName, residenceKey, limit).isReserved();
    }

    public ReservationResult reserveResidenceWithPrevious(UUID owner, String serverName, String residenceKey, int limit) throws SQLException {
        if (owner == null || residenceKey == null || limit <= 0)
            return new ReservationResult(false, null);
        String server = normalizeServerKey(serverName);
        String resKey = residenceKey.toLowerCase(Locale.ROOT);
        String ownerId = owner.toString();
        String insertLock = "INSERT INTO " + tablePrefix + "quota_locks (owner_uuid) VALUES (?) "
                + (isMySqlFamily() ? "ON DUPLICATE KEY UPDATE owner_uuid = VALUES(owner_uuid)"
                        : "ON CONFLICT (owner_uuid) DO UPDATE SET owner_uuid = excluded.owner_uuid");
        String count = "SELECT COUNT(*) FROM (SELECT LOWER(server_name) AS node, res_key FROM " + tablePrefix
                + "residence_index WHERE owner_uuid = ? UNION SELECT server_name AS node, res_key FROM "
                + tablePrefix + "quota_reservations WHERE owner_uuid = ?) owned";
        String selectPrev = "SELECT owner_uuid FROM " + tablePrefix + "quota_reservations WHERE server_name = ? AND res_key = ?";
        String upsert = isMySqlFamily()
                ? "INSERT INTO " + tablePrefix + "quota_reservations (server_name, res_key, owner_uuid) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE owner_uuid = VALUES(owner_uuid)"
                : "INSERT INTO " + tablePrefix + "quota_reservations (server_name, res_key, owner_uuid) VALUES (?, ?, ?) "
                        + "ON CONFLICT (server_name, res_key) DO UPDATE SET owner_uuid = excluded.owner_uuid";
        synchronized (sqliteWriteLock) {
            try (Connection conn = openConnection()) {
                conn.setAutoCommit(false);
                try {
                    // The upsert takes the owner's row lock until this reservation commits.
                    try (PreparedStatement lock = conn.prepareStatement(insertLock)) {
                        lock.setString(1, ownerId);
                        lock.executeUpdate();
                    }
                    try (PreparedStatement query = conn.prepareStatement(count)) {
                        query.setString(1, ownerId);
                        query.setString(2, ownerId);
                        try (ResultSet rows = query.executeQuery()) {
                            if (!rows.next() || rows.getInt(1) >= limit) {
                                conn.rollback();
                                return new ReservationResult(false, null);
                            }
                        }
                    }
                    UUID displacedOwner = null;
                    try (PreparedStatement prev = conn.prepareStatement(selectPrev)) {
                        prev.setString(1, server);
                        prev.setString(2, resKey);
                        try (ResultSet rows = prev.executeQuery()) {
                            if (rows.next()) {
                                UUID existing = parseUuid(rows.getString("owner_uuid"));
                                if (existing != null && !existing.equals(owner)) {
                                    displacedOwner = existing;
                                }
                            }
                        }
                    }
                    try (PreparedStatement reserve = conn.prepareStatement(upsert)) {
                        reserve.setString(1, server);
                        reserve.setString(2, resKey);
                        reserve.setString(3, ownerId);
                        reserve.executeUpdate();
                    }
                    conn.commit();
                    return new ReservationResult(true, displacedOwner);
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                }
            }
        }
    }

    public void restoreResidenceReservation(String serverName, String residenceKey, UUID owner) throws SQLException {
        if (owner == null || residenceKey == null)
            return;
        String server = normalizeServerKey(serverName);
        String resKey = residenceKey.toLowerCase(Locale.ROOT);
        String upsert = isMySqlFamily()
                ? "INSERT INTO " + tablePrefix + "quota_reservations (server_name, res_key, owner_uuid) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE owner_uuid = VALUES(owner_uuid)"
                : "INSERT INTO " + tablePrefix + "quota_reservations (server_name, res_key, owner_uuid) VALUES (?, ?, ?) "
                        + "ON CONFLICT (server_name, res_key) DO UPDATE SET owner_uuid = excluded.owner_uuid";
        synchronized (sqliteWriteLock) {
            try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(upsert)) {
                ps.setString(1, server);
                ps.setString(2, resKey);
                ps.setString(3, owner.toString());
                ps.executeUpdate();
            }
        }
    }

    public void releaseResidenceReservation(String serverName, String residenceKey, UUID owner) throws SQLException {
        if (residenceKey == null)
            return;
        String server = normalizeServerKey(serverName);
        String resKey = residenceKey.toLowerCase(Locale.ROOT);
        synchronized (sqliteWriteLock) {
            if (owner == null) {
                try (Connection conn = openConnection(); PreparedStatement delete = conn.prepareStatement("DELETE FROM "
                        + tablePrefix + "quota_reservations WHERE server_name = ? AND res_key = ?")) {
                    delete.setString(1, server);
                    delete.setString(2, resKey);
                    delete.executeUpdate();
                }
                return;
            }
            try (Connection conn = openConnection(); PreparedStatement delete = conn.prepareStatement("DELETE FROM "
                    + tablePrefix + "quota_reservations WHERE server_name = ? AND res_key = ? AND owner_uuid = ?")) {
                delete.setString(1, server);
                delete.setString(2, resKey);
                delete.setString(3, owner.toString());
                delete.executeUpdate();
            }
        }
    }

    public void renameResidenceReservation(String serverName, String oldResidenceKey, String newResidenceKey) throws SQLException {
        if (oldResidenceKey == null || newResidenceKey == null)
            return;
        String server = normalizeServerKey(serverName);
        String oldKey = oldResidenceKey.toLowerCase(Locale.ROOT);
        String newKey = newResidenceKey.toLowerCase(Locale.ROOT);
        if (oldKey.equals(newKey))
            return;
        synchronized (sqliteWriteLock) {
            try (Connection conn = openConnection()) {
                boolean prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM " + tablePrefix
                            + "quota_reservations WHERE server_name = ? AND res_key = ?")) {
                        del.setString(1, server);
                        del.setString(2, newKey);
                        del.executeUpdate();
                    }
                    try (PreparedStatement upd = conn.prepareStatement("UPDATE " + tablePrefix
                            + "quota_reservations SET res_key = ? WHERE server_name = ? AND res_key = ?")) {
                        upd.setString(1, newKey);
                        upd.setString(2, server);
                        upd.setString(3, oldKey);
                        upd.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(prevAuto);
                }
            }
        }
    }

    public void clearResidenceReservations(String serverName) throws SQLException {
        synchronized (sqliteWriteLock) {
            try (Connection conn = openConnection(); PreparedStatement delete = conn.prepareStatement("DELETE FROM "
                    + tablePrefix + "quota_reservations WHERE server_name = ?")) {
                delete.setString(1, normalizeServerKey(serverName));
                delete.executeUpdate();
            }
        }
    }

    public void savePendingTeleport(UUID playerUuid, String targetServer, String residenceName, boolean bypassPerm, long ttlMillis) throws SQLException {
        long now = System.currentTimeMillis();
        long expires = now + Math.max(5000L, ttlMillis);
        synchronized (sqliteWriteLock) {
            try (Connection conn = openConnection()) {
                String sql = isMySqlFamily()
                        ? "INSERT INTO " + tablePrefix + "cross_teleports (player_uuid, target_server, res_name, bypass_perm, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE target_server=VALUES(target_server), res_name=VALUES(res_name), bypass_perm=VALUES(bypass_perm), created_at=VALUES(created_at), expires_at=VALUES(expires_at)"
                        : "INSERT INTO " + tablePrefix + "cross_teleports (player_uuid, target_server, res_name, bypass_perm, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (player_uuid) DO UPDATE SET target_server=excluded.target_server, res_name=excluded.res_name, bypass_perm=excluded.bypass_perm, created_at=excluded.created_at, expires_at=excluded.expires_at";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerUuid.toString());
                    ps.setString(2, targetServer);
                    ps.setString(3, residenceName);
                    ps.setInt(4, bypassPerm ? 1 : 0);
                    ps.setLong(5, now);
                    ps.setLong(6, expires);
                    ps.executeUpdate();
                }
            }
        }
    }

    public PendingTeleport consumePendingTeleport(UUID playerUuid, String currentServer) throws SQLException {
        if (playerUuid == null || currentServer == null)
            return null;
        synchronized (sqliteWriteLock) {
            try (Connection conn = openConnection()) {
                String sql = "SELECT target_server, res_name, bypass_perm, created_at, expires_at FROM "
                        + tablePrefix + "cross_teleports WHERE player_uuid = ? AND LOWER(target_server) = ?";
                PendingTeleport pending;
                long createdAt;
                try (PreparedStatement select = conn.prepareStatement(sql)) {
                    select.setString(1, playerUuid.toString());
                    select.setString(2, normalizeServerKey(currentServer));
                    try (ResultSet rows = select.executeQuery()) {
                        if (!rows.next())
                            return null;
                        createdAt = rows.getLong("created_at");
                        pending = new PendingTeleport(playerUuid, rows.getString("target_server"), rows.getString("res_name"),
                                rows.getInt("bypass_perm") != 0, rows.getLong("expires_at"));
                    }
                }

                try (PreparedStatement remove = conn.prepareStatement("DELETE FROM " + tablePrefix
                        + "cross_teleports WHERE player_uuid = ? AND target_server = ? AND created_at = ? AND expires_at = ? AND res_name = ?")) {
                    remove.setString(1, playerUuid.toString());
                    remove.setString(2, pending.getTargetServer());
                    remove.setLong(3, createdAt);
                    remove.setLong(4, pending.getExpiresAt());
                    remove.setString(5, pending.getResidenceName());
                    if (remove.executeUpdate() != 1)
                        return null;
                }
                return pending.getExpiresAt() >= System.currentTimeMillis() ? pending : null;
            }
        }
    }

    public void saveGuiMetadata(String serverName, String resKey, ResidenceGuiMetadata meta) throws SQLException {
        long now = System.currentTimeMillis();
        String srvKey = normalizeServerKey(serverName);
        String key = resKey.toLowerCase();
        synchronized (sqliteWriteLock) {
            try (Connection conn = openConnection()) {
                String sql = isMySqlFamily()
                        ? "INSERT INTO " + tablePrefix + "gui_meta (server_name, res_key, alias, description, icon, listed, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE alias=VALUES(alias), description=VALUES(description), icon=VALUES(icon), listed=VALUES(listed), updated_at=VALUES(updated_at)"
                        : "INSERT INTO " + tablePrefix + "gui_meta (server_name, res_key, alias, description, icon, listed, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (server_name, res_key) DO UPDATE SET alias=excluded.alias, description=excluded.description, icon=excluded.icon, listed=excluded.listed, updated_at=excluded.updated_at";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, srvKey);
                    ps.setString(2, key);
                    ps.setString(3, meta.getAlias());
                    ps.setString(4, meta.getDescription());
                    ps.setString(5, meta.getIcon());
                    ps.setInt(6, meta.isListed() ? 1 : 0);
                    ps.setLong(7, now);
                    ps.executeUpdate();
                }
            }
        }
    }

    public Map<String, ResidenceGuiMetadata> loadAllGuiMetadata() throws SQLException {
        Map<String, ResidenceGuiMetadata> map = new HashMap<>();
        String sql = "SELECT server_name, res_key, alias, description, icon, listed FROM " + tablePrefix + "gui_meta ORDER BY updated_at ASC";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String qualified = normalizeServerKey(rs.getString("server_name")) + ":" + rs.getString("res_key").toLowerCase();
                map.put(qualified, new ResidenceGuiMetadata(
                        rs.getString("alias"),
                        rs.getString("description"),
                        rs.getString("icon"),
                        rs.getInt("listed") != 0));
            }
        }
        return map;
    }

    public void saveSocialState(UUID playerUuid, String serverName, String resKey, boolean liked, boolean pinned) throws SQLException {
        long now = System.currentTimeMillis();
        String srvKey = normalizeServerKey(serverName);
        String key = resKey.toLowerCase();
        synchronized (sqliteWriteLock) {
            try (Connection conn = openConnection()) {
                if (!liked && !pinned) {
                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM " + tablePrefix + "gui_social WHERE player_uuid=? AND LOWER(server_name)=? AND res_key=?")) {
                        del.setString(1, playerUuid.toString());
                        del.setString(2, srvKey);
                        del.setString(3, key);
                        del.executeUpdate();
                    }
                    return;
                }
                String sql = isMySqlFamily()
                        ? "INSERT INTO " + tablePrefix + "gui_social (player_uuid, server_name, res_key, liked, pinned, updated_at) VALUES (?, ?, ?, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE liked=VALUES(liked), pinned=VALUES(pinned), updated_at=VALUES(updated_at)"
                        : "INSERT INTO " + tablePrefix + "gui_social (player_uuid, server_name, res_key, liked, pinned, updated_at) VALUES (?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (player_uuid, server_name, res_key) DO UPDATE SET liked=excluded.liked, pinned=excluded.pinned, updated_at=excluded.updated_at";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerUuid.toString());
                    ps.setString(2, srvKey);
                    ps.setString(3, key);
                    ps.setInt(4, liked ? 1 : 0);
                    ps.setInt(5, pinned ? 1 : 0);
                    ps.setLong(6, now);
                    ps.executeUpdate();
                }
            }
        }
    }

    public void deleteGuiDataForResidence(String serverName, String resKey) throws SQLException {
        String srvKey = normalizeServerKey(serverName);
        String key = resKey.toLowerCase();
        synchronized (sqliteWriteLock) {
            try (Connection conn = openConnection()) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tablePrefix + "gui_meta WHERE LOWER(server_name)=? AND res_key=?")) {
                    ps.setString(1, srvKey);
                    ps.setString(2, key);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tablePrefix + "gui_social WHERE LOWER(server_name)=? AND res_key=?")) {
                    ps.setString(1, srvKey);
                    ps.setString(2, key);
                    ps.executeUpdate();
                }
            }
        }
    }

    public void renameGuiDataForResidence(String serverName, String oldResKey, String newResKey) throws SQLException {
        String srvKey = normalizeServerKey(serverName);
        String oldKey = oldResKey.toLowerCase();
        String newKey = newResKey.toLowerCase();
        if (oldKey.equals(newKey))
            return;
        synchronized (sqliteWriteLock) {
            try (Connection conn = openConnection()) {
                boolean prevAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tablePrefix + "gui_meta WHERE LOWER(server_name)=? AND res_key=?")) {
                        ps.setString(1, srvKey);
                        ps.setString(2, newKey);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE " + tablePrefix + "gui_meta SET server_name=?, res_key=? WHERE LOWER(server_name)=? AND res_key=?")) {
                        ps.setString(1, srvKey);
                        ps.setString(2, newKey);
                        ps.setString(3, srvKey);
                        ps.setString(4, oldKey);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tablePrefix + "gui_social WHERE LOWER(server_name)=? AND res_key=?")) {
                        ps.setString(1, srvKey);
                        ps.setString(2, newKey);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE " + tablePrefix + "gui_social SET server_name=?, res_key=? WHERE LOWER(server_name)=? AND res_key=?")) {
                        ps.setString(1, srvKey);
                        ps.setString(2, newKey);
                        ps.setString(3, srvKey);
                        ps.setString(4, oldKey);
                        ps.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(prevAuto);
                }
            }
        }
    }

    public Map<UUID, Map<String, SocialState>> loadAllSocialStates() throws SQLException {
        Map<UUID, Map<String, SocialState>> map = new HashMap<>();
        String sql = "SELECT player_uuid, server_name, res_key, liked, pinned FROM " + tablePrefix + "gui_social ORDER BY updated_at ASC";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID uuid = parseUuid(rs.getString("player_uuid"));
                if (uuid == null)
                    continue;
                String qualified = normalizeServerKey(rs.getString("server_name")) + ":" + rs.getString("res_key").toLowerCase();
                map.computeIfAbsent(uuid, k -> new HashMap<>()).put(qualified,
                        new SocialState(rs.getInt("liked") != 0, rs.getInt("pinned") != 0));
            }
        }
        return map;
    }

    public Map<String, Integer> loadLikeCounts() throws SQLException {
        Map<String, Integer> counts = new HashMap<>();
        String sql = "SELECT LOWER(server_name) AS srv_key, res_key, COUNT(DISTINCT player_uuid) AS cnt FROM " + tablePrefix + "gui_social WHERE liked <> 0 GROUP BY LOWER(server_name), res_key";
        try (Connection conn = openConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String qualified = normalizeServerKey(rs.getString("srv_key")) + ":" + rs.getString("res_key").toLowerCase();
                counts.put(qualified, rs.getInt("cnt"));
            }
        }
        return counts;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.trim().isEmpty())
            return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
