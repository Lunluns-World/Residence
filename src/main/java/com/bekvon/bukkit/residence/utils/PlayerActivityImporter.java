package com.bekvon.bukkit.residence.utils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.economy.rent.RentedLand;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager;

/**
 * 外部玩家最後上線時間自動匯入器（透過純反射 Soft-Hook 串接 Plan 與 HuskSync API）。
 * 專用於補齊「名下有領地但本機 playerdata 與資料庫 res_player_activity 皆查無最後上線時間」的玩家紀錄，
 * 同時提供公開 Java API 與管理員指令呼叫。
 */
public class PlayerActivityImporter {

    public enum Source {
        ALL,
        PLAN,
        HUSKSYNC;

        public static Source parse(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return ALL;
            }
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "plan":
                return PLAN;
            case "husksync":
            case "husk":
                return HUSKSYNC;
            case "all":
            case "auto":
            default:
                return ALL;
            }
        }
    }

    /**
     * 外部來源查詢介面（方便單元測試注入假資料，執行期由 Plan/HuskSync 反射實作提供）。
     */
    public interface ExternalActivityProvider {
        String getName();

        boolean isAvailable();

        Map<UUID, Long> queryLastSeen(Collection<UUID> uuids);
    }

    public static final class MissingOwnerTarget {
        private final UUID uuid;
        private final String lastKnownName;
        private final List<String> residences;

        public MissingOwnerTarget(UUID uuid, String lastKnownName, List<String> residences) {
            this.uuid = uuid;
            this.lastKnownName = lastKnownName == null ? "" : lastKnownName;
            this.residences = residences == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(residences));
        }

        public UUID getUuid() { return uuid; }
        public String getLastKnownName() { return lastKnownName; }
        public List<String> getResidences() { return residences; }
    }

    public static final class ImportedEntry {
        private final UUID uuid;
        private final String playerName;
        private final long lastSeenMs;
        private final String sourceName;
        private final List<String> residences;

        public ImportedEntry(UUID uuid, String playerName, long lastSeenMs, String sourceName, List<String> residences) {
            this.uuid = uuid;
            this.playerName = playerName == null ? "" : playerName;
            this.lastSeenMs = lastSeenMs;
            this.sourceName = sourceName == null ? "" : sourceName;
            this.residences = residences == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(residences));
        }

        public UUID getUuid() { return uuid; }
        public String getPlayerName() { return playerName; }
        public long getLastSeenMs() { return lastSeenMs; }
        public String getSourceName() { return sourceName; }
        public List<String> getResidences() { return residences; }
    }

    public static final class ImportReport {
        private final boolean dryRun;
        private final boolean planAvailable;
        private final boolean huskSyncAvailable;
        private final int totalScannedMissing;
        private final List<ImportedEntry> imported;
        private final List<MissingOwnerTarget> stillUnknown;
        private final int importedFromPlan;
        private final int importedFromHuskSync;

        public ImportReport(
                boolean dryRun,
                boolean planAvailable,
                boolean huskSyncAvailable,
                int totalScannedMissing,
                List<ImportedEntry> imported,
                List<MissingOwnerTarget> stillUnknown,
                int importedFromPlan,
                int importedFromHuskSync) {
            this.dryRun = dryRun;
            this.planAvailable = planAvailable;
            this.huskSyncAvailable = huskSyncAvailable;
            this.totalScannedMissing = totalScannedMissing;
            this.imported = Collections.unmodifiableList(new ArrayList<>(imported));
            this.stillUnknown = Collections.unmodifiableList(new ArrayList<>(stillUnknown));
            this.importedFromPlan = importedFromPlan;
            this.importedFromHuskSync = importedFromHuskSync;
        }

        public boolean isDryRun() { return dryRun; }
        public boolean isPlanAvailable() { return planAvailable; }
        public boolean isHuskSyncAvailable() { return huskSyncAvailable; }
        public int getTotalScannedMissing() { return totalScannedMissing; }
        public List<ImportedEntry> getImported() { return imported; }
        public List<MissingOwnerTarget> getStillUnknown() { return stillUnknown; }
        public int getImportedFromPlan() { return importedFromPlan; }
        public int getImportedFromHuskSync() { return importedFromHuskSync; }
    }

    private final Residence plugin;
    private final Logger logger;
    private final ExternalActivityProvider planProvider;
    private final ExternalActivityProvider huskSyncProvider;

    public PlayerActivityImporter(Residence plugin) {
        this(plugin,
                plugin != null ? plugin.getLogger() : Logger.getLogger("ResidenceActivityImporter"),
                new PlanActivityProvider(plugin != null ? plugin.getLogger() : Logger.getLogger("ResidenceActivityImporter")),
                new HuskSyncActivityProvider(plugin != null ? plugin.getLogger() : Logger.getLogger("ResidenceActivityImporter")));
    }

    public PlayerActivityImporter(
            Residence plugin,
            Logger logger,
            ExternalActivityProvider planProvider,
            ExternalActivityProvider huskSyncProvider) {
        this.plugin = plugin;
        this.logger = logger != null ? logger : Logger.getLogger("ResidenceActivityImporter");
        this.planProvider = planProvider;
        this.huskSyncProvider = huskSyncProvider;
    }

    /**
     * 非同步掃描名下有領地（含租客）但缺少最後上線時間的玩家，並從 Plan / HuskSync 自動補齊。
     */
    public CompletableFuture<ImportReport> importMissingResidenceOwnersAsync(Source source, boolean dryRun) {
        CompletableFuture<ImportReport> future = new CompletableFuture<>();
        if (plugin == null) {
            future.completeExceptionally(new IllegalStateException("Residence plugin is not available"));
            return future;
        }
        // 在呼叫時先於主執行緒快照領地與本機 OfflinePlayer 狀態
        Map<UUID, MissingOwnerTarget> candidateOwners = collectResidenceOwnersSnapshot();
        Map<UUID, Long> localTimestamps = collectLocalLastSeenSnapshot();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                DatabaseStorageManager db = plugin.getDatabaseStorageManager();
                Map<UUID, Long> dbLastSeen = Collections.emptyMap();
                if (db != null && db.isInitialized()) {
                    dbLastSeen = db.loadAllPlayerLastSeen();
                }

                List<MissingOwnerTarget> missing = new ArrayList<>();
                for (MissingOwnerTarget target : candidateOwners.values()) {
                    long local = localTimestamps.getOrDefault(target.getUuid(), 0L);
                    long dbTs = dbLastSeen.getOrDefault(target.getUuid(), 0L);
                    if (Math.max(local, dbTs) <= 0L) {
                        missing.add(target);
                    }
                }

                ImportReport report = resolveFromProviders(missing, source, dryRun);

                if (!dryRun && !report.getImported().isEmpty() && db != null && db.isInitialized()) {
                    Map<UUID, Long> batchTs = new HashMap<>();
                    Map<UUID, String> batchNames = new HashMap<>();
                    for (ImportedEntry entry : report.getImported()) {
                        batchTs.put(entry.getUuid(), entry.getLastSeenMs());
                        if (!entry.getPlayerName().isEmpty()) {
                            batchNames.put(entry.getUuid(), entry.getPlayerName());
                        }
                    }
                    db.upsertPlayerActivitiesBatch(batchTs, batchNames, plugin.getStorageServerName());
                }
                future.complete(report);
            } catch (Throwable ex) {
                future.completeExceptionally(ex);
            }
        });
        return future;
    }

    /**
     * 純 Java 解析核心：依據指定來源順序查詢並彙整結果（直接供 JUnit 測試）。
     */
    public ImportReport resolveFromProviders(List<MissingOwnerTarget> missingTargets, Source source, boolean dryRun) {
        Source eff = source == null ? Source.ALL : source;
        boolean planReady = (eff == Source.ALL || eff == Source.PLAN) && planProvider != null && planProvider.isAvailable();
        boolean huskReady = (eff == Source.ALL || eff == Source.HUSKSYNC) && huskSyncProvider != null && huskSyncProvider.isAvailable();

        if (missingTargets == null || missingTargets.isEmpty()) {
            return new ImportReport(dryRun, planReady, huskReady, 0, Collections.emptyList(), Collections.emptyList(), 0, 0);
        }

        List<UUID> allMissingUuids = new ArrayList<>(missingTargets.size());
        for (MissingOwnerTarget t : missingTargets) {
            if (t != null && t.getUuid() != null) {
                allMissingUuids.add(t.getUuid());
            }
        }

        Map<UUID, Long> planResults = planReady ? safeQuery(planProvider, allMissingUuids) : Collections.emptyMap();

        // 若選擇 ALL，只針對 Plan 沒查到（或全體需要取最大值時也可只補漏）的再查 HuskSync；此處也取兩者最大值以防萬一
        Map<UUID, Long> huskResults = Collections.emptyMap();
        if (huskReady) {
            List<UUID> huskTargets = new ArrayList<>();
            for (UUID uuid : allMissingUuids) {
                if (eff == Source.HUSKSYNC || !planResults.containsKey(uuid) || planResults.get(uuid) <= 0L) {
                    huskTargets.add(uuid);
                }
            }
            if (!huskTargets.isEmpty()) {
                huskResults = safeQuery(huskSyncProvider, huskTargets);
            }
        }

        List<ImportedEntry> imported = new ArrayList<>();
        List<MissingOwnerTarget> stillUnknown = new ArrayList<>();
        int fromPlan = 0;
        int fromHusk = 0;

        for (MissingOwnerTarget target : missingTargets) {
            if (target == null || target.getUuid() == null) {
                continue;
            }
            long planTs = planResults.getOrDefault(target.getUuid(), 0L);
            long huskTs = huskResults.getOrDefault(target.getUuid(), 0L);
            long best = Math.max(planTs, huskTs);
            if (best > 0L) {
                String src;
                if (planTs >= huskTs && planTs > 0L) {
                    src = "Plan";
                    fromPlan++;
                } else {
                    src = "HuskSync";
                    fromHusk++;
                }
                imported.add(new ImportedEntry(target.getUuid(), target.getLastKnownName(), best, src, target.getResidences()));
            } else {
                stillUnknown.add(target);
            }
        }

        return new ImportReport(dryRun, planReady, huskReady, missingTargets.size(), imported, stillUnknown, fromPlan, fromHusk);
    }

    private Map<UUID, Long> safeQuery(ExternalActivityProvider provider, Collection<UUID> uuids) {
        try {
            Map<UUID, Long> result = provider.queryLastSeen(uuids);
            return result == null ? Collections.emptyMap() : result;
        } catch (Throwable ex) {
            logger.log(Level.WARNING, "查詢外部最後上線來源 [" + provider.getName() + "] 失敗", ex);
            return Collections.emptyMap();
        }
    }

    private Map<UUID, MissingOwnerTarget> collectResidenceOwnersSnapshot() {
        Map<UUID, List<String>> resByOwner = new LinkedHashMap<>();
        Map<UUID, String> namesByOwner = new HashMap<>();
        String serverLand = plugin.getServerLandName();
        String archivedHolder = plugin.getConfigManager().getAutoCleanUserName();

        for (ClaimedResidence res : new ArrayList<>(plugin.getResidenceManager().getResidences().values())) {
            if (res == null || !res.isTopArea()) {
                continue;
            }
            String ownerName = res.getOwner();
            if (ownerName != null
                    && !ownerName.equalsIgnoreCase("server land")
                    && !ownerName.equalsIgnoreCase(serverLand)
                    && (archivedHolder == null || !ownerName.equalsIgnoreCase(archivedHolder))) {
                UUID ownerUuid = res.getOwnerUUID();
                if (ownerUuid != null) {
                    resByOwner.computeIfAbsent(ownerUuid, k -> new ArrayList<>()).add(res.getName());
                    namesByOwner.putIfAbsent(ownerUuid, ownerName);
                }
            }
            collectRenterUuids(res, resByOwner, namesByOwner);
        }

        Map<UUID, MissingOwnerTarget> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<String>> e : resByOwner.entrySet()) {
            out.put(e.getKey(), new MissingOwnerTarget(e.getKey(), namesByOwner.getOrDefault(e.getKey(), ""), e.getValue()));
        }
        return out;
    }

    private void collectRenterUuids(ClaimedResidence res, Map<UUID, List<String>> resByOwner, Map<UUID, String> namesByOwner) {
        if (res.isRented()) {
            RentedLand rl = res.getRentedLand();
            if (rl != null && rl.getUniqueId() != null) {
                resByOwner.computeIfAbsent(rl.getUniqueId(), k -> new ArrayList<>()).add(res.getName() + "(租客)");
                if (rl.player != null) {
                    namesByOwner.putIfAbsent(rl.getUniqueId(), rl.player);
                }
            }
        }
        for (ClaimedResidence sub : res.getSubzones()) {
            collectRenterUuids(sub, resByOwner, namesByOwner);
        }
    }

    private Map<UUID, Long> collectLocalLastSeenSnapshot() {
        Map<UUID, Long> map = new HashMap<>();
        long now = System.currentTimeMillis();
        for (OfflinePlayer off : Bukkit.getOfflinePlayers()) {
            if (off == null || off.getUniqueId() == null) {
                continue;
            }
            long ts = off.isOnline() ? now : off.getLastPlayed();
            if (ts > 0L) {
                map.put(off.getUniqueId(), ts);
            }
        }
        return map;
    }

    /**
     * Plan (Player Analytics) 官方 QueryService 反射查詢提供者。
     * 自動相容 Plan 新版（plan_sessions.user_id JOIN plan_users.id）與舊版（plan_sessions.uuid）。
     */
    public static final class PlanActivityProvider implements ExternalActivityProvider {

        private final Logger logger;

        public PlanActivityProvider(Logger logger) {
            this.logger = logger;
        }

        @Override
        public String getName() {
            return "Plan";
        }

        private Object getQueryService() {
            try {
                Class<?> qsClass = Class.forName("com.djrapitops.plan.query.QueryService");
                Method getInstance = qsClass.getMethod("getInstance");
                return getInstance.invoke(null);
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public boolean isAvailable() {
            return getQueryService() != null;
        }

        @Override
        public Map<UUID, Long> queryLastSeen(Collection<UUID> uuids) {
            if (uuids == null || uuids.isEmpty()) {
                return Collections.emptyMap();
            }
            Object qs = getQueryService();
            if (qs == null) {
                return Collections.emptyMap();
            }
            try {
                Class<?> qsClass = qs.getClass();
                boolean hasSessions = invokeSchemaCheck(qs, qsClass, "doesDBHaveTable", "plan_sessions");
                boolean hasUsers = invokeSchemaCheck(qs, qsClass, "doesDBHaveTable", "plan_users");
                if (!hasSessions && !hasUsers) {
                    return Collections.emptyMap();
                }

                Method queryMethod = findQueryMethod(qsClass);
                if (queryMethod == null) {
                    logger.warning("找不到 Plan QueryService#query(String, ThrowingFunction) 方法。");
                    return Collections.emptyMap();
                }

                boolean sessionsHasUserId = hasSessions && invokeColumnCheck(qs, qsClass, "plan_sessions", "user_id");
                boolean sessionsHasUuid = hasSessions && invokeColumnCheck(qs, qsClass, "plan_sessions", "uuid");
                boolean usersHasRegistered = hasUsers && invokeColumnCheck(qs, qsClass, "plan_users", "registered");

                Map<UUID, Long> result = new HashMap<>();
                for (UUID uuid : uuids) {
                    long best = 0L;
                    if (sessionsHasUserId && hasUsers) {
                        best = Math.max(best, querySingleTimestamp(qs, queryMethod,
                                "SELECT MAX(s.session_end) FROM plan_sessions s INNER JOIN plan_users u ON s.user_id = u.id WHERE u.uuid = ?",
                                uuid.toString()));
                    } else if (sessionsHasUuid) {
                        best = Math.max(best, querySingleTimestamp(qs, queryMethod,
                                "SELECT MAX(session_end) FROM plan_sessions WHERE uuid = ?",
                                uuid.toString()));
                    }
                    if (best <= 0L && usersHasRegistered) {
                        best = Math.max(best, querySingleTimestamp(qs, queryMethod,
                                "SELECT MAX(registered) FROM plan_users WHERE uuid = ?",
                                uuid.toString()));
                    }
                    if (best > 0L) {
                        result.put(uuid, best);
                    }
                }
                return result;
            } catch (Throwable ex) {
                logger.log(Level.WARNING, "透過 Plan QueryService 查詢最後上線時間時發生錯誤", ex);
                return Collections.emptyMap();
            }
        }

        private boolean invokeSchemaCheck(Object qs, Class<?> qsClass, String methodName, String table) {
            try {
                Method m = qsClass.getMethod(methodName, String.class);
                Object r = m.invoke(qs, table);
                return Boolean.TRUE.equals(r);
            } catch (Throwable ignored) {
                return false;
            }
        }

        private boolean invokeColumnCheck(Object qs, Class<?> qsClass, String table, String column) {
            try {
                Method m = qsClass.getMethod("doesDBHaveTableColumn", String.class, String.class);
                Object r = m.invoke(qs, table, column);
                return Boolean.TRUE.equals(r);
            } catch (Throwable ignored) {
                return false;
            }
        }

        private Method findQueryMethod(Class<?> qsClass) {
            for (Method m : qsClass.getMethods()) {
                if (!"query".equals(m.getName())) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 2 && params[0] == String.class && params[1].isInterface()) {
                    return m;
                }
            }
            return null;
        }

        private long querySingleTimestamp(Object qs, Method queryMethod, String sql, String uuidStr) {
            Class<?> funcInterface = queryMethod.getParameterTypes()[1];
            InvocationHandler handler = (proxy, method, args) -> {
                if ("toString".equals(method.getName())) {
                    return "ResidencePlanQueryProxy";
                }
                if (args != null && args.length == 1 && args[0] instanceof PreparedStatement) {
                    PreparedStatement ps = (PreparedStatement) args[0];
                    ps.setString(1, uuidStr);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long val = rs.getLong(1);
                            return rs.wasNull() ? 0L : val;
                        }
                    }
                    return 0L;
                }
                return 0L;
            };
            Object proxy = Proxy.newProxyInstance(funcInterface.getClassLoader(), new Class<?>[] { funcInterface }, handler);
            try {
                Object val = queryMethod.invoke(qs, sql, proxy);
                return val instanceof Number ? ((Number) val).longValue() : 0L;
            } catch (Throwable ex) {
                logger.log(Level.FINE, "Plan 單筆查詢失敗 (" + uuidStr + "): " + ex.getMessage());
                return 0L;
            }
        }
    }

    /**
     * HuskSync 官方 HuskSyncAPI 反射查詢提供者（讀取玩家最新 DataSnapshot 時間戳）。
     */
    public static final class HuskSyncActivityProvider implements ExternalActivityProvider {

        private final Logger logger;

        public HuskSyncActivityProvider(Logger logger) {
            this.logger = logger;
        }

        @Override
        public String getName() {
            return "HuskSync";
        }

        private Object getApi() {
            String[] candidates = new String[] {
                    "net.william278.husksync.api.BukkitHuskSyncAPI",
                    "net.william278.husksync.api.HuskSyncAPI"
            };
            for (String className : candidates) {
                try {
                    Class<?> cls = Class.forName(className);
                    Method getInstance = cls.getMethod("getInstance");
                    Object api = getInstance.invoke(null);
                    if (api != null) {
                        return api;
                    }
                } catch (Throwable ignored) {
                }
            }
            return null;
        }

        @Override
        public boolean isAvailable() {
            return getApi() != null;
        }

        @Override
        public Map<UUID, Long> queryLastSeen(Collection<UUID> uuids) {
            if (uuids == null || uuids.isEmpty()) {
                return Collections.emptyMap();
            }
            Object api = getApi();
            if (api == null) {
                return Collections.emptyMap();
            }
            Map<UUID, Long> result = new HashMap<>();
            Method getUserByUuid = findMethod(api.getClass(), "getUser", UUID.class);
            if (getUserByUuid == null) {
                return result;
            }

            for (UUID uuid : uuids) {
                try {
                    Object userFutureObj = getUserByUuid.invoke(api, uuid);
                    Object user = unwrapFutureOptional(userFutureObj);
                    if (user == null) {
                        continue;
                    }
                    Object snapshot = fetchLatestSnapshot(api, user);
                    long ts = extractTimestampMillis(snapshot);
                    if (ts > 0L) {
                        result.put(uuid, ts);
                    }
                } catch (Throwable ex) {
                    logger.log(Level.FINE, "HuskSync 查詢 " + uuid + " 失敗: " + ex.getMessage());
                }
            }
            return result;
        }

        private Object fetchLatestSnapshot(Object api, Object user) {
            String[] methodNames = new String[] { "getLatestSnapshot", "getCurrentData", "getUserData" };
            for (String name : methodNames) {
                for (Method m : api.getClass().getMethods()) {
                    if (!name.equals(m.getName()) || m.getParameterCount() != 1) {
                        continue;
                    }
                    if (!m.getParameterTypes()[0].isInstance(user)) {
                        continue;
                    }
                    try {
                        Object raw = m.invoke(api, user);
                        Object snapshot = unwrapFutureOptional(raw);
                        if (snapshot != null) {
                            return snapshot;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            return null;
        }

        private Object unwrapFutureOptional(Object obj) throws Exception {
            Object cur = obj;
            if (cur instanceof CompletableFuture<?>) {
                cur = ((CompletableFuture<?>) cur).get(10, TimeUnit.SECONDS);
            }
            if (cur instanceof Optional<?>) {
                return ((Optional<?>) cur).orElse(null);
            }
            return cur;
        }

        private long extractTimestampMillis(Object snapshot) {
            if (snapshot == null) {
                return 0L;
            }
            String[] getters = new String[] { "getTimestamp", "timestamp", "getSavedAt", "getCreatedAt" };
            for (String name : getters) {
                try {
                    Method m = snapshot.getClass().getMethod(name);
                    Object val = m.invoke(snapshot);
                    long converted = toEpochMillis(val);
                    if (converted > 0L) {
                        return converted;
                    }
                } catch (Throwable ignored) {
                }
            }
            return 0L;
        }

        private long toEpochMillis(Object val) {
            if (val == null) {
                return 0L;
            }
            if (val instanceof Number) {
                return ((Number) val).longValue();
            }
            if (val instanceof Instant) {
                return ((Instant) val).toEpochMilli();
            }
            if (val instanceof OffsetDateTime) {
                return ((OffsetDateTime) val).toInstant().toEpochMilli();
            }
            if (val instanceof Date) {
                return ((Date) val).getTime();
            }
            try {
                Method toInstant = val.getClass().getMethod("toInstant");
                Object inst = toInstant.invoke(val);
                if (inst instanceof Instant) {
                    return ((Instant) inst).toEpochMilli();
                }
            } catch (Throwable ignored) {
            }
            return 0L;
        }

        private Method findMethod(Class<?> cls, String name, Class<?>... params) {
            try {
                return cls.getMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }
    }
}
