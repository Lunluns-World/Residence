package com.bekvon.bukkit.residence.audit;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.audit.ResidenceSnapshot.Box;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.event.ResidenceAreaAddEvent;
import com.bekvon.bukkit.residence.event.ResidenceAreaDeleteEvent;
import com.bekvon.bukkit.residence.event.ResidenceFlagChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceOwnerChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceRenameEvent;
import com.bekvon.bukkit.residence.event.ResidenceSizeChangeEvent;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.ResidenceAuditBridge;
import com.bekvon.bukkit.residence.protection.ResidenceAuditBridge.ApplyOutcome;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager;
import com.bekvon.bukkit.residence.storage.StorageType;

import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.Container.PageInfo;
import net.Zrips.CMILib.RawMessages.RawMessage;

/**
 * 領地操作紀錄與還原管理器（類 CoreProtect 的稽核查詢、單筆或批次還原／重做、半徑內刪除領地恢復、方塊點擊檢查器）。
 */
public class AuditManager implements Listener {

    private static final int LOOKUP_PAGE_SIZE = 8;
    private static final int MAX_BATCH_EXECUTION_ROWS = 2000;

    private static final ThreadLocal<CommandSender> ACTIVE_COMMAND_SENDER = new ThreadLocal<>();

    private static final class PendingMutation {
        final String key;
        final UUID actorUuid;
        final String actorName;
        final AuditAction action;
        final UUID topResUuid;
        final String initialTopResKey;
        final String targetResKey;
        final String targetResName;
        final String relativePath;
        final ResidenceSnapshot beforeTop;

        PendingMutation(
                String key,
                UUID actorUuid,
                String actorName,
                AuditAction action,
                UUID topResUuid,
                String initialTopResKey,
                String targetResKey,
                String targetResName,
                String relativePath,
                ResidenceSnapshot beforeTop) {
            this.key = key;
            this.actorUuid = actorUuid;
            this.actorName = actorName;
            this.action = action;
            this.topResUuid = topResUuid;
            this.initialTopResKey = initialTopResKey;
            this.targetResKey = targetResKey;
            this.targetResName = targetResName;
            this.relativePath = relativePath;
            this.beforeTop = beforeTop;
        }
    }

    private final Residence plugin;
    private DatabaseStorageManager fallbackSqliteDb = null;
    private volatile AuditDatabase auditDb = null;
    private ExecutorService writerExecutor = null;

    private final AtomicBoolean applyingAuditOnMain = new AtomicBoolean(false);
    private final ThreadLocal<Integer> suspendDepth = ThreadLocal.withInitial(() -> 0);
    private final Map<String, PendingMutation> pendingMutations = new ConcurrentHashMap<>();
    private final Set<UUID> activeInspectors = ConcurrentHashMap.newKeySet();

    public AuditManager(Residence plugin) {
        this.plugin = plugin;
    }

    public static <T> T withActor(CommandSender sender, Supplier<T> action) {
        CommandSender prev = ACTIVE_COMMAND_SENDER.get();
        ACTIVE_COMMAND_SENDER.set(sender);
        try {
            return action.get();
        } finally {
            if (prev == null) {
                ACTIVE_COMMAND_SENDER.remove();
            } else {
                ACTIVE_COMMAND_SENDER.set(prev);
            }
        }
    }

    public static void withActorRun(CommandSender sender, Runnable action) {
        withActor(sender, () -> {
            action.run();
            return null;
        });
    }

