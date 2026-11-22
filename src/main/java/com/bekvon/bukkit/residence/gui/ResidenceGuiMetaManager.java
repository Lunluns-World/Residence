package com.bekvon.bukkit.residence.gui;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.persistance.YMLSaveHelper;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager.SocialState;

import net.Zrips.CMILib.Version.Schedulers.CMIScheduler;

public class ResidenceGuiMetaManager {

    public static final String YAML_FILE_NAME = "gui_meta.yml";

    public static final class YamlGuiData {
        private final Map<String, ResidenceGuiMetadata> metadata;
        private final Map<UUID, Map<String, SocialState>> social;
        private final Map<String, Integer> likeCounts;

        public YamlGuiData(Map<String, ResidenceGuiMetadata> metadata,
                Map<UUID, Map<String, SocialState>> social,
                Map<String, Integer> likeCounts) {
            this.metadata = metadata;
            this.social = social;
            this.likeCounts = likeCounts;
        }

        public Map<String, ResidenceGuiMetadata> getMetadata() {
            return metadata;
        }

        public Map<UUID, Map<String, SocialState>> getSocial() {
            return social;
        }

        public Map<String, Integer> getLikeCounts() {
            return likeCounts;
        }

        public boolean isEmpty() {
            return metadata.isEmpty() && social.isEmpty();
        }
    }

    private final Residence plugin;
    private volatile Map<String, ResidenceGuiMetadata> metadataMap = new ConcurrentHashMap<>();
    private volatile Map<UUID, Map<String, SocialState>> socialMap = new ConcurrentHashMap<>();
    private volatile Map<String, Integer> likeCounts = new ConcurrentHashMap<>();
    private volatile YamlGuiData yamlFallbackData = null;

    public ResidenceGuiMetaManager(Residence plugin) {
        this.plugin = plugin;
    }

    public static String qualifiedKey(String serverName, String resName) {
        String srv = (serverName == null || serverName.trim().isEmpty()) ? "default" : serverName.trim().toLowerCase();
        String key = resName == null ? "" : resName.trim().toLowerCase();
        return srv + ":" + key;
    }

    public String localServerName() {
        return plugin.getStorageServerName();
    }

