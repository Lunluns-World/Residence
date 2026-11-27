package com.bekvon.bukkit.residence.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.bekvon.bukkit.residence.storage.DatabaseStorageManager;
import com.bekvon.bukkit.residence.storage.StorageType;

/**
 * 領地操作稽核與還原系統資料庫持久層（支援 SQLite / MariaDB / MySQL / PostgreSQL）。
 */
public class AuditDatabase {

    private final DatabaseStorageManager db;

    public AuditDatabase(DatabaseStorageManager db) {
        this.db = db;
    }

    public DatabaseStorageManager getStorage() {
        return db;
    }

    private String t(String suffix) {
        return db.getTablePrefix() + suffix;
    }

    private boolean isSqlite() {
        return db.getStorageType() == StorageType.SQLITE;
    }

    private boolean isPostgres() {
        return db.getStorageType() == StorageType.POSTGRESQL;
    }

    public void initialize() throws SQLException {
        if (db == null || !db.isInitialized()) {
            return;
        }
        String autoId = isSqlite()
                ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                : (isPostgres() ? "BIGSERIAL PRIMARY KEY" : "BIGINT AUTO_INCREMENT PRIMARY KEY");
        String longText = (db.getStorageType() == StorageType.MYSQL || db.getStorageType() == StorageType.MARIADB)
                ? "LONGTEXT" : "TEXT";

        String table = t("audit_log");
        Runnable ddl = () -> {
            try (Connection conn = db.openConnection(); Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " ("
                        + "id " + autoId + ","
                        + "server_name VARCHAR(64) NOT NULL,"
                        + "created_at BIGINT NOT NULL,"
                        + "actor_uuid VARCHAR(36),"
                        + "actor_name VARCHAR(64) NOT NULL,"
                        + "action_type VARCHAR(24) NOT NULL,"
                        + "res_uuid VARCHAR(36),"
                        + "top_res_key VARCHAR(128) NOT NULL,"
                        + "res_key VARCHAR(192) NOT NULL,"
                        + "res_name VARCHAR(192) NOT NULL,"
                        + "owner_uuid VARCHAR(36),"
                        + "owner_name VARCHAR(64),"
                        + "world_name VARCHAR(128) NOT NULL,"
                        + "min_x INT NOT NULL,"
                        + "min_y INT NOT NULL,"
                        + "min_z INT NOT NULL,"
                        + "max_x INT NOT NULL,"
                        + "max_y INT NOT NULL,"
                        + "max_z INT NOT NULL,"
                        + "summary VARCHAR(512) NOT NULL,"
                        + "before_snapshot " + longText + ","
                        + "after_snapshot " + longText + ","
                        + "rolled_back SMALLINT NOT NULL DEFAULT 0"
                        + ")");

                createIndexSafe(conn, st, "idx_" + table + "_srv_time", table, "server_name, created_at");
                createIndexSafe(conn, st, "idx_" + table + "_spatial", table, "server_name, world_name, max_x, min_x, max_z, min_z");
                createIndexSafe(conn, st, "idx_" + table + "_res", table, "server_name, top_res_key, created_at");
                createIndexSafe(conn, st, "idx_" + table + "_actor", table, "actor_name, created_at");
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        };

        try {
            if (isSqlite()) {
                synchronized (db.getSqliteWriteLock()) {
                    ddl.run();
                }
            } else {
                ddl.run();
            }
        } catch (RuntimeException wrapped) {
            if (wrapped.getCause() instanceof SQLException) {
                throw (SQLException) wrapped.getCause();
            }
            throw wrapped;
        }
    }

    private void createIndexSafe(Connection conn, Statement st, String idxName, String tableName, String columns) {
        try {
            if (isSqlite() || isPostgres()) {
                st.executeUpdate("CREATE INDEX IF NOT EXISTS " + idxName + " ON " + tableName + " (" + columns + ")");
                return;
            }
            if (hasIndex(conn, tableName, idxName)) {
                return;
            }
            st.executeUpdate("CREATE INDEX " + idxName + " ON " + tableName + " (" + columns + ")");
        } catch (SQLException ignored) {
        }
    }

