package com.bekvon.bukkit.residence.containers;

import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.crossserver.CrossServerManager;
import com.bekvon.bukkit.residence.crossserver.RemoteResidenceEntry;
import com.bekvon.bukkit.residence.protection.PlayerManager;
import com.bekvon.bukkit.residence.storage.CleanupReclaimManager;
import com.bekvon.bukkit.residence.storage.CleanupReclaimManager.ReclaimEntry;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter;

public class TargetInfo {

    String targetName = null;
    UUID targetUuid = null;
    boolean archivedHolderTarget = false;

    public TargetInfo() {
    }

    public TargetInfo defaultIfNotValid(String name) {

        if (isValid())
            return this;

        if (name == null || name.trim().isEmpty())
            return this;

        String trimmed = name.trim();
        Residence plugin = Residence.getInstance();

        // 1. 優先檢查是否為系統暫管帳號（如 Server_Unclaimed，不分大小寫，不依賴是否曾經登入）
        CleanupReclaimManager reclaim = plugin != null ? plugin.getCleanupReclaimManager() : null;
        if (reclaim != null && reclaim.isArchivedHolder(trimmed)) {
            String holderName = reclaim.getConfiguredArchivedHolderName();
            if (holderName == null)
                holderName = trimmed;
            UUID existing = ResidencePlayer.getUUID(holderName);
            targetName = holderName;
            targetUuid = existing != null ? existing : PlayerManager.createTempUUID(holderName.toLowerCase(Locale.ROOT));
            archivedHolderTarget = true;
            return this;
        }

        // 2. 本機真實玩家快取（優先取非 TempUUID 紀錄）
        ResidencePlayer resP = ResidencePlayer.get(trimmed);
        if (resP != null && resP.getUniqueId() != null && !PlayerManager.isTempUUID(resP.getUniqueId())) {
            targetName = resP.getName();
            targetUuid = resP.getUniqueId();
            return this;
        }

        // 3. 系統暫管領地的原領地主紀錄（即使該玩家名下已無一般領地或未登入本分流也能查到）
        if (reclaim != null) {
            ReclaimEntry byOriginal = reclaim.findOriginalOwnerByName(trimmed);
            if (byOriginal != null && byOriginal.getOriginalOwnerUuid() != null) {
                targetName = byOriginal.getOriginalOwnerName();
                targetUuid = byOriginal.getOriginalOwnerUuid();
                return this;
            }
        }

        // 4. 跨服／資料庫活躍玩家快取與跨服領地索引
        CrossServerManager csm = plugin != null ? plugin.getCrossServerManager() : null;
        if (csm != null) {
            UUID cachedUuid = csm.getCachedPlayerUuidByName(trimmed);
            if (cachedUuid != null) {
                String cachedName = csm.getCachedPlayerName(cachedUuid);
                targetName = cachedName != null && !cachedName.isEmpty() ? cachedName : trimmed;
                targetUuid = cachedUuid;
                return this;
            }
            for (RemoteResidenceEntry remote : csm.getRemoteResidences()) {
                if (remote.getOwnerUuid() != null && remote.getOwnerName().equalsIgnoreCase(trimmed)) {
                    targetName = remote.getOwnerName();
                    targetUuid = remote.getOwnerUuid();
                    return this;
                }
            }
        }

        // 5. 直接傳入 UUID 字串
        UUID parsedUuid = PlayerActivityImporter.parseFlexibleUuid(trimmed);
        if (parsedUuid != null) {
            String known = ResidencePlayer.getName(parsedUuid);
            if ((known == null || known.isEmpty()) && reclaim != null)
                known = reclaim.findOriginalOwnerName(parsedUuid);
            if ((known == null || known.isEmpty()) && csm != null)
                known = csm.getCachedPlayerName(parsedUuid);
            targetName = (known != null && !known.isEmpty()) ? known : parsedUuid.toString().substring(0, 8);
            targetUuid = parsedUuid;
            return this;
        }

        // 6. Paper 本機 usercache 非阻塞查詢
        try {
            OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(trimmed);
            if (off != null && off.getUniqueId() != null) {
                targetName = off.getName() != null ? off.getName() : trimmed;
                targetUuid = off.getUniqueId();
                return this;
            }
        } catch (Throwable ignored) {
        }

        // 7. 退回舊版 TempUUID ResidencePlayer
        if (resP != null) {
            targetName = resP.getName();
            targetUuid = resP.getUniqueId();
        }

        return this;
    }

    public boolean isSame(CommandSender sender) {

        if (!isValid() || archivedHolderTarget)
            return false;

        if (sender instanceof Player)
            return ((Player) sender).getUniqueId().equals(targetUuid);

        return false;
    }

    public TargetInfo defaultIfNotValid(CommandSender sender) {

        if (isValid())
            return this;

        if (sender instanceof Player) {
            ResidencePlayer resP = ResidencePlayer.get((Player) sender);
            targetName = resP.getName();
            targetUuid = resP.getUniqueId();
            return this;
        }

        return this;
    }

    public boolean isValid() {
        return targetName != null && targetUuid != null;
    }

    public boolean isPartiallyValid() {
        return targetName != null || targetUuid != null;
    }

    public UUID getUniqueId() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public boolean isArchivedHolderTarget() {
        return archivedHolderTarget;
    }
}
