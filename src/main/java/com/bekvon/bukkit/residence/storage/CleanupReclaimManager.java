package com.bekvon.bukkit.residence.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;

public class CleanupReclaimManager {

    public static final class ReclaimEntry {
        private final String serverName;
        private final String resKey;
        private final String resName;
        private final UUID resUuid;
        private final UUID originalOwnerUuid;
        private final String originalOwnerName;
        private final long archivedAt;

        public ReclaimEntry(String serverName, String resKey, String resName, UUID resUuid, UUID originalOwnerUuid, String originalOwnerName, long archivedAt) {
            this.serverName = serverName;
            this.resKey = resKey;
            this.resName = resName;
            this.resUuid = resUuid;
            this.originalOwnerUuid = originalOwnerUuid;
            this.originalOwnerName = originalOwnerName == null ? "" : originalOwnerName;
            this.archivedAt = archivedAt;
        }

        public String getServerName() {
            return serverName;
        }

        public String getResKey() {
            return resKey;
        }

        public String getResName() {
            return resName;
        }

        public UUID getResUuid() {
            return resUuid;
        }

        public UUID getOriginalOwnerUuid() {
            return originalOwnerUuid;
        }

        public String getOriginalOwnerName() {
            return originalOwnerName;
        }

        public long getArchivedAt() {
            return archivedAt;
        }
    }

    private final Residence plugin;
    private final ConcurrentHashMap<String, ReclaimEntry> yamlEntries = new ConcurrentHashMap<>();
    private final Object yamlLock = new Object();

    public CleanupReclaimManager(Residence plugin) {
        this.plugin = plugin;
    }

    private boolean isDatabaseReady() {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        return db != null && db.isInitialized();
    }

    private File getYamlFile() {
        return new File(new File(plugin.getDataFolder(), "Save"), "cleanup_reclaim.yml");
    }

    public void load() {
        yamlEntries.clear();
        if (!isDatabaseReady()) {
            loadYaml();
        }
    }

    public void recordArchived(ClaimedResidence res, UUID originalOwnerUuid, String originalOwnerName) {
        if (res == null || originalOwnerUuid == null || res.getName() == null)
            return;
        String serverName = plugin.getStorageServerName();
        String resName = res.getName();
        String resKey = resName.toLowerCase(Locale.ROOT);
        UUID resUuid = res.getUniqueId();
        long archivedAt = System.currentTimeMillis();
        ReclaimEntry entry = new ReclaimEntry(serverName, resKey, resName, resUuid, originalOwnerUuid, originalOwnerName, archivedAt);

        if (isDatabaseReady()) {
            try {
                plugin.getDatabaseStorageManager().upsertCleanupReclaim(entry);
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to save cleanup reclaim record for " + resName, ex);
            }
        } else {
            yamlEntries.put(resKey, entry);
            saveYaml();
        }
    }