    private boolean hasIndex(Connection conn, String tableName, String idxName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND LOWER(table_name) = LOWER(?) AND LOWER(index_name) = LOWER(?) LIMIT 1")) {
            ps.setString(1, tableName);
            ps.setString(2, idxName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public long insert(AuditRecord rec) throws SQLException {
        if (isSqlite()) {
            synchronized (db.getSqliteWriteLock()) {
                return doInsert(rec);
            }
        }
        return doInsert(rec);
    }

    private long doInsert(AuditRecord rec) throws SQLException {
        String sql = "INSERT INTO " + t("audit_log") + " ("
                + "server_name, created_at, actor_uuid, actor_name, action_type, "
                + "res_uuid, top_res_key, res_key, res_name, owner_uuid, owner_name, "
                + "world_name, min_x, min_y, min_z, max_x, max_y, max_z, "
                + "summary, before_snapshot, after_snapshot, rolled_back"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = db.openConnection();
                PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, rec.serverName);
            ps.setLong(2, rec.createdAt);
            ps.setString(3, rec.actorUuid == null ? null : rec.actorUuid.toString());
            ps.setString(4, truncate(rec.actorName, 64));
            ps.setString(5, rec.action.name());
            ps.setString(6, rec.resUuid == null ? null : rec.resUuid.toString());
            ps.setString(7, truncate(rec.topResKey, 128));
            ps.setString(8, truncate(rec.resKey, 192));
            ps.setString(9, truncate(rec.resName, 192));
            ps.setString(10, rec.ownerUuid == null ? null : rec.ownerUuid.toString());
            ps.setString(11, rec.ownerName == null ? null : truncate(rec.ownerName, 64));
            ps.setString(12, truncate(rec.worldName, 128));
            ps.setInt(13, rec.minX);
            ps.setInt(14, rec.minY);
            ps.setInt(15, rec.minZ);
            ps.setInt(16, rec.maxX);
            ps.setInt(17, rec.maxY);
            ps.setInt(18, rec.maxZ);
            ps.setString(19, truncate(rec.summary, 512));
            ps.setString(20, rec.beforeSnapshot);
            ps.setString(21, rec.afterSnapshot);
            ps.setInt(22, rec.rolledBack ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys != null && keys.next()) {
                    return keys.getLong(1);
                }
            }
            return 0L;
        }
    }

    public int countMatching(AuditQuery query, String localServerName) throws SQLException {
        List<Object> params = new ArrayList<>();
        String where = buildWhereClause(query, localServerName, null, params);
        String sql = "SELECT COUNT(*) FROM " + t("audit_log") + where;
        try (Connection conn = db.openConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * 查詢摘要清單（不載入龐大的 before/after JSON 欄位，降低分頁瀏覽時的 I/O 與記憶體開銷）。
     */
    public List<AuditRecord> querySummaries(AuditQuery query, String localServerName, int limit, int offset) throws SQLException {
        List<Object> params = new ArrayList<>();
        String where = buildWhereClause(query, localServerName, null, params);
        String sql = "SELECT id, server_name, created_at, actor_uuid, actor_name, action_type, "
                + "res_uuid, top_res_key, res_key, res_name, owner_uuid, owner_name, "
                + "world_name, min_x, min_y, min_z, max_x, max_y, max_z, summary, rolled_back "
                + "FROM " + t("audit_log") + where
                + " ORDER BY id DESC LIMIT ? OFFSET ?";
        params.add(Math.max(1, limit));
        params.add(Math.max(0, offset));

        List<AuditRecord> list = new ArrayList<>();
        try (Connection conn = db.openConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs, false));
                }
            }
        }
        return list;
    }

    /**
     * 查詢供 rollback / restore 執行的完整紀錄（含完整快照 JSON）。
     *
     * @param expectedRolledBack rollback 應傳入 false（只挑尚未還原者，依 id DESC 倒序）；restore 應傳入 true（只挑已還原者，依 id ASC 正序）
     */
    public List<AuditRecord> queryForExecution(
            AuditQuery query,
            String localServerName,
            boolean expectedRolledBack,
            boolean ascendingOrder,
            int maxRows) throws SQLException {
        List<Object> params = new ArrayList<>();
        // 執行還原強制限定在當前分流（因為領地實體只存在於當前分流記憶體中）
        AuditQuery scopedQuery = query.allServers
                ? new AuditQuery(query.entryIds, query.actors, query.excludedActors, query.owners,
                        query.actions, query.residences, query.worldName, query.radius, query.centerX, query.centerZ,
                        query.sinceEpochMs, query.untilEpochMs, query.preview, false, query.page)
                : query;
        String where = buildWhereClause(scopedQuery, localServerName, expectedRolledBack, params);
        String sql = "SELECT id, server_name, created_at, actor_uuid, actor_name, action_type, "
                + "res_uuid, top_res_key, res_key, res_name, owner_uuid, owner_name, "
                + "world_name, min_x, min_y, min_z, max_x, max_y, max_z, summary, "
                + "before_snapshot, after_snapshot, rolled_back "
                + "FROM " + t("audit_log") + where
                + " ORDER BY id " + (ascendingOrder ? "ASC" : "DESC")
                + " LIMIT ?";
        params.add(Math.max(1, maxRows));

        List<AuditRecord> list = new ArrayList<>();
        try (Connection conn = db.openConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(readRow(rs, true));
                }
            }
        }
        return list;
    }

    public AuditRecord getById(long id) throws SQLException {
        String sql = "SELECT id, server_name, created_at, actor_uuid, actor_name, action_type, "
                + "res_uuid, top_res_key, res_key, res_name, owner_uuid, owner_name, "
                + "world_name, min_x, min_y, min_z, max_x, max_y, max_z, summary, "
                + "before_snapshot, after_snapshot, rolled_back "
                + "FROM " + t("audit_log") + " WHERE id = ?";
        try (Connection conn = db.openConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs, true) : null;
            }
        }
    }

    public void markRolledBackBatch(Collection<Long> ids, boolean rolledBack) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        if (isSqlite()) {
            synchronized (db.getSqliteWriteLock()) {
                doMarkRolledBackBatch(ids, rolledBack);
            }
        } else {
            doMarkRolledBackBatch(ids, rolledBack);
        }
    }

    private void doMarkRolledBackBatch(Collection<Long> ids, boolean rolledBack) throws SQLException {
        List<Long> idList = new ArrayList<>(ids);
        final int chunkSize = 200;
        try (Connection conn = db.openConnection()) {
            for (int start = 0; start < idList.size(); start += chunkSize) {
                List<Long> sub = idList.subList(start, Math.min(idList.size(), start + chunkSize));
                String placeholders = String.join(",", Collections.nCopies(sub.size(), "?"));
                String sql = "UPDATE " + t("audit_log") + " SET rolled_back = ? WHERE id IN (" + placeholders + ")";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, rolledBack ? 1 : 0);
                    int idx = 2;
                    for (Long id : sub) {
                        ps.setLong(idx++, id);
                    }
                    ps.executeUpdate();
                }
            }
        }
    }

    public int purgeOlderThan(long cutoffEpochMs, String localServerName) throws SQLException {
        if (isSqlite()) {
            synchronized (db.getSqliteWriteLock()) {
                return doPurge(cutoffEpochMs, localServerName);
            }
        }
        return doPurge(cutoffEpochMs, localServerName);
    }

    private int doPurge(long cutoffEpochMs, String localServerName) throws SQLException {
        String sql = localServerName == null
                ? "DELETE FROM " + t("audit_log") + " WHERE created_at < ?"
                : "DELETE FROM " + t("audit_log") + " WHERE server_name = ? AND created_at < ?";
        try (Connection conn = db.openConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            if (localServerName == null) {
                ps.setLong(1, cutoffEpochMs);
            } else {
                ps.setString(1, localServerName);
                ps.setLong(2, cutoffEpochMs);
            }
            return ps.executeUpdate();
        }
    }

    private String buildWhereClause(AuditQuery q, String localServerName, Boolean expectedRolledBack, List<Object> params) {
        List<String> conds = new ArrayList<>();

        if (!q.allServers && localServerName != null && !localServerName.isEmpty()) {
            conds.add("server_name = ?");
            params.add(localServerName);
        }

        if (expectedRolledBack != null) {
            conds.add("rolled_back = ?");
            params.add(expectedRolledBack ? 1 : 0);
        }

        if (!q.entryIds.isEmpty()) {
            String ph = String.join(",", Collections.nCopies(q.entryIds.size(), "?"));
            conds.add("id IN (" + ph + ")");
            params.addAll(q.entryIds);
        }

        if (q.sinceEpochMs != null) {
            conds.add("created_at >= ?");
            params.add(q.sinceEpochMs);
        }

        if (q.untilEpochMs != null) {
            conds.add("created_at <= ?");
            params.add(q.untilEpochMs);
        }

        if (!q.actors.isEmpty()) {
            String ph = String.join(",", Collections.nCopies(q.actors.size(), "?"));
            conds.add("(LOWER(actor_name) IN (" + ph + ") OR LOWER(actor_uuid) IN (" + ph + "))");
            for (String a : q.actors) {
                params.add(a.toLowerCase(Locale.ROOT));
            }
            for (String a : q.actors) {
                params.add(a.toLowerCase(Locale.ROOT));
            }
        }

        if (!q.excludedActors.isEmpty()) {
            String ph = String.join(",", Collections.nCopies(q.excludedActors.size(), "?"));
            conds.add("(LOWER(actor_name) NOT IN (" + ph + ") AND (actor_uuid IS NULL OR LOWER(actor_uuid) NOT IN (" + ph + ")))");
            for (String a : q.excludedActors) {
                params.add(a.toLowerCase(Locale.ROOT));
            }
            for (String a : q.excludedActors) {
                params.add(a.toLowerCase(Locale.ROOT));
            }
        }

        if (!q.owners.isEmpty()) {
            String ph = String.join(",", Collections.nCopies(q.owners.size(), "?"));
            conds.add("(LOWER(owner_name) IN (" + ph + ") OR LOWER(owner_uuid) IN (" + ph + "))");
            for (String o : q.owners) {
                params.add(o.toLowerCase(Locale.ROOT));
            }
            for (String o : q.owners) {
                params.add(o.toLowerCase(Locale.ROOT));
            }
        }

        if (!q.actions.isEmpty()) {
            String ph = String.join(",", Collections.nCopies(q.actions.size(), "?"));
            conds.add("action_type IN (" + ph + ")");
            for (AuditAction act : q.actions) {
                params.add(act.name());
            }
        }

        if (!q.residences.isEmpty()) {
            List<String> resOrs = new ArrayList<>();
            for (String r : q.residences) {
                String clean = r.toLowerCase(Locale.ROOT);
                String escapedPrefix = clean.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + ".%";
                resOrs.add("(LOWER(res_key) = ? OR LOWER(top_res_key) = ? OR LOWER(res_key) LIKE ? ESCAPE '\\')");
                params.add(clean);
                params.add(clean);
                params.add(escapedPrefix);
            }
            conds.add("(" + String.join(" OR ", resOrs) + ")");
        }

        if (q.worldName != null && !q.worldName.isEmpty()) {
            conds.add("LOWER(world_name) = LOWER(?)");
            params.add(q.worldName);
        }

        if (q.radius != null && q.centerX != null && q.centerZ != null) {
            long qMinX = (long) q.centerX - q.radius;
            long qMaxX = (long) q.centerX + q.radius;
            long qMinZ = (long) q.centerZ - q.radius;
            long qMaxZ = (long) q.centerZ + q.radius;
            conds.add("max_x >= ? AND min_x <= ? AND max_z >= ? AND min_z <= ?");
            params.add(clampInt(qMinX));
            params.add(clampInt(qMaxX));
            params.add(clampInt(qMinZ));
            params.add(clampInt(qMaxZ));
        }

        return conds.isEmpty() ? "" : (" WHERE " + String.join(" AND ", conds));
    }

    private static int clampInt(long v) {
        if (v < Integer.MIN_VALUE)
            return Integer.MIN_VALUE;
        if (v > Integer.MAX_VALUE)
            return Integer.MAX_VALUE;
        return (int) v;
    }

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object val = params.get(i);
            int idx = i + 1;
            if (val instanceof Long) {
                ps.setLong(idx, (Long) val);
            } else if (val instanceof Integer) {
                ps.setInt(idx, (Integer) val);
            } else if (val instanceof String) {
                ps.setString(idx, (String) val);
            } else {
                ps.setObject(idx, val);
            }
        }
    }

    private static AuditRecord readRow(ResultSet rs, boolean includeSnapshots) throws SQLException {
        UUID actorUuid = parseUuid(rs.getString("actor_uuid"));
        UUID resUuid = parseUuid(rs.getString("res_uuid"));
        UUID ownerUuid = parseUuid(rs.getString("owner_uuid"));
        AuditAction action = AuditAction.fromDatabase(rs.getString("action_type"));
        if (action == null) {
            action = AuditAction.META;
        }
        String before = includeSnapshots ? rs.getString("before_snapshot") : null;
        String after = includeSnapshots ? rs.getString("after_snapshot") : null;
        return new AuditRecord(
                rs.getLong("id"),
                rs.getString("server_name"),
                rs.getLong("created_at"),
                actorUuid,
                rs.getString("actor_name"),
                action,
                resUuid,
                rs.getString("top_res_key"),
                rs.getString("res_key"),
                rs.getString("res_name"),
                ownerUuid,
                rs.getString("owner_name"),
                rs.getString("world_name"),
                rs.getInt("min_x"),
                rs.getInt("min_y"),
                rs.getInt("min_z"),
                rs.getInt("max_x"),
                rs.getInt("max_y"),
                rs.getInt("max_z"),
                rs.getString("summary"),
                before,
                after,
                rs.getInt("rolled_back") != 0);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
