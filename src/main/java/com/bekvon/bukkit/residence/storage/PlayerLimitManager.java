package com.bekvon.bukkit.residence.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;

public class PlayerLimitManager {

    public enum LimitType {
        RES("res", "extra_res", "override_res"),
        X("x", "extra_x", "override_x"),
        Y("y", "extra_y", "override_y"),
        Z("z", "extra_z", "override_z"),
        SUBZONES("subzones", "extra_subzones", "override_subzones"),
        RENTS("rents", "extra_rents", "override_rents");

        private final String key;
        private final String extraColumn;
        private final String overrideColumn;

        LimitType(String key, String extraColumn, String overrideColumn) {
            this.key = key;
            this.extraColumn = extraColumn;
            this.overrideColumn = overrideColumn;
        }

        public String getKey() {
            return key;
        }

        public String getExtraColumn() {
            return extraColumn;
        }

        public String getOverrideColumn() {
            return overrideColumn;
        }

        public static LimitType parse(String raw) {
            if (raw == null)
                return null;
            switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "res":
            case "zone":
            case "zones":
            case "maxres":
            case "amount":
                return RES;
            case "x":
            case "ew":
            case "maxx":
                return X;
            case "y":
            case "ud":
            case "height":
            case "maxy":
                return Y;
            case "z":
            case "ns":
            case "maxz":
                return Z;
            case "sub":
            case "subzone":
            case "subzones":
                return SUBZONES;
            case "rent":
            case "rents":
                return RENTS;
            default:
                return null;
            }
        }
    }

    public static final class PlayerLimitData {
        public static final PlayerLimitData EMPTY = new PlayerLimitData(0, 0, 0, 0, 0, 0, null, null, null, null, null, null);

        private final int extraRes;
        private final int extraX;
        private final int extraY;
        private final int extraZ;
        private final int extraSubzones;
        private final int extraRents;
        private final Integer overrideRes;
        private final Integer overrideX;
        private final Integer overrideY;
        private final Integer overrideZ;
        private final Integer overrideSubzones;
        private final Integer overrideRents;

        public PlayerLimitData(
                int extraRes, int extraX, int extraY, int extraZ, int extraSubzones, int extraRents,
                Integer overrideRes, Integer overrideX, Integer overrideY, Integer overrideZ, Integer overrideSubzones, Integer overrideRents) {
            this.extraRes = Math.max(0, extraRes);
            this.extraX = Math.max(0, extraX);
            this.extraY = Math.max(0, extraY);
            this.extraZ = Math.max(0, extraZ);
            this.extraSubzones = Math.max(0, extraSubzones);
            this.extraRents = Math.max(0, extraRents);
            this.overrideRes = overrideRes == null ? null : Math.max(0, overrideRes);
            this.overrideX = overrideX == null ? null : Math.max(0, overrideX);
            this.overrideY = overrideY == null ? null : Math.max(0, overrideY);
            this.overrideZ = overrideZ == null ? null : Math.max(0, overrideZ);
            this.overrideSubzones = overrideSubzones == null ? null : Math.max(0, overrideSubzones);
            this.overrideRents = overrideRents == null ? null : Math.max(0, overrideRents);
        }

        public int getExtra(LimitType type) {
            switch (type) {
            case RES: return extraRes;
            case X: return extraX;
            case Y: return extraY;
            case Z: return extraZ;
            case SUBZONES: return extraSubzones;
            case RENTS: return extraRents;
            default: return 0;
            }
        }

        public Integer getOverride(LimitType type) {
            switch (type) {
            case RES: return overrideRes;
            case X: return overrideX;
            case Y: return overrideY;
            case Z: return overrideZ;
            case SUBZONES: return overrideSubzones;
            case RENTS: return overrideRents;
            default: return null;
            }
        }

        public boolean isEmpty() {
            return extraRes == 0 && extraX == 0 && extraY == 0 && extraZ == 0 && extraSubzones == 0 && extraRents == 0
                    && overrideRes == null && overrideX == null && overrideY == null
                    && overrideZ == null && overrideSubzones == null && overrideRents == null;
        }

        public PlayerLimitData withExtraDelta(LimitType type, int delta) {
            int er = extraRes, ex = extraX, ey = extraY, ez = extraZ, es = extraSubzones, ert = extraRents;
            switch (type) {
            case RES: er = clampNonNegativeAdd(er, delta); break;
            case X: ex = clampNonNegativeAdd(ex, delta); break;
            case Y: ey = clampNonNegativeAdd(ey, delta); break;
            case Z: ez = clampNonNegativeAdd(ez, delta); break;
            case SUBZONES: es = clampNonNegativeAdd(es, delta); break;
            case RENTS: ert = clampNonNegativeAdd(ert, delta); break;
            }
            return new PlayerLimitData(er, ex, ey, ez, es, ert,
                    overrideRes, overrideX, overrideY, overrideZ, overrideSubzones, overrideRents);
        }

        public PlayerLimitData withOverride(LimitType type, Integer value) {
            int er = extraRes, ex = extraX, ey = extraY, ez = extraZ, es = extraSubzones, ert = extraRents;
            Integer or = overrideRes, ox = overrideX, oy = overrideY, oz = overrideZ, os = overrideSubzones, ort = overrideRents;
            Integer clamped = value == null ? null : Math.max(0, value);
            switch (type) {
            case RES: or = clamped; er = 0; break;
            case X: ox = clamped; ex = 0; break;
            case Y: oy = clamped; ey = 0; break;
            case Z: oz = clamped; ez = 0; break;
            case SUBZONES: os = clamped; es = 0; break;
            case RENTS: ort = clamped; ert = 0; break;
            }
            return new PlayerLimitData(er, ex, ey, ez, es, ert, or, ox, oy, oz, os, ort);
        }

        private static final int MAX_CLAMP = 1_000_000;

        private static int clampNonNegativeAdd(int current, int delta) {
            long sum = (long) current + delta;
            if (sum <= 0L)
                return 0;
            if (sum > MAX_CLAMP)
                return MAX_CLAMP;
            return (int) sum;
        }
    }

    private final Residence plugin;
    private final ConcurrentHashMap<UUID, PlayerLimitData> cache = new ConcurrentHashMap<>();
    private final Object yamlLock = new Object();

    public PlayerLimitManager(Residence plugin) {
        this.plugin = plugin;
    }

    private boolean isDatabaseReady() {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        return db != null && db.isInitialized();
    }

    private File getYamlFile() {
        return new File(new File(plugin.getDataFolder(), "Save"), "player_limits.yml");
    }

    public void load() {
        cache.clear();
        if (isDatabaseReady()) {
            refreshAllFromDatabaseNow();
        } else {
            loadYaml();
        }
    }

    public void refreshAllFromDatabaseNow() {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized())
            return;
        try {
            Map<UUID, PlayerLimitData> loaded = db.loadAllPlayerLimits();
            cache.clear();
            cache.putAll(loaded);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to load player limits from database", ex);
        }
    }

    public void refreshPlayerAsync(UUID uuid) {
        if (uuid == null || !isDatabaseReady() || !plugin.isEnabled())
            return;
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                PlayerLimitData loaded = db.loadPlayerLimit(uuid);
                if (loaded == null || loaded.isEmpty()) {
                    cache.remove(uuid);
                } else {
                    cache.put(uuid, loaded);
                }
            } catch (SQLException ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to refresh player limits for " + uuid, ex);
            }
        });
    }

    public PlayerLimitData get(UUID uuid) {
        if (uuid == null)
            return PlayerLimitData.EMPTY;
        PlayerLimitData data = cache.get(uuid);
        return data == null ? PlayerLimitData.EMPTY : data;
    }

    public int getExtra(UUID uuid, LimitType type) {
        if (uuid == null || type == null)
            return 0;
        return get(uuid).getExtra(type);
    }

    public Integer getOverride(UUID uuid, LimitType type) {
        if (uuid == null || type == null)
            return null;
        return get(uuid).getOverride(type);
    }

    private static final int UNLIMITED_COUNT_SENTINEL = 9999;

    public int applyLimit(UUID uuid, LimitType type, int baseValue) {
        if (uuid == null || type == null)
            return baseValue;
        PlayerLimitData data = cache.get(uuid);
        if (data == null)
            return baseValue;

        Integer override = data.getOverride(type);
        int extra = data.getExtra(type);
        boolean isCountType = type == LimitType.RES || type == LimitType.SUBZONES || type == LimitType.RENTS;

        if (override == null && isCountType && baseValue >= UNLIMITED_COUNT_SENTINEL) {
            return UNLIMITED_COUNT_SENTINEL;
        }
        int effectiveBase = override != null ? override : Math.max(0, baseValue);
        long total = (long) effectiveBase + extra;
        if (total <= 0L)
            return 0;
        int cap = isCountType ? UNLIMITED_COUNT_SENTINEL : PlayerLimitData.MAX_CLAMP;
        if (total > cap)
            return cap;
        return (int) total;
    }

    public PlayerLimitData addLimit(UUID uuid, String playerName, LimitType type, int delta) throws SQLException {
        if (uuid == null || type == null)
            return PlayerLimitData.EMPTY;
        if (isDatabaseReady()) {
            PlayerLimitData updated = plugin.getDatabaseStorageManager().addPlayerLimit(uuid, playerName, type, delta);
            updateCache(uuid, updated);
            return updated;
        }
        PlayerLimitData updated = get(uuid).withExtraDelta(type, delta);
        updateCache(uuid, updated);
        saveYaml();
        return updated;
    }

    public PlayerLimitData setLimit(UUID uuid, String playerName, LimitType type, int value) throws SQLException {
        if (uuid == null || type == null)
            return PlayerLimitData.EMPTY;
        int clean = Math.max(0, value);
        if (isDatabaseReady()) {
            PlayerLimitData updated = plugin.getDatabaseStorageManager().setPlayerLimit(uuid, playerName, type, clean);
            updateCache(uuid, updated);
            return updated;
        }
        PlayerLimitData updated = get(uuid).withOverride(type, clean);
        updateCache(uuid, updated);
        saveYaml();
        return updated;
    }

    public PlayerLimitData resetLimit(UUID uuid, String playerName, LimitType type) throws SQLException {
        if (uuid == null)
            return PlayerLimitData.EMPTY;
        if (isDatabaseReady()) {
            PlayerLimitData updated = plugin.getDatabaseStorageManager().resetPlayerLimit(uuid, type);
            updateCache(uuid, updated);
            return updated;
        }
        PlayerLimitData updated = type == null ? PlayerLimitData.EMPTY : get(uuid).withOverride(type, null);
        updateCache(uuid, updated);
        saveYaml();
        return updated;
    }

    private void updateCache(UUID uuid, PlayerLimitData updated) {
        if (updated == null || updated.isEmpty()) {
            cache.remove(uuid);
        } else {
            cache.put(uuid, updated);
        }
    }

    private void loadYaml() {
        File file = getYamlFile();
        if (!file.exists())
            return;
        synchronized (yamlLock) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection players = yml.getConfigurationSection("Players");
            if (players == null)
                return;
            for (String key : players.getKeys(false)) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(key);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                ConfigurationSection sec = players.getConfigurationSection(key);
                if (sec == null)
                    continue;
                PlayerLimitData data = new PlayerLimitData(
                        sec.getInt("extra.res", 0),
                        sec.getInt("extra.x", 0),
                        sec.getInt("extra.y", 0),
                        sec.getInt("extra.z", 0),
                        sec.getInt("extra.subzones", 0),
                        sec.getInt("extra.rents", 0),
                        sec.contains("override.res") ? sec.getInt("override.res") : null,
                        sec.contains("override.x") ? sec.getInt("override.x") : null,
                        sec.contains("override.y") ? sec.getInt("override.y") : null,
                        sec.contains("override.z") ? sec.getInt("override.z") : null,
                        sec.contains("override.subzones") ? sec.getInt("override.subzones") : null,
                        sec.contains("override.rents") ? sec.getInt("override.rents") : null);
                if (!data.isEmpty()) {
                    cache.put(uuid, data);
                }
            }
        }
    }

    public void saveYaml() {
        synchronized (yamlLock) {
            File file = getYamlFile();
            if (file.getParentFile() != null)
                file.getParentFile().mkdirs();
            YamlConfiguration yml = new YamlConfiguration();
            for (Map.Entry<UUID, PlayerLimitData> entry : cache.entrySet()) {
                PlayerLimitData d = entry.getValue();
                if (d == null || d.isEmpty())
                    continue;
                String base = "Players." + entry.getKey().toString();
                for (LimitType t : LimitType.values()) {
                    int extra = d.getExtra(t);
                    if (extra != 0)
                        yml.set(base + ".extra." + t.getKey(), extra);
                    Integer ov = d.getOverride(t);
                    if (ov != null)
                        yml.set(base + ".override." + t.getKey(), ov);
                }
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
                plugin.getLogger().log(Level.WARNING, "Failed to save player_limits.yml", ex);
            }
        }
    }
}