    public void restoreForPlayerAsync(Player player) {
        if (player == null || !plugin.isEnabled())
            return;
        if (!plugin.getConfigManager().isAutoCleanRestoreOnReturn())
            return;

        UUID uuid = player.getUniqueId();
        String playerName = player.getName();
        String serverName = plugin.getStorageServerName();

        if (isDatabaseReady()) {
            DatabaseStorageManager db = plugin.getDatabaseStorageManager();
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                List<ReclaimEntry> entries;
                try {
                    entries = db.loadCleanupReclaimsForPlayer(serverName, uuid);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.WARNING, "Failed to check cleanup reclaim records for " + playerName, ex);
                    return;
                }
                if (entries.isEmpty())
                    return;
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> applyRestoresOnMain(entries, player));
            });
        } else {
            List<ReclaimEntry> matches = new ArrayList<>();
            for (ReclaimEntry entry : yamlEntries.values()) {
                if (uuid.equals(entry.getOriginalOwnerUuid())) {
                    matches.add(entry);
                }
            }
            if (!matches.isEmpty()) {
                Bukkit.getGlobalRegionScheduler().execute(plugin, () -> applyRestoresOnMain(matches, player));
            }
        }
    }

    public void reconcileCrossServerReturnsNow() {
        if (!plugin.isEnabled() || !isDatabaseReady())
            return;
        if (!plugin.getConfigManager().isAutoCleanRestoreOnReturn())
            return;
        String serverName = plugin.getStorageServerName();
        List<ReclaimEntry> entries;
        try {
            entries = plugin.getDatabaseStorageManager().loadReturningCleanupReclaims(serverName);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to reconcile cross-server cleanup reclaims", ex);
            return;
        }
        if (entries.isEmpty())
            return;
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> applyRestoresOnMain(entries, null));
    }

    public void reconcileCrossServerReturnsAsync() {
        if (!plugin.isEnabled() || !isDatabaseReady())
            return;
        if (!plugin.getConfigManager().isAutoCleanRestoreOnReturn())
            return;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> reconcileCrossServerReturnsNow());
    }

    private void applyRestoresOnMain(List<ReclaimEntry> entries, Player notifyPlayer) {
        if (!plugin.isEnabled() || entries == null || entries.isEmpty())
            return;

        String expectedHolder = plugin.getConfigManager().getAutoCleanUserName();
        List<String> restoredNames = new ArrayList<>();
        List<ReclaimEntry> toDelete = new ArrayList<>();

        for (ReclaimEntry entry : entries) {
            ClaimedResidence res = null;
            if (entry.getResUuid() != null) {
                res = plugin.getResidenceManager().getByUUID(entry.getResUuid());
            }
            if (res == null) {
                ClaimedResidence byName = plugin.getResidenceManager().getByName(entry.getResName());
                if (byName == null) {
                    byName = plugin.getResidenceManager().getByName(entry.getResKey());
                }
                // Only fall back to name if record had no UUID or the residence's UUID matches
                if (byName != null && (entry.getResUuid() == null || byName.getUniqueId() == null || entry.getResUuid().equals(byName.getUniqueId()))) {
                    res = byName;
                }
            }
            if (res == null) {
                toDelete.add(entry);
                continue;
            }
            if (!res.getOwner().equalsIgnoreCase(expectedHolder)) {
                // Residence was already manually transferred or sold to someone else; never overwrite
                toDelete.add(entry);
                continue;
            }
            // Restore ownership WITHOUT resetting flags (preserves all custom flags & player trust)
            boolean changed = res.getPermissions().setOwner(entry.getOriginalOwnerUuid(), false);
            if (changed) {
                Player online = notifyPlayer != null && notifyPlayer.getUniqueId().equals(entry.getOriginalOwnerUuid())
                        ? notifyPlayer
                        : Bukkit.getPlayer(entry.getOriginalOwnerUuid());
                if (online != null) {
                    res.getPermissions().setOwnerLastKnownName(online.getName());
                } else if (!entry.getOriginalOwnerName().isEmpty()) {
                    res.getPermissions().setOwnerLastKnownName(entry.getOriginalOwnerName());
                }
                plugin.getSignUtil().updateSignResName(res);
                restoredNames.add(res.getName());
                toDelete.add(entry);
            }
        }

        if (!toDelete.isEmpty()) {
            deleteEntriesAsync(toDelete);
        }

        if (!restoredNames.isEmpty()) {
            plugin.saveResidenceDataNow();
            if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
                plugin.getCrossServerManager().triggerAsyncSync();
            }
            plugin.getLogger().info("[AutoCleanUp] Automatically restored " + restoredNames.size()
                    + " archived residence(s): " + String.join(", ", restoredNames));

            if (notifyPlayer != null && notifyPlayer.isOnline()) {
                lm.Residence_CleanupRestored.sendMessage(notifyPlayer, String.join(", ", restoredNames));
            }
        }
    }

    private void deleteEntriesAsync(List<ReclaimEntry> entries) {
        if (entries == null || entries.isEmpty())
            return;
        if (isDatabaseReady()) {
            DatabaseStorageManager db = plugin.getDatabaseStorageManager();
            List<ReclaimEntry> copy = new ArrayList<>(entries);
            Runnable work = () -> {
                for (ReclaimEntry entry : copy) {
                    try {
                        db.deleteCleanupReclaim(entry.getServerName(), entry.getResKey());
                    } catch (SQLException ex) {
                        plugin.getLogger().log(Level.WARNING, "Failed to delete cleanup reclaim record for " + entry.getResName(), ex);
                    }
                }
            };
            if (plugin.isEnabled()) {
                Bukkit.getAsyncScheduler().runNow(plugin, task -> work.run());
            } else {
                work.run();
            }
        } else {
            for (ReclaimEntry entry : entries) {
                yamlEntries.remove(entry.getResKey());
            }
            saveYaml();
        }
    }

    public List<ReclaimEntry> getLocalYamlSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(yamlEntries.values()));
    }

    private void loadYaml() {
        File file = getYamlFile();
        if (!file.exists())
            return;
        synchronized (yamlLock) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection sec = yml.getConfigurationSection("Reclaims");
            if (sec == null)
                return;
            String srv = plugin.getStorageServerName();
            for (String key : sec.getKeys(false)) {
                ConfigurationSection one = sec.getConfigurationSection(key);
                if (one == null)
                    continue;
                String uuidRaw = one.getString("ownerUuid");
                if (uuidRaw == null)
                    continue;
                UUID uuid;
                try {
                    uuid = UUID.fromString(uuidRaw);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                String resName = one.getString("resName", key);
                UUID resUuid = null;
                String resUuidRaw = one.getString("resUuid");
                if (resUuidRaw != null) {
                    try {
                        resUuid = UUID.fromString(resUuidRaw);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                String ownerName = one.getString("ownerName", "");
                long archivedAt = one.getLong("archivedAt", 0L);
                String resKey = resName.toLowerCase(Locale.ROOT);
                yamlEntries.put(resKey, new ReclaimEntry(srv, resKey, resName, resUuid, uuid, ownerName, archivedAt));
            }
        }
    }

    public void saveYaml() {
        synchronized (yamlLock) {
            File file = getYamlFile();
            if (file.getParentFile() != null)
                file.getParentFile().mkdirs();
            YamlConfiguration yml = new YamlConfiguration();
            for (Map.Entry<String, ReclaimEntry> e : yamlEntries.entrySet()) {
                ReclaimEntry r = e.getValue();
                String base = "Reclaims." + r.getResKey();
                yml.set(base + ".resName", r.getResName());
                if (r.getResUuid() != null)
                    yml.set(base + ".resUuid", r.getResUuid().toString());
                yml.set(base + ".ownerUuid", r.getOriginalOwnerUuid().toString());
                yml.set(base + ".ownerName", r.getOriginalOwnerName());
                yml.set(base + ".archivedAt", r.getArchivedAt());
            }
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try {
                yml.save(tmp);
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to save cleanup_reclaim.yml", ex);
            }
        }
    }
}