    public void load() {
        yamlFallbackData = null;
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db != null && db.isInitialized()) {
            if (hasLocalYamlFile()) {
                try {
                    if (db.loadDocuments(localServerName()).isEmpty()) {
                        YamlGuiData fallback = readLocalYamlData();
                        if (!fallback.isEmpty()) {
                            yamlFallbackData = fallback;
                        }
                    }
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.WARNING, "Failed to check database save state while loading GUI metadata", ex);
                }
            }
            if (refreshFromDatabaseNow()) {
                return;
            }
            plugin.getLogger().warning("Failed to load GUI metadata from database, falling back to local YAML");
        }

        loadYaml();
    }

    public void clearYamlFallbackFlag() {
        this.yamlFallbackData = null;
    }

    public boolean loadYamlIfEmpty() {
        if (!metadataMap.isEmpty() || !socialMap.isEmpty() || !hasLocalYamlFile())
            return false;
        loadYaml();
        return !metadataMap.isEmpty() || !socialMap.isEmpty();
    }

    private boolean hasLocalYamlFile() {
        File file = getYamlFile();
        return file.isFile() && file.length() > 0;
    }

    public boolean refreshFromDatabaseNow() {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized())
            return false;
        try {
            Map<String, ResidenceGuiMetadata> loadedMeta = db.loadAllGuiMetadata();
            Map<UUID, Map<String, SocialState>> loadedSocial = db.loadAllSocialStates();
            Map<String, Integer> loadedLikes = db.loadLikeCounts();

            Map<String, ResidenceGuiMetadata> nextMeta = new ConcurrentHashMap<>();
            Map<UUID, Map<String, SocialState>> nextSocial = new ConcurrentHashMap<>();
            Map<String, Integer> nextLikes = new ConcurrentHashMap<>();

            YamlGuiData fallback = this.yamlFallbackData;
            if (fallback != null && !fallback.isEmpty()) {
                nextMeta.putAll(fallback.getMetadata());
                for (Entry<UUID, Map<String, SocialState>> e : fallback.getSocial().entrySet()) {
                    nextSocial.put(e.getKey(), new ConcurrentHashMap<>(e.getValue()));
                }
            }

            nextMeta.putAll(loadedMeta);
            for (Entry<UUID, Map<String, SocialState>> e : loadedSocial.entrySet()) {
                nextSocial.computeIfAbsent(e.getKey(), k -> new ConcurrentHashMap<>()).putAll(e.getValue());
            }

            if (fallback != null && !fallback.isEmpty()) {
                for (Map<String, SocialState> perPlayer : nextSocial.values()) {
                    for (Entry<String, SocialState> r : perPlayer.entrySet()) {
                        if (r.getValue() != null && r.getValue().isLiked()) {
                            nextLikes.merge(r.getKey(), 1, Integer::sum);
                        }
                    }
                }
            } else {
                nextLikes.putAll(loadedLikes);
            }

            this.metadataMap = nextMeta;
            this.socialMap = nextSocial;
            this.likeCounts = nextLikes;
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to refresh GUI metadata from database", ex);
            return false;
        }
    }

    public void onResidenceRemoved(String resName) {
        if (resName == null || resName.isEmpty())
            return;
        String serverName = localServerName();
        String key = qualifiedKey(serverName, resName);
        boolean changed = metadataMap.remove(key) != null;
        if (likeCounts.remove(key) != null)
            changed = true;
        for (Map<String, SocialState> perPlayer : socialMap.values()) {
            if (perPlayer.remove(key) != null)
                changed = true;
        }

        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db != null && db.isInitialized()) {
            CMIScheduler.runTaskAsynchronously(plugin, () -> {
                try {
                    db.deleteGuiDataForResidence(serverName, resName);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.WARNING, "Failed to delete GUI metadata from database for " + resName, ex);
                }
            });
        } else if (changed) {
            CMIScheduler.runTaskAsynchronously(plugin, this::saveYaml);
        }
    }

    public void onResidenceRenamed(String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equalsIgnoreCase(newName))
            return;
        String serverName = localServerName();
        String oldKey = qualifiedKey(serverName, oldName);
        String newKey = qualifiedKey(serverName, newName);
        boolean changed = false;

        ResidenceGuiMetadata movedMeta = metadataMap.remove(oldKey);
        if (movedMeta != null) {
            metadataMap.put(newKey, movedMeta);
            changed = true;
        }
        Integer movedLikes = likeCounts.remove(oldKey);
        if (movedLikes != null) {
            likeCounts.put(newKey, movedLikes);
            changed = true;
        }
        for (Map<String, SocialState> perPlayer : socialMap.values()) {
            SocialState movedState = perPlayer.remove(oldKey);
            if (movedState != null) {
                perPlayer.put(newKey, movedState);
                changed = true;
            }
        }

        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db != null && db.isInitialized()) {
            CMIScheduler.runTaskAsynchronously(plugin, () -> {
                try {
                    db.renameGuiDataForResidence(serverName, oldName, newName);
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.WARNING, "Failed to rename GUI metadata in database from " + oldName + " to " + newName, ex);
                }
            });
        } else if (changed) {
            CMIScheduler.runTaskAsynchronously(plugin, this::saveYaml);
        }
    }

    @SuppressWarnings("unchecked")
    public YamlGuiData readLocalYamlData() {
        Map<String, ResidenceGuiMetadata> nextMeta = new ConcurrentHashMap<>();
        Map<UUID, Map<String, SocialState>> nextSocial = new ConcurrentHashMap<>();
        Map<String, Integer> nextLikes = new ConcurrentHashMap<>();

        File file = getYamlFile();
        if (!file.isFile() || file.length() == 0) {
            return new YamlGuiData(nextMeta, nextSocial, nextLikes);
        }
        try {
            YMLSaveHelper yml = new YMLSaveHelper(file);
            yml.load();
            Map<String, Object> root = yml.getRoot();
            if (root == null) {
                return new YamlGuiData(nextMeta, nextSocial, nextLikes);
            }

            Object metaObj = root.get("Metadata");
            if (metaObj instanceof Map) {
                Map<String, Object> metaSection = (Map<String, Object>) metaObj;
                for (Entry<String, Object> e : metaSection.entrySet()) {
                    if (!(e.getValue() instanceof Map))
                        continue;
                    Map<String, Object> vals = (Map<String, Object>) e.getValue();
                    String alias = vals.get("Alias") instanceof String ? (String) vals.get("Alias") : null;
                    String desc = vals.get("Description") instanceof String ? (String) vals.get("Description") : null;
                    String icon = vals.get("Icon") instanceof String ? (String) vals.get("Icon") : null;
                    boolean listed = !(vals.get("Listed") instanceof Boolean) || (Boolean) vals.get("Listed");
                    nextMeta.put(e.getKey().toLowerCase(), new ResidenceGuiMetadata(alias, desc, icon, listed));
                }
            }

            Object socialObj = root.get("Social");
            if (socialObj instanceof Map) {
                Map<String, Object> socialSection = (Map<String, Object>) socialObj;
                for (Entry<String, Object> playerEntry : socialSection.entrySet()) {
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(playerEntry.getKey());
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    if (!(playerEntry.getValue() instanceof Map))
                        continue;
                    Map<String, SocialState> perPlayer = new ConcurrentHashMap<>();
                    Map<String, Object> entries = (Map<String, Object>) playerEntry.getValue();
                    for (Entry<String, Object> resEntry : entries.entrySet()) {
                        if (!(resEntry.getValue() instanceof Map))
                            continue;
                        Map<String, Object> stateVals = (Map<String, Object>) resEntry.getValue();
                        boolean liked = Boolean.TRUE.equals(stateVals.get("Liked"));
                        boolean pinned = Boolean.TRUE.equals(stateVals.get("Pinned"));
                        if (liked || pinned) {
                            String qKey = resEntry.getKey().toLowerCase();
                            perPlayer.put(qKey, new SocialState(liked, pinned));
                            if (liked) {
                                nextLikes.merge(qKey, 1, Integer::sum);
                            }
                        }
                    }
                    if (!perPlayer.isEmpty()) {
                        nextSocial.put(uuid, perPlayer);
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to load Save/" + YAML_FILE_NAME, ex);
        }
        return new YamlGuiData(nextMeta, nextSocial, nextLikes);
    }

    private void loadYaml() {
        YamlGuiData data = readLocalYamlData();
        this.metadataMap = data.getMetadata();
        this.socialMap = data.getSocial();
        this.likeCounts = data.getLikeCounts();
    }

    public synchronized void saveYaml() {
        saveYaml(getYamlFile());
    }

    public synchronized void saveYaml(File file) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> metaSection = new LinkedHashMap<>();
        for (Entry<String, ResidenceGuiMetadata> e : metadataMap.entrySet()) {
            ResidenceGuiMetadata meta = e.getValue();
            if (meta == null || meta.isDefault())
                continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            if (meta.getAlias() != null)
                entry.put("Alias", meta.getAlias());
            if (meta.getDescription() != null)
                entry.put("Description", meta.getDescription());
            if (meta.getIcon() != null)
                entry.put("Icon", meta.getIcon());
            entry.put("Listed", meta.isListed());
            metaSection.put(e.getKey(), entry);
        }
        root.put("Metadata", metaSection);

        Map<String, Object> socialSection = new LinkedHashMap<>();
        for (Entry<UUID, Map<String, SocialState>> pEntry : socialMap.entrySet()) {
            Map<String, Object> resEntries = new LinkedHashMap<>();
            for (Entry<String, SocialState> rEntry : pEntry.getValue().entrySet()) {
                SocialState st = rEntry.getValue();
                if (st == null || (!st.isLiked() && !st.isPinned()))
                    continue;
                Map<String, Object> stateMap = new LinkedHashMap<>();
                stateMap.put("Liked", st.isLiked());
                stateMap.put("Pinned", st.isPinned());
                resEntries.put(rEntry.getKey(), stateMap);
            }
            if (!resEntries.isEmpty())
                socialSection.put(pEntry.getKey().toString(), resEntries);
        }
        root.put("Social", socialSection);

        try {
            Path target = file.toPath();
            Path dir = target.getParent();
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, "gui_meta.yml.", ".tmp");
            try {
                YMLSaveHelper yml = new YMLSaveHelper(temp.toFile());
                yml.getRoot().putAll(root);
                yml.save();
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException | UnsupportedOperationException ex) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to save Save/gui_meta.yml", ex);
        }
    }

    private File getYamlFile() {
        return new File(new File(plugin.getDataFolder(), "Save"), YAML_FILE_NAME);
    }

    public boolean hasMetadata(String serverName, String resName) {
        return metadataMap.containsKey(qualifiedKey(serverName, resName));
    }

    public ResidenceGuiMetadata getMetadata(String serverName, String resName) {
        String key = qualifiedKey(serverName, resName);
        ResidenceGuiMetadata meta = metadataMap.get(key);
        return meta == null ? new ResidenceGuiMetadata() : meta;
    }

    public ResidenceGuiMetadata getOrCreateMetadata(String serverName, String resName) {
        String key = qualifiedKey(serverName, resName);
        return metadataMap.computeIfAbsent(key, k -> new ResidenceGuiMetadata());
    }

    public void updateMetadata(String serverName, String resName, ResidenceGuiMetadata meta) {
        String key = qualifiedKey(serverName, resName);
        metadataMap.put(key, meta);

        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db != null && db.isInitialized()) {
            org.bukkit.Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try {
                    db.saveGuiMetadata(serverName, resName.toLowerCase(), meta);
                    if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
                        if (serverName.equalsIgnoreCase(localServerName())) {
                            plugin.getCrossServerManager().syncLocalIndexNow();
                        } else {
                            plugin.getCrossServerManager().refreshRemoteCacheNow();
                        }
                    }
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.WARNING, "Failed to save GUI metadata to database", ex);
                }
            });
        } else {
            org.bukkit.Bukkit.getAsyncScheduler().runNow(plugin, task -> saveYaml());
        }
    }

    public boolean isLiked(UUID playerUuid, String serverName, String resName) {
        if (playerUuid == null)
            return false;
        Map<String, SocialState> map = socialMap.get(playerUuid);
        if (map == null)
            return false;
        SocialState st = map.get(qualifiedKey(serverName, resName));
        return st != null && st.isLiked();
    }

    public boolean isPinned(UUID playerUuid, String serverName, String resName) {
        if (playerUuid == null)
            return false;
        Map<String, SocialState> map = socialMap.get(playerUuid);
        if (map == null)
            return false;
        SocialState st = map.get(qualifiedKey(serverName, resName));
        return st != null && st.isPinned();
    }

    public boolean hasLikeData(String serverName, String resName) {
        return likeCounts.containsKey(qualifiedKey(serverName, resName));
    }

    public int getLikes(String serverName, String resName) {
        return likeCounts.getOrDefault(qualifiedKey(serverName, resName), 0);
    }

    public boolean toggleLike(UUID playerUuid, String serverName, String resName) {
        String key = qualifiedKey(serverName, resName);
        Map<String, SocialState> perPlayer = socialMap.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());
        SocialState prev = perPlayer.get(key);
        boolean newLiked = prev == null || !prev.isLiked();
        boolean pinned = prev != null && prev.isPinned();

        if (!newLiked && !pinned) {
            perPlayer.remove(key);
        } else {
            perPlayer.put(key, new SocialState(newLiked, pinned));
        }

        likeCounts.compute(key, (k, v) -> {
            int cur = v == null ? 0 : v;
            int updated = newLiked ? cur + 1 : Math.max(0, cur - 1);
            return updated == 0 ? null : updated;
        });

        persistSocial(playerUuid, serverName, resName, newLiked, pinned);
        return newLiked;
    }

    public boolean togglePin(UUID playerUuid, String serverName, String resName) {
        String key = qualifiedKey(serverName, resName);
        Map<String, SocialState> perPlayer = socialMap.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());
        SocialState prev = perPlayer.get(key);
        boolean liked = prev != null && prev.isLiked();
        boolean newPinned = prev == null || !prev.isPinned();

        if (!liked && !newPinned) {
            perPlayer.remove(key);
        } else {
            perPlayer.put(key, new SocialState(liked, newPinned));
        }

        persistSocial(playerUuid, serverName, resName, liked, newPinned);
        return newPinned;
    }

    private void persistSocial(UUID playerUuid, String serverName, String resName, boolean liked, boolean pinned) {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db != null && db.isInitialized()) {
            org.bukkit.Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try {
                    db.saveSocialState(playerUuid, serverName, resName.toLowerCase(), liked, pinned);
                    if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
                        if (serverName.equalsIgnoreCase(localServerName())) {
                            plugin.getCrossServerManager().syncLocalIndexNow();
                        } else {
                            plugin.getCrossServerManager().refreshRemoteCacheNow();
                        }
                    }
                } catch (SQLException ex) {
                    plugin.getLogger().log(Level.WARNING, "Failed to save GUI social state to database", ex);
                }
            });
        } else {
            org.bukkit.Bukkit.getAsyncScheduler().runNow(plugin, task -> saveYaml());
        }
    }

    public Map<String, ResidenceGuiMetadata> snapshotMetadata() {
        return new HashMap<>(metadataMap);
    }

    public Map<UUID, Map<String, SocialState>> snapshotSocial() {
        Map<UUID, Map<String, SocialState>> copy = new HashMap<>();
        for (Entry<UUID, Map<String, SocialState>> e : socialMap.entrySet()) {
            copy.put(e.getKey(), new HashMap<>(e.getValue()));
        }
        return copy;
    }
}
