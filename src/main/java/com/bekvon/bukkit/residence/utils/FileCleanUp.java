package com.bekvon.bukkit.residence.utils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.economy.rent.RentedLand;
import com.bekvon.bukkit.residence.permissions.LuckPerms5Adapter;
import com.bekvon.bukkit.residence.permissions.PermissionManager.ResPerm;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager;

import net.Zrips.CMILib.Version.Version;

public class FileCleanUp {

    private final Residence plugin;

    public FileCleanUp(Residence plugin) {
        this.plugin = plugin;
    }

    public void cleanOldResidence() {

        Map<String, ClaimedResidence> resNameList = new HashMap<>(plugin.getResidenceManager().getResidences());
        if (resNameList.isEmpty())
            return;

        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        boolean dbReady = db != null && db.isInitialized();
        boolean crossServer = plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled();

        Map<UUID, Long> dbLastSeen = Collections.emptyMap();
        if (dbReady) {
            try {
                dbLastSeen = db.loadAllPlayerLastSeen();
            } catch (Exception ex) {
                if (crossServer) {
                    plugin.getLogger().log(Level.WARNING, "Aborting AutoCleanUp: unable to read cross-server player activity from database.", ex);
                    return;
                }
                plugin.getLogger().log(Level.WARNING, "AutoCleanUp could not read database player activity; falling back to local offline player timestamps.", ex);
            }
        }

        OfflinePlayer[] offplayer = Bukkit.getOfflinePlayers();
        Map<UUID, OfflinePlayer> playerMapUUID = new HashMap<>(offplayer.length * 2 + 1);
        for (OfflinePlayer one : offplayer) {
            if (one != null && one.getUniqueId() != null) {
                playerMapUUID.put(one.getUniqueId(), one);
            }
        }

        int interval = plugin.getConfigManager().getResidenceFileCleanDays();
        long time = System.currentTimeMillis();
        boolean useRegenerate = plugin.getConfigManager().isAutoCleanUpRegenerate();
        if (useRegenerate && Version.isFolia()) {
            plugin.getLogger().warning("AutoCleanUp.Regenerate is disabled automatically on Folia to prevent off-region terrain modification.");
            useRegenerate = false;
        }

        lm.consoleMessage("Starting auto CleanUp (" + playerMapUUID.size() + " local / " + dbLastSeen.size()
                + " db players, " + resNameList.size() + " residences)!");

        int cleaned = 0;
        int skippedUnknown = 0;
        int skippedActiveRenter = 0;
        try {
            for (Entry<String, ClaimedResidence> oneName : resNameList.entrySet()) {
                ClaimedResidence res = oneName.getValue();
                if (res == null || !res.isTopArea())
                    continue;

                String worldName = res.getPermissions().getWorldName();
                if (worldName == null || !plugin.getConfigManager().getAutoCleanUpWorlds().contains(worldName.toLowerCase()))
                    continue;

                if (res.getOwner().equalsIgnoreCase("server land") || res.getOwner().equalsIgnoreCase(plugin.getServerLandName()))
                    continue;

                if (res.getOwner().equalsIgnoreCase(plugin.getConfigManager().getAutoCleanUserName()))
                    continue;

                UUID ownerUuid = res.getOwnerUUID();
                if (ownerUuid == null) {
                    skippedUnknown++;
                    continue;
                }

                long lastPlayed = resolveEffectiveLastSeen(ownerUuid, playerMapUUID, dbLastSeen, time);
                if (lastPlayed <= 0L) {
                    skippedUnknown++;
                    if (plugin.getConfigManager().isAutoCleanDetailsOnUnknown())
                        lm.consoleMessage("Skipping " + res.getName() + " residence owned by " + res.getOwner() + " (" + ownerUuid + ") due to unknown last-seen time.");
                    continue;
                }

                long daysInactive = (time - lastPlayed) / 86_400_000L;
                if (daysInactive < interval)
                    continue;

                // Protect residences that have an active (or unknown-activity) renter on the residence or any subzone
                if (hasProtectedRenter(res, playerMapUUID, dbLastSeen, time, interval)) {
                    skippedActiveRenter++;
                    continue;
                }

                // Check residence.cleanbypass ONLY for expired candidates (fail-safe for offline LuckPerms users)
                if (hasCleanBypass(ownerUuid, playerMapUUID.get(ownerUuid), worldName))
                    continue;

                ResidencePlayer rPlayer = plugin.getPlayerManager().getResidencePlayer(ownerUuid);

                if (plugin.getConfigManager().isAutoCleanTrasnferToUser()
                        && !res.getOwner().equalsIgnoreCase(plugin.getConfigManager().getAutoCleanUserName())) {
                    String originalOwnerName = res.getOwner();
                    if (plugin.getConfigManager().isAutoCleanRestoreOnReturn() && plugin.getCleanupReclaimManager() != null) {
                        plugin.getCleanupReclaimManager().recordArchived(res, ownerUuid, originalOwnerName);
                    }
                    boolean resetFlags = !plugin.getConfigManager().isAutoCleanKeepFlags();
                    res.getPermissions().setOwner(plugin.getConfigManager().getAutoCleanUserName(), resetFlags);
                    if (plugin.getRentManager().isForRent(res))
                        plugin.getRentManager().removeRentable(res);
                    if (plugin.getTransactionManager().isForSale(res))
                        plugin.getTransactionManager().removeFromSale(res);
                } else {
                    plugin.getResidenceManager().removeResidence(rPlayer, res, true, useRegenerate);
                }
                cleaned++;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }

        if (cleaned > 0) {
            if (dbReady) {
                plugin.saveResidenceDataNow();
            }
            if (crossServer) {
                plugin.getCrossServerManager().triggerAsyncSync();
            }
        }

        lm.consoleMessage("Auto CleanUp deleted/transferred " + cleaned + " residences!");
        if (skippedUnknown > 0)
            lm.consoleMessage("Skipped " + skippedUnknown + " residences due to inability to determine owner last-seen time.");
        if (skippedActiveRenter > 0)
            lm.consoleMessage("Skipped " + skippedActiveRenter + " residences because an active renter is currently renting them.");
    }

    private long resolveEffectiveLastSeen(UUID uuid, Map<UUID, OfflinePlayer> localPlayers, Map<UUID, Long> dbLastSeen, long now) {
        if (uuid == null)
            return 0L;
        if (Bukkit.getPlayer(uuid) != null)
            return now;
        long best = 0L;
        OfflinePlayer local = localPlayers.get(uuid);
        if (local != null) {
            long localTs = local.getLastPlayed();
            if (localTs > best)
                best = localTs;
        }
        Long crossTs = dbLastSeen.get(uuid);
        if (crossTs != null && crossTs > best) {
            best = crossTs;
        }
        return best;
    }

    private boolean hasProtectedRenter(ClaimedResidence res, Map<UUID, OfflinePlayer> localPlayers, Map<UUID, Long> dbLastSeen, long now, int intervalDays) {
        if (res == null)
            return false;
        if (res.isRented()) {
            RentedLand rented = res.getRentedLand();
            if (rented != null) {
                UUID renterUuid = rented.getUniqueId();
                if (renterUuid == null)
                    return true;
                long renterLastSeen = resolveEffectiveLastSeen(renterUuid, localPlayers, dbLastSeen, now);
                if (renterLastSeen <= 0L)
                    return true;
                long renterDays = (now - renterLastSeen) / 86_400_000L;
                if (renterDays < intervalDays)
                    return true;
            }
        }
        for (ClaimedResidence sub : res.getSubzones()) {
            if (hasProtectedRenter(sub, localPlayers, dbLastSeen, now, intervalDays))
                return true;
        }
        return false;
    }

    private boolean hasCleanBypass(UUID ownerUuid, OfflinePlayer localPlayer, String worldName) {
        com.bekvon.bukkit.residence.permissions.PermissionsInterface perms = plugin.getPermissionManager().getPermissionsPlugin();
        if (perms == null)
            return false;
        String perm = ResPerm.cleanbypass.getPermission();
        if (perms instanceof LuckPerms5Adapter) {
            LuckPerms5Adapter lp = (LuckPerms5Adapter) perms;
            return lp.hasOfflinePermissionFailSafe(ownerUuid, perm, worldName);
        }
        OfflinePlayer target = localPlayer != null ? localPlayer : Bukkit.getOfflinePlayer(ownerUuid);
        if (target == null)
            return false;
        try {
            return perms.hasPermission(target, perm, worldName);
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to check cleanbypass for " + ownerUuid + "; skipping cleanup for safety.", ex);
            return true;
        }
    }
}
