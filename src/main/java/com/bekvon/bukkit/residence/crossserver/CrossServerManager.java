package com.bekvon.bukkit.residence.crossserver;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.gui.ResidenceGuiMetaManager;
import com.bekvon.bukkit.residence.gui.ResidenceGuiMetadata;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagCombo;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager.PendingTeleport;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager.ReservationResult;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.Zrips.CMILib.Colors.CMIChatColor;

public class CrossServerManager {

    private final Residence plugin;
    private volatile Map<String, RemoteResidenceEntry> remoteCache = Collections.emptyMap();
    private ScheduledTask syncTask = null;
    private String registeredChannel = null;

    public CrossServerManager(Residence plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfigManager().isCrossServerEnabled()
                && plugin.getDatabaseStorageManager() != null
                && plugin.getDatabaseStorageManager().isInitialized();
    }

    public String getServerName() {
        return plugin.getStorageServerName();
    }

    public void start() {
        stop();
        if (!isEnabled())
            return;

        String channel = plugin.getConfigManager().getCrossServerChannel();
        if (registeredChannel == null) {
            try {
                Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, channel);
                registeredChannel = channel;
            } catch (Throwable ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to register outgoing plugin channel " + channel, ex);
            }
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            if (plugin.getGuiMetaManager() != null)
                plugin.getGuiMetaManager().refreshFromDatabaseNow();
            syncLocalIndexNow();
            refreshRemoteCacheNow();
        });

        int intervalSeconds = Math.max(5, plugin.getConfigManager().getCrossServerSyncInterval());
        syncTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            if (plugin.getGuiMetaManager() != null)
                plugin.getGuiMetaManager().refreshFromDatabaseNow();
            if (plugin.getPlayerLimitManager() != null)
                plugin.getPlayerLimitManager().refreshAllFromDatabaseNow();
            if (plugin.getCleanupReclaimManager() != null) {
                plugin.getCleanupReclaimManager().refreshCacheFromDatabaseNow();
                plugin.getCleanupReclaimManager().reconcileCrossServerReturnsNow();
            }
            syncLocalIndexNow();
            refreshRemoteCacheNow();
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    public void stop() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        if (registeredChannel != null) {
            try {
                Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, registeredChannel);
            } catch (Throwable ignored) {
            }
            registeredChannel = null;
        }
    }

    public void triggerAsyncSync() {
        if (!isEnabled())
            return;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            syncLocalIndexNow();
            refreshRemoteCacheNow();
        });
    }

    public void refreshAsyncThen(Runnable mainThreadCallback) {
        if (!isEnabled()) {
            if (mainThreadCallback != null)
                mainThreadCallback.run();
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            if (plugin.getGuiMetaManager() != null)
                plugin.getGuiMetaManager().refreshFromDatabaseNow();
            syncLocalIndexNow();
            refreshRemoteCacheNow();
            if (mainThreadCallback != null) {
                Bukkit.getGlobalRegionScheduler().execute(plugin, mainThreadCallback);
            }
        });
    }

    public List<RemoteResidenceEntry> buildLocalEntriesSnapshot() {
        String srv = getServerName();
        ResidenceGuiMetaManager metaMgr = plugin.getGuiMetaManager();
        List<RemoteResidenceEntry> list = new ArrayList<>();
        Collection<ClaimedResidence> snapshot = new ArrayList<>(plugin.getResidenceManager().getResidences().values());
        for (ClaimedResidence res : snapshot) {
            if (res == null || !res.isTopArea())
                continue;
            list.add(toEntry(res, srv, metaMgr));
        }
        return list;
    }

    public static RemoteResidenceEntry toEntry(ClaimedResidence res, String serverName, ResidenceGuiMetaManager metaMgr) {
        CuboidArea main = res.getMainArea();
        Location tp = main != null ? res.getTeleportLocation(null, false) : null;
        double x = 0, y = 64, z = 0;
        float yaw = 0, pitch = 0;
        if (tp != null) {
            x = tp.getX();
            y = tp.getY();
            z = tp.getZ();
            yaw = tp.getYaw();
            pitch = tp.getPitch();
        } else if (main != null) {
            x = (main.getLowVector().getBlockX() + main.getHighVector().getBlockX()) / 2.0;
            y = main.getHighVector().getBlockY();
            z = (main.getLowVector().getBlockZ() + main.getHighVector().getBlockZ()) / 2.0;
        }
        boolean hidden = res.getPermissions().has(Flags.hidden, FlagCombo.OnlyTrue);
        boolean tpPublic = res.getPermissions().has(Flags.tp, FlagCombo.TrueOrNone);
        ResidenceGuiMetadata meta = metaMgr != null ? metaMgr.getMetadata(serverName, res.getName()) : new ResidenceGuiMetadata();
        int likes = metaMgr != null ? metaMgr.getLikes(serverName, res.getName()) : 0;

        return new RemoteResidenceEntry(
                serverName,
                res.getName().toLowerCase(),
                res.getName(),
                res.getUniqueId(),
                res.getWorldName(),
                res.getOwnerUUID(),
                res.getOwner(),
                x, y, z, yaw, pitch,
                hidden,
                tpPublic,
                meta.isListed(),
                meta.getAlias(),
                meta.getDescription(),
                meta.getIcon(),
                likes,
                res.getCreateTime(),
                true);
    }

    public synchronized void syncLocalIndexNow() {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized())
            return;
        try {
            db.syncLocalServerIndex(getServerName(), buildLocalEntriesSnapshot());
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to sync local residence index to database", ex);
        }
    }

    public synchronized void refreshRemoteCacheNow() {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized())
            return;
        try {
            ResidenceGuiMetaManager metaMgr = plugin.getGuiMetaManager();
            if (metaMgr != null) {
                metaMgr.refreshFromDatabaseNow();
            }
            List<RemoteResidenceEntry> all = db.fetchAllIndexedResidences(getServerName());
            Map<String, RemoteResidenceEntry> nextRemote = new LinkedHashMap<>();
            for (RemoteResidenceEntry entry : all) {
                if (!entry.isLocal()) {
                    RemoteResidenceEntry effective = entry;
                    if (metaMgr != null) {
                        ResidenceGuiMetadata liveMeta = metaMgr.hasMetadata(entry.getServerName(), entry.getResName())
                                ? metaMgr.getMetadata(entry.getServerName(), entry.getResName())
                                : null;
                        int liveLikes = metaMgr.getLikes(entry.getServerName(), entry.getResName());
                        if (liveMeta != null || liveLikes != entry.getLikes()) {
                            effective = new RemoteResidenceEntry(
                                    entry.getServerName(), entry.getResKey(), entry.getResName(), entry.getResUuid(),
                                    entry.getWorldName(), entry.getOwnerUuid(), entry.getOwnerName(),
                                    entry.getTpX(), entry.getTpY(), entry.getTpZ(), entry.getTpYaw(), entry.getTpPitch(),
                                    entry.isHidden(), entry.isTpPublic(),
                                    liveMeta != null ? liveMeta.isListed() : entry.isListed(),
                                    liveMeta != null ? liveMeta.getAlias() : entry.getAlias(),
                                    liveMeta != null ? liveMeta.getDescription() : entry.getDescription(),
                                    liveMeta != null ? liveMeta.getIcon() : entry.getIcon(),
                                    liveLikes,
                                    entry.getCreateTime(),
                                    false);
                        }
                    }
                    nextRemote.put(effective.getQualifiedKey(), effective);
                }
            }
            remoteCache = Collections.unmodifiableMap(nextRemote);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to refresh remote residence cache", ex);
        }
    }

    public List<RemoteResidenceEntry> getRemoteResidences() {
        return new ArrayList<>(remoteCache.values());
    }

    public List<RemoteResidenceEntry> getRemoteResidencesByOwner(UUID ownerUuid) {
        if (ownerUuid == null)
            return Collections.emptyList();
        List<RemoteResidenceEntry> result = new ArrayList<>();
        for (RemoteResidenceEntry entry : remoteCache.values()) {
            if (ownerUuid.equals(entry.getOwnerUuid()))
                result.add(entry);
        }
        return result;
    }

    public List<RemoteResidenceEntry> getAllResidencesUnified() {
        List<RemoteResidenceEntry> all = new ArrayList<>(buildLocalEntriesSnapshot());
        if (isEnabled()) {
            all.addAll(remoteCache.values());
        }
        return all;
    }

    public Set<String> getAvailableServers() {
        Set<String> servers = new LinkedHashSet<>();
        servers.add(getServerName());
        for (RemoteResidenceEntry entry : remoteCache.values()) {
            servers.add(entry.getServerName());
        }
        return servers;
    }

    public boolean usesGlobalQuota() {
        return isEnabled() && plugin.getConfigManager().isCrossServerGlobalLimit();
    }

    public boolean reserveResidence(UUID owner, String key, int limit, Player player) {
        return reserveResidenceWithPrevious(owner, key, limit, player, lm.Residence_TooMany).isReserved();
    }

    public ReservationResult reserveResidenceWithPrevious(UUID owner, String key, int limit, Player player, lm limitMessage) {
        try {
            ReservationResult result = plugin.getDatabaseStorageManager().reserveResidenceWithPrevious(owner, getServerName(), key, limit);
            if (result.isReserved())
                return result;
            if (player != null) {
                (limitMessage != null ? limitMessage : lm.Residence_TooMany).sendMessage(player);
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not reserve a global residence slot", ex);
            if (player != null) {
                player.sendMessage(CMIChatColor.RED + "The network residence limit could not be checked.");
            }
        }
        return new ReservationResult(false, null);
    }

    public void restoreResidenceReservation(UUID owner, String key) {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized() || owner == null || key == null)
            return;
        try {
            db.restoreResidenceReservation(getServerName(), key, owner);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not restore a global residence slot", ex);
        }
    }

    public void releaseResidenceReservation(UUID owner, String key) {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized() || key == null)
            return;
        try {
            db.releaseResidenceReservation(getServerName(), key, owner);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not release a global residence slot", ex);
        }
    }

    public void renameResidenceReservation(String oldKey, String newKey) {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized() || oldKey == null || newKey == null)
            return;
        try {
            db.renameResidenceReservation(getServerName(), oldKey, newKey);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not rename a global residence slot", ex);
        }
    }

    public int getRemoteResidenceCount(UUID ownerUuid) {
        if (!isEnabled() || !plugin.getConfigManager().isCrossServerGlobalLimit() || ownerUuid == null)
            return 0;
        int cached = 0;
        for (RemoteResidenceEntry entry : remoteCache.values()) {
            if (ownerUuid.equals(entry.getOwnerUuid()))
                cached++;
        }
        return cached;
    }

    public List<RemoteResidenceEntry> findRemoteMatches(String query) {
        if (query == null || query.trim().isEmpty())
            return Collections.emptyList();
        String trimmed = query.trim();
        List<RemoteResidenceEntry> matches = new ArrayList<>();
        int colon = trimmed.indexOf(':');
        if (colon > 0 && colon < trimmed.length() - 1) {
            String targetServer = trimmed.substring(0, colon);
            String targetRes = trimmed.substring(colon + 1);
            RemoteResidenceEntry exact = remoteCache.get(targetServer.toLowerCase() + ":" + targetRes.toLowerCase());
            if (exact != null) {
                matches.add(exact);
                return matches;
            }
            String lowerRes = targetRes.toLowerCase();
            for (RemoteResidenceEntry entry : remoteCache.values()) {
                if (!entry.getResKey().equals(lowerRes))
                    continue;
                String plainDisplay = CMIChatColor.stripColor(CMIChatColor.translate(getServerDisplayName(entry.getServerName())));
                if (plainDisplay != null && plainDisplay.equalsIgnoreCase(targetServer)) {
                    matches.add(entry);
                }
            }
            return matches;
        }
        String lower = trimmed.toLowerCase();
        for (RemoteResidenceEntry entry : remoteCache.values()) {
            if (entry.getResKey().equals(lower))
                matches.add(entry);
        }
        return matches;
    }

    public String getServerDisplayName(String serverName) {
        return plugin.getConfigManager().getCrossServerDisplayName(serverName);
    }

    public boolean tryCrossServerTeleport(Player player, String targetQuery, boolean resadmin) {
        if (!isEnabled() || player == null || targetQuery == null)
            return false;

        List<RemoteResidenceEntry> matches = findRemoteMatches(targetQuery);
        if (targetQuery.indexOf(':') < 0 && !resadmin)
            matches.removeIf(entry -> entry.isHidden() && !entry.isOwner(player.getUniqueId()));
        if (matches.isEmpty())
            return false;

        if (matches.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (RemoteResidenceEntry e : matches) {
                if (sb.length() > 0)
                    sb.append(", ");
                sb.append(e.getQualifiedName());
            }
            player.sendMessage(CMIChatColor.translate("&eMultiple servers have residence &6" + targetQuery
                    + "&e. Please specify server: &6" + sb));
            return true;
        }

        teleportToRemoteEntry(player, matches.get(0), resadmin);
        return true;
    }

    public void teleportToRemoteEntry(Player player, RemoteResidenceEntry entry, boolean resadmin) {
        if (player == null || entry == null)
            return;

        if (entry.isLocal()) {
            ClaimedResidence local = plugin.getResidenceManager().getByName(entry.getResName());
            if (local == null) {
                lm.Invalid_Residence.sendMessage(player);
                return;
            }
            player.performCommand((resadmin ? "resadmin" : "res") + " tp " + local.getName());
            return;
        }

        if (!isEnabled()) {
            lm.Invalid_Residence.sendMessage(player);
            return;
        }

        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        long ttl = plugin.getConfigManager().getCrossServerTeleportTimeout() * 1000L;
        UUID playerUuid = player.getUniqueId();

        player.sendMessage(CMIChatColor.translate("&aConnecting to server &6" + getServerDisplayName(entry.getServerName())
                + "&a -> residence &e" + entry.getResName() + "&a..."));

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                db.savePendingTeleport(playerUuid, entry.getServerName(), entry.getResName(), resadmin, ttl);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to record cross-server teleport for " + player.getName(), ex);
                player.getScheduler().run(plugin, t -> player.sendMessage(CMIChatColor.RED + "Cross-server teleport failed due to database error."), null);
                return;
            }

            player.getScheduler().run(plugin, t -> {
                if (player.isOnline())
                    sendProxyConnect(player, entry.getServerName());
            }, null);
        });
    }

    public void recordPlayerActivityAsync(Player player) {
        if (player == null)
            return;
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized())
            return;
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long now = System.currentTimeMillis();
        String server = plugin.getStorageServerName();
        if (!plugin.isEnabled()) {
            try {
                db.upsertPlayerActivity(uuid, name, now, server);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to update player activity for " + name, ex);
            }
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                db.upsertPlayerActivity(uuid, name, now, server);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to update player activity for " + name, ex);
            }
        });
    }

    public void flushOnlinePlayerActivityNow() {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized())
            return;
        Map<UUID, Long> lastSeenMap = new java.util.HashMap<>();
        Map<UUID, String> namesMap = new java.util.HashMap<>();
        long now = System.currentTimeMillis();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online == null || online.getUniqueId() == null)
                continue;
            lastSeenMap.put(online.getUniqueId(), now);
            namesMap.put(online.getUniqueId(), online.getName());
        }
        if (lastSeenMap.isEmpty())
            return;
        try {
            db.upsertPlayerActivitiesBatch(lastSeenMap, namesMap, plugin.getStorageServerName());
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to flush online player activity on disable", ex);
        }
    }

    public void seedLocalPlayerActivityNow() {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized())
            return;
        Map<UUID, Long> lastSeenMap = new java.util.HashMap<>();
        Map<UUID, String> namesMap = new java.util.HashMap<>();
        long now = System.currentTimeMillis();
        for (org.bukkit.OfflinePlayer off : Bukkit.getOfflinePlayers()) {
            if (off == null || off.getUniqueId() == null)
                continue;
            long ts = off.isOnline() ? now : off.getLastPlayed();
            if (ts > 0L) {
                lastSeenMap.put(off.getUniqueId(), ts);
                if (off.getName() != null)
                    namesMap.put(off.getUniqueId(), off.getName());
            }
        }
        if (lastSeenMap.isEmpty())
            return;
        try {
            db.upsertPlayerActivitiesBatch(lastSeenMap, namesMap, plugin.getStorageServerName());
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to seed local player activity into database", ex);
        }
    }

    public void seedLocalPlayerActivityAsync() {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized())
            return;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> seedLocalPlayerActivityNow());
    }

    public void handlePlayerJoinArrival(Player player) {
        if (!isEnabled() || player == null)
            return;
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        UUID uuid = player.getUniqueId();
        String server = getServerName();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            PendingTeleport pending;
            try {
                pending = db.consumePendingTeleport(uuid, server);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to check pending cross-server teleport for " + player.getName(), ex);
                return;
            }
            if (pending == null)
                return;

            player.getScheduler().runDelayed(plugin, t -> {
                if (!player.isOnline())
                    return;
                boolean destAdmin = plugin.getPermissionManager().isResidenceAdmin(player);
                String command = pending.isBypassPermission() && destAdmin ? "resadmin" : "res";
                player.performCommand(command + " tp " + pending.getResidenceName());
            }, null, 5L);
        });
    }

    private void sendProxyConnect(Player player, String targetServer) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeUTF("Connect");
            out.writeUTF(targetServer);
            player.sendPluginMessage(plugin, plugin.getConfigManager().getCrossServerChannel(), baos.toByteArray());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to send proxy Connect message for " + player.getName(), ex);
        }
    }
}