    public synchronized void start() {
        stop();
        try {
            DatabaseStorageManager storage = plugin.getDatabaseStorageManager();
            if (storage == null || !storage.isInitialized()) {
                fallbackSqliteDb = new DatabaseStorageManager(
                        StorageType.SQLITE,
                        plugin.getDataFolder(),
                        "Save/residence_audit.db",
                        "",
                        0,
                        "",
                        "",
                        "",
                        "res_",
                        "");
                fallbackSqliteDb.initialize();
                storage = fallbackSqliteDb;
            }
            AuditDatabase db = new AuditDatabase(storage);
            db.initialize();
            this.auditDb = db;
            this.writerExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Residence-Audit-Writer");
                t.setDaemon(true);
                return t;
            });
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "[Audit] Failed to initialize Residence Audit storage", ex);
            this.auditDb = null;
        }
    }

    public synchronized void stop() {
        for (String key : new ArrayList<>(pendingMutations.keySet())) {
            try {
                flushPendingMutation(key);
            } catch (Throwable ignored) {
            }
        }
        pendingMutations.clear();
        activeInspectors.clear();
        if (writerExecutor != null) {
            writerExecutor.shutdown();
            try {
                writerExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            writerExecutor = null;
        }
        auditDb = null;
        fallbackSqliteDb = null;
    }

    public boolean isReady() {
        return auditDb != null;
    }

    public AuditDatabase getDatabase() {
        return auditDb;
    }

    public void runSuspended(Runnable action) {
        suspendDepth.set(suspendDepth.get() + 1);
        try {
            action.run();
        } finally {
            int next = suspendDepth.get() - 1;
            if (next <= 0) {
                suspendDepth.remove();
            } else {
                suspendDepth.set(next);
            }
        }
    }

    public boolean isRecordingSuppressed() {
        return auditDb == null || applyingAuditOnMain.get() || suspendDepth.get() > 0;
    }

    public boolean toggleInspector(UUID playerUuid) {
        if (activeInspectors.contains(playerUuid)) {
            activeInspectors.remove(playerUuid);
            return false;
        }
        activeInspectors.add(playerUuid);
        return true;
    }

    public boolean isInspecting(UUID playerUuid) {
        return playerUuid != null && activeInspectors.contains(playerUuid);
    }

    // ==================== 操作紀錄擷取入口 ====================

    public void recordCreate(CommandSender explicitActor, ClaimedResidence createdRes) {
        if (isRecordingSuppressed() || createdRes == null) {
            return;
        }
        try {
            ClaimedResidence top = createdRes.getTopParent();
            ResidenceSnapshot afterTop = ResidenceAuditBridge.captureTopSnapshot(plugin, top, false);
            if (afterTop == null) {
                return;
            }
            AuditAction action = createdRes.isTopArea() ? AuditAction.CREATE : AuditAction.SUBZONE_CREATE;
            String topKey = top.getName().toLowerCase(Locale.ROOT);
            String resKey = createdRes.getName().toLowerCase(Locale.ROOT);
            String relPath = computeRelativePath(topKey, resKey);
            Box env = ResidenceAuditBridge.captureNodeEnvelope(createdRes);
            ActorIdentity actor = resolveActor(explicitActor, null);
            String summary = ResidenceSnapshot.summarize(action, null, afterTop, relPath);

            AuditRecord rec = new AuditRecord(
                    0L,
                    plugin.getStorageServerName(),
                    System.currentTimeMillis(),
                    actor.uuid,
                    actor.name,
                    action,
                    top.getUniqueId(),
                    topKey,
                    resKey,
                    createdRes.getName(),
                    createdRes.getOwnerUUID(),
                    createdRes.getOwner(),
                    createdRes.getPermissions().getWorldName(),
                    env.minX, env.minY, env.minZ,
                    env.maxX, env.maxY, env.maxZ,
                    summary,
                    null,
                    afterTop.toJson(),
                    false);
            enqueueInsert(rec);
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.FINE, "[Audit] Failed to capture CREATE snapshot", ex);
        }
    }

    public void recordRemoveBeforeDelete(CommandSender explicitActor, ClaimedResidence removingRes, boolean moneyRefunded, String systemTag) {
        if (isRecordingSuppressed() || removingRes == null) {
            return;
        }
        try {
            ClaimedResidence top = removingRes.getTopParent();
            ResidenceSnapshot beforeTop = ResidenceAuditBridge.captureTopSnapshot(plugin, top, moneyRefunded);
            if (beforeTop == null) {
                return;
            }
            AuditAction action = removingRes.isTopArea() ? AuditAction.REMOVE : AuditAction.SUBZONE_REMOVE;
            String topKey = top.getName().toLowerCase(Locale.ROOT);
            String resKey = removingRes.getName().toLowerCase(Locale.ROOT);
            String relPath = computeRelativePath(topKey, resKey);
            Box env = ResidenceAuditBridge.captureNodeEnvelope(removingRes);
            ActorIdentity actor = resolveActor(explicitActor, systemTag);
            String summary = ResidenceSnapshot.summarize(action, beforeTop, null, relPath);

            // 若刪除整個主領地，先捨棄該主領地同 tick 尚未 flush 的變更，避免多餘紀錄
            if (removingRes.isTopArea() && top.getUniqueId() != null) {
                String prefix = top.getUniqueId().toString() + "|";
                pendingMutations.keySet().removeIf(k -> k.startsWith(prefix));
            }

            AuditRecord rec = new AuditRecord(
                    0L,
                    plugin.getStorageServerName(),
                    System.currentTimeMillis(),
                    actor.uuid,
                    actor.name,
                    action,
                    top.getUniqueId(),
                    topKey,
                    resKey,
                    removingRes.getName(),
                    removingRes.getOwnerUUID(),
                    removingRes.getOwner(),
                    removingRes.getPermissions().getWorldName(),
                    env.minX, env.minY, env.minZ,
                    env.maxX, env.maxY, env.maxZ,
                    summary,
                    beforeTop.toJson(),
                    null,
                    false);
            enqueueInsert(rec);
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.FINE, "[Audit] Failed to capture REMOVE snapshot", ex);
        }
    }

    public void captureBeforeMutation(CommandSender explicitActor, ClaimedResidence targetNode, AuditAction action) {
        captureBeforeMutation(explicitActor, targetNode, action, null);
    }

    public void captureBeforeMutation(CommandSender explicitActor, ClaimedResidence targetNode, AuditAction action, String systemTag) {
        if (isRecordingSuppressed() || targetNode == null || action == null) {
            return;
        }
        if (targetNode.getPermissions() != null && targetNode.getPermissions().isEventCallsSuspended()) {
            return;
        }
        try {
            ClaimedResidence top = targetNode.getTopParent();
            if (top == null || top.getUniqueId() == null) {
                return;
            }
            String topKey = top.getName().toLowerCase(Locale.ROOT);
            String resKey = targetNode.getName().toLowerCase(Locale.ROOT);
            String relPath = computeRelativePath(topKey, resKey);
            String dedupeKey = top.getUniqueId() + "|" + action.name() + "|" + relPath;
            if (pendingMutations.containsKey(dedupeKey)) {
                return;
            }

            ResidenceSnapshot beforeTop = ResidenceAuditBridge.captureTopSnapshot(plugin, top, false);
            if (beforeTop == null) {
                return;
            }
            ActorIdentity actor = resolveActor(explicitActor, systemTag);
            PendingMutation pending = new PendingMutation(
                    dedupeKey,
                    actor.uuid,
                    actor.name,
                    action,
                    top.getUniqueId(),
                    topKey,
                    resKey,
                    targetNode.getName(),
                    relPath,
                    beforeTop);
            if (pendingMutations.putIfAbsent(dedupeKey, pending) == null) {
                if (plugin.isEnabled()) {
                    Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> flushPendingMutation(dedupeKey), 1L);
                } else {
                    flushPendingMutation(dedupeKey);
                }
            }
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.FINE, "[Audit] Failed to capture pre-mutation snapshot", ex);
        }
    }

    private void flushPendingMutation(String dedupeKey) {
        PendingMutation p = pendingMutations.remove(dedupeKey);
        if (p == null || auditDb == null) {
            return;
        }
        try {
            ClaimedResidence liveTop = plugin.getResidenceManager().getByUUID(p.topResUuid);
            if (liveTop == null) {
                liveTop = plugin.getResidenceManager().getByName(p.initialTopResKey);
            }
            if (liveTop == null) {
                return;
            }
            liveTop = liveTop.getTopParent();

            ResidenceSnapshot afterTop = ResidenceAuditBridge.captureTopSnapshot(plugin, liveTop, false);
            if (afterTop == null) {
                return;
            }

            if (!hasEffectiveDiff(p.action, p.beforeTop, afterTop, p.relativePath)) {
                return;
            }

            ClaimedResidence liveNode = p.relativePath.isEmpty() ? liveTop : liveTop.getSubzone(p.relativePath);
            if (liveNode == null) {
                liveNode = liveTop;
            }
            Box env = ResidenceAuditBridge.captureNodeEnvelope(liveNode);
            String liveTopKey = liveTop.getName().toLowerCase(Locale.ROOT);
            String liveResKey = liveNode.getName().toLowerCase(Locale.ROOT);
            String summary = ResidenceSnapshot.summarize(p.action, p.beforeTop, afterTop, p.relativePath);

            AuditRecord rec = new AuditRecord(
                    0L,
                    plugin.getStorageServerName(),
                    System.currentTimeMillis(),
                    p.actorUuid,
                    p.actorName,
                    p.action,
                    liveTop.getUniqueId(),
                    liveTopKey,
                    liveResKey,
                    liveNode.getName(),
                    liveNode.getOwnerUUID(),
                    liveNode.getOwner(),
                    liveNode.getPermissions().getWorldName(),
                    env.minX, env.minY, env.minZ,
                    env.maxX, env.maxY, env.maxZ,
                    summary,
                    p.beforeTop.toJson(),
                    afterTop.toJson(),
                    false);
            enqueueInsert(rec);
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.FINE, "[Audit] Failed to flush mutation snapshot", ex);
        }
    }

    private static boolean hasEffectiveDiff(AuditAction action, ResidenceSnapshot beforeTop, ResidenceSnapshot afterTop, String relPath) {
        switch (action) {
        case FLAG:
            return beforeTop.hasPermissionDiffRecursive(afterTop);
        case OWNER:
            return beforeTop.hasOwnerDiffRecursive(afterTop);
        case AREA: {
            ResidenceSnapshot b = beforeTop.findNode(relPath);
            ResidenceSnapshot a = afterTop.findNode(relPath);
            return b == null || a == null || b.hasAreaDiff(a);
        }
        case RENAME: {
            if (relPath == null || relPath.isEmpty()) {
                return !String.valueOf(beforeTop.resName).equals(String.valueOf(afterTop.resName));
            }
            int dot = relPath.lastIndexOf('.');
            String parentRel = dot >= 0 ? relPath.substring(0, dot) : "";
            ResidenceSnapshot pb = beforeTop.findNode(parentRel);
            ResidenceSnapshot pa = afterTop.findNode(parentRel);
            return pb == null || pa == null || !pb.subzones.keySet().equals(pa.subzones.keySet());
        }
        case META: {
            ResidenceSnapshot b = beforeTop.findNode(relPath);
            ResidenceSnapshot a = afterTop.findNode(relPath);
            return b == null || a == null || b.hasMetaDiff(a);
        }
        default:
            return true;
        }
    }

    private void enqueueInsert(AuditRecord record) {
        AuditDatabase db = this.auditDb;
        ExecutorService exec = this.writerExecutor;
        if (db == null || record == null) {
            return;
        }
        Runnable task = () -> {
            try {
                db.insert(record);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Audit] Failed to write audit record for " + record.resName, ex);
            }
        };
        if (exec != null && !exec.isShutdown()) {
            exec.submit(task);
        } else {
            task.run();
        }
    }

    // ==================== 事件監聽 ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlagChange(ResidenceFlagChangeEvent event) {
        captureBeforeMutation(event.getPlayer(), event.getResidence(), AuditAction.FLAG);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerChange(ResidenceOwnerChangeEvent event) {
        captureBeforeMutation(null, event.getResidence(), AuditAction.OWNER);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAreaAdd(ResidenceAreaAddEvent event) {
        // 僅在該領地已註冊於 ResidenceManager（排除新建領地尚未 commit 階段）時記錄 AREA 變更
        ClaimedResidence res = event.getResidence();
        if (res != null && plugin.getResidenceManager().getByUUID(res.getTopParent().getUniqueId()) != null) {
            captureBeforeMutation(event.getPlayer(), res, AuditAction.AREA);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAreaDelete(ResidenceAreaDeleteEvent event) {
        ClaimedResidence res = event.getResidence();
        if (res != null && plugin.getResidenceManager().getByUUID(res.getTopParent().getUniqueId()) != null) {
            captureBeforeMutation(event.getPlayer(), res, AuditAction.AREA);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSizeChange(ResidenceSizeChangeEvent event) {
        captureBeforeMutation(event.getPlayer(), event.getResidence(), AuditAction.AREA);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRename(ResidenceRenameEvent event) {
        captureBeforeMutation(null, event.getResidence(), AuditAction.RENAME);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInspectorInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!isInspecting(player.getUniqueId())) {
            return;
        }
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action act = event.getAction();
        if (act != Action.LEFT_CLICK_BLOCK && act != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        event.setCancelled(true);

        int bx = clicked.getX();
        int by = clicked.getY();
        int bz = clicked.getZ();
        String worldName = clicked.getWorld().getName();

        AuditQuery spatialQuery = new AuditQuery(
                null, null, null, null, null, null,
                worldName, 0, bx, bz, null, null, false, false, 1);

        AuditDatabase db = this.auditDb;
        if (db == null) {
            lm.Audit_NotReady.sendMessage(player);
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                int total = db.countMatching(spatialQuery, plugin.getStorageServerName());
                List<AuditRecord> rows = total > 0
                        ? db.querySummaries(spatialQuery, plugin.getStorageServerName(), LOOKUP_PAGE_SIZE, 0)
                        : java.util.Collections.emptyList();
                runOnSender(player, () -> {
                    lm.Audit_Inspect_BlockHeader.sendMessage(player, worldName, bx, by, bz);
                    if (rows.isEmpty()) {
                        lm.Audit_NoResults.sendMessage(player);
                        return;
                    }
                    for (AuditRecord r : rows) {
                        sendLookupRow(player, r);
                    }
                });
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Audit] Inspector query failed", ex);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        activeInspectors.remove(event.getPlayer().getUniqueId());
    }

    // ==================== 查詢、單筆檢視、批次還原／重做 ====================

    public void lookupAsync(CommandSender sender, AuditQuery query, String paginationBaseCmd) {
        AuditDatabase db = this.auditDb;
        if (db == null) {
            lm.Audit_NotReady.sendMessage(sender);
            return;
        }
        String localSrv = plugin.getStorageServerName();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                int total = db.countMatching(query, localSrv);
                if (total <= 0) {
                    runOnSender(sender, () -> lm.Audit_NoResults.sendMessage(sender));
                    return;
                }
                int totalPages = Math.max(1, (total + LOOKUP_PAGE_SIZE - 1) / LOOKUP_PAGE_SIZE);
                int safePage = Math.max(1, Math.min(query.page, totalPages));
                int offset = (safePage - 1) * LOOKUP_PAGE_SIZE;
                List<AuditRecord> rows = db.querySummaries(query, localSrv, LOOKUP_PAGE_SIZE, offset);

                runOnSender(sender, () -> {
                    lm.Audit_Header_Lookup.sendMessage(sender, total, safePage, totalPages);
                    for (AuditRecord r : rows) {
                        sendLookupRow(sender, r);
                    }
                    if (totalPages > 1 && paginationBaseCmd != null && !paginationBaseCmd.isEmpty()) {
                        PageInfo pi = new PageInfo(LOOKUP_PAGE_SIZE, total, safePage);
                        pi.autoPagination(sender, paginationBaseCmd, "page:");
                    }
                });
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Audit] Lookup query failed", ex);
            }
        });
    }

    public void showRecordInfoAsync(CommandSender sender, long id) {
        AuditDatabase db = this.auditDb;
        if (db == null) {
            lm.Audit_NotReady.sendMessage(sender);
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                AuditRecord rec = db.getById(id);
                runOnSender(sender, () -> {
                    if (rec == null) {
                        lm.Audit_NoResults.sendMessage(sender);
                        return;
                    }
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    lm.Audit_Info_Header.sendMessage(sender, rec.id);
                    String body = lm.Audit_Info_Body.getMessage(
                            rec.action.getShortTag(),
                            rec.rolledBack ? lm.Audit_Lookup_RolledBackTag.getMessage() : "",
                            rec.resName,
                            rec.topResKey,
                            rec.actorName,
                            rec.ownerName.isEmpty() ? "N/A" : rec.ownerName,
                            rec.worldName,
                            rec.minX + "," + rec.minY + "," + rec.minZ,
                            rec.maxX + "," + rec.maxY + "," + rec.maxZ,
                            sdf.format(new Date(rec.createdAt)),
                            formatRelativeAge(rec.createdAt),
                            rec.summary);
                    for (String line : body.split("\n")) {
                        sender.sendMessage(CMIChatColor.translate(line));
                    }
                    if (sender instanceof Player) {
                        RawMessage rm = new RawMessage();
                        if (!rec.rolledBack) {
                            rm.addText(lm.Audit_Info_BtnRollback.getMessage(rec.id))
                                    .addHover("&eClick: /res co rb #" + rec.id)
                                    .addCommand("res co rb #" + rec.id);
                        } else {
                            rm.addText(lm.Audit_Info_BtnRestore.getMessage(rec.id))
                                    .addHover("&eClick: /res co rs #" + rec.id)
                                    .addCommand("res co rs #" + rec.id);
                        }
                        rm.addText("  ");
                        rm.addText(lm.Audit_Info_BtnTp.getMessage())
                                .addHover("&eTeleport to (" + rec.centerX() + ", " + rec.centerY() + ", " + rec.centerZ() + ")")
                                .addCommand("res co tp " + rec.id);
                        rm.show(sender);
                    }
                });
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Audit] Failed to load audit record #" + id, ex);
            }
        });
    }

    public void teleportToRecordAsync(Player player, long id) {
        AuditDatabase db = this.auditDb;
        if (db == null) {
            lm.Audit_NotReady.sendMessage(player);
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                AuditRecord rec = db.getById(id);
                if (rec == null) {
                    runOnSender(player, () -> lm.Audit_NoResults.sendMessage(player));
                    return;
                }
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                    org.bukkit.World w = Bukkit.getWorld(rec.worldName);
                    if (w == null || !player.isOnline()) {
                        return;
                    }
                    org.bukkit.Location dest = new org.bukkit.Location(
                            w, rec.centerX() + 0.5, Math.max(w.getMinHeight() + 1, rec.centerY()), rec.centerZ() + 0.5);
                    player.teleportAsync(dest);
                });
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Audit] Teleport lookup failed for #" + id, ex);
            }
        });
    }

    public void executeBatchAsync(CommandSender sender, AuditQuery query, boolean isRestore, boolean isRecoverMode) {
        AuditDatabase db = this.auditDb;
        if (db == null) {
            lm.Audit_NotReady.sendMessage(sender);
            return;
        }

        String modeLabel = isRecoverMode
                ? lm.Audit_Exec_ModeRecover.getMessage()
                : (isRestore ? lm.Audit_Exec_ModeRestore.getMessage() : lm.Audit_Exec_ModeRollback.getMessage());
        String previewTag = query.preview ? lm.Audit_Exec_PreviewTag.getMessage() : "";
        lm.Audit_Exec_Started.sendMessage(sender, modeLabel, previewTag);

        String localSrv = plugin.getStorageServerName();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            List<AuditRecord> records;
            try {
                records = db.queryForExecution(query, localSrv, isRestore, isRestore, MAX_BATCH_EXECUTION_ROWS);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Audit] Failed to load records for " + modeLabel, ex);
                return;
            }

            if (records.isEmpty()) {
                runOnSender(sender, () -> lm.Audit_NoResults.sendMessage(sender));
                return;
            }

            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                List<Long> appliedIds = new ArrayList<>();
                int noopCount = 0;
                List<String> conflictLines = new ArrayList<>();

                applyingAuditOnMain.set(true);
                try {
                    for (AuditRecord rec : records) {
                        ApplyOutcome out = ResidenceAuditBridge.applyRecord(plugin, rec, isRestore, query.preview);
                        switch (out.status) {
                        case APPLIED:
                            appliedIds.add(rec.id);
                            break;
                        case SKIPPED_NOOP:
                            noopCount++;
                            if (!query.preview) {
                                appliedIds.add(rec.id);
                            }
                            break;
                        default:
                            conflictLines.add(lm.Audit_Exec_ConflictLine.getMessage(
                                    rec.id, rec.resName, out.status.name() + (out.detail.isEmpty() ? "" : ": " + out.detail)));
                            break;
                        }
                    }

                    if (!query.preview && !appliedIds.isEmpty()) {
                        plugin.saveResidenceDataNow();
                    }
                } finally {
                    applyingAuditOnMain.set(false);
                }

                int effectiveApplied = query.preview ? appliedIds.size() : Math.max(0, appliedIds.size() - noopCount);
                int finalNoop = noopCount;

                if (!query.preview && !appliedIds.isEmpty()) {
                    Bukkit.getAsyncScheduler().runNow(plugin, t2 -> {
                        try {
                            db.markRolledBackBatch(appliedIds, !isRestore);
                        } catch (SQLException ex) {
                            plugin.getLogger().log(Level.WARNING, "[Audit] Failed to update rolled_back flags", ex);
                        }
                    });
                }

                runOnSender(sender, () -> {
                    String summaryMsg = lm.Audit_Exec_Summary.getMessage(
                            modeLabel, previewTag, effectiveApplied, finalNoop, conflictLines.size());
                    for (String line : summaryMsg.split("\n")) {
                        sender.sendMessage(CMIChatColor.translate(line));
                    }
                    int showConflicts = Math.min(5, conflictLines.size());
                    for (int i = 0; i < showConflicts; i++) {
                        sender.sendMessage(CMIChatColor.translate(conflictLines.get(i)));
                    }
                });
            });
        });
    }

    public void purgeAsync(CommandSender sender, long durationMs, String durationLabel) {
        AuditDatabase db = this.auditDb;
        if (db == null) {
            lm.Audit_NotReady.sendMessage(sender);
            return;
        }
        long cutoff = System.currentTimeMillis() - durationMs;
        String localSrv = plugin.getStorageServerName();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                int purged = db.purgeOlderThan(cutoff, localSrv);
                runOnSender(sender, () -> lm.Audit_Purge_Success.sendMessage(sender, purged, durationLabel));
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "[Audit] Purge failed", ex);
            }
        });
    }

    // ==================== 內部顯示與工具 ====================

    private void sendLookupRow(CommandSender sender, AuditRecord r) {
        String age = formatRelativeAge(r.createdAt);
        String actionColor = (r.action == AuditAction.REMOVE || r.action == AuditAction.SUBZONE_REMOVE) ? "&c"
                : ((r.action == AuditAction.CREATE || r.action == AuditAction.SUBZONE_CREATE) ? "&a" : "&e");
        String tag = actionColor + r.action.getShortTag();
        String rbTag = r.rolledBack ? lm.Audit_Lookup_RolledBackTag.getMessage() : "";
        String line = lm.Audit_Lookup_Line.getMessage(age, r.id, r.actorName, tag, r.resName, r.summary, rbTag);

        if (sender instanceof Player) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String hover = lm.Audit_Lookup_Hover.getMessage(
                    r.id,
                    sdf.format(new Date(r.createdAt)),
                    r.serverName,
                    r.worldName,
                    r.centerX(), r.centerY(), r.centerZ(),
                    r.ownerName.isEmpty() ? "N/A" : r.ownerName,
                    r.summary);
            RawMessage rm = new RawMessage();
            rm.addText(line).addHover(hover).addCommand("res co info " + r.id);
            rm.show(sender);
        } else {
            sender.sendMessage(CMIChatColor.translate(line));
        }
    }

    public static String formatRelativeAge(long createdAtMs) {
        long diffSec = Math.max(0L, (System.currentTimeMillis() - createdAtMs) / 1000L);
        if (diffSec < 60L) {
            return diffSec + "s";
        }
        if (diffSec < 3600L) {
            return String.format(Locale.ROOT, "%.1fm", diffSec / 60.0);
        }
        if (diffSec < 86_400L) {
            return String.format(Locale.ROOT, "%.1fh", diffSec / 3600.0);
        }
        return String.format(Locale.ROOT, "%.1fd", diffSec / 86_400.0);
    }

    private static String computeRelativePath(String topKey, String fullKey) {
        if (topKey == null || fullKey == null || fullKey.equalsIgnoreCase(topKey)) {
            return "";
        }
        String prefix = topKey.toLowerCase(Locale.ROOT) + ".";
        String lowerFull = fullKey.toLowerCase(Locale.ROOT);
        if (lowerFull.startsWith(prefix)) {
            return lowerFull.substring(prefix.length());
        }
        int dot = lowerFull.indexOf('.');
        return dot >= 0 ? lowerFull.substring(dot + 1) : "";
    }

    private void runOnSender(CommandSender sender, Runnable task) {
        if (sender instanceof Player) {
            Player p = (Player) sender;
            p.getScheduler().run(plugin, t -> {
                if (p.isOnline()) {
                    task.run();
                }
            }, null);
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        }
    }

    private static final class ActorIdentity {
        final UUID uuid;
        final String name;

        ActorIdentity(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    private static ActorIdentity resolveActor(CommandSender explicit, String systemTag) {
        if (systemTag != null && !systemTag.isEmpty()) {
            return new ActorIdentity(null, systemTag);
        }
        CommandSender eff = explicit != null ? explicit : ACTIVE_COMMAND_SENDER.get();
        if (eff instanceof Player) {
            Player p = (Player) eff;
            return new ActorIdentity(p.getUniqueId(), p.getName());
        }
        if (eff != null) {
            return new ActorIdentity(null, "#console");
        }
        return new ActorIdentity(null, "#system");
    }
}
