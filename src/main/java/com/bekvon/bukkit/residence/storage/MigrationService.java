package com.bekvon.bukkit.residence.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.gui.ResidenceGuiMetaManager;
import com.bekvon.bukkit.residence.gui.ResidenceGuiMetadata;
import com.bekvon.bukkit.residence.persistance.YMLSaveHelper;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagState;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager.SocialState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class MigrationService {

    public static final class MigrationReport {
        public int importedResidences = 0;
        public int importedSubzones = 0;
        public int importedAreas = 0;
        public int worldsProcessed = 0;
        public int skipped = 0;
        public int errors = 0;
        public final List<String> details = new ArrayList<>();

        public String toSummary(String title) {
            return "&a[Residence Migrate] &e" + title
                    + " &7| &aResidences: &f" + importedResidences
                    + (importedSubzones > 0 ? " &7| &aSubzones: &f" + importedSubzones : "")
                    + (importedAreas > 0 ? " &7| &aAreas: &f" + importedAreas : "")
                    + (worldsProcessed > 0 ? " &7| &aWorlds: &f" + worldsProcessed : "")
                    + " &7| &eSkipped: &f" + skipped
                    + " &7| &cErrors: &f" + errors;
        }
    }

    public static MigrationReport migrateYamlToDatabase(Residence plugin) throws Exception {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized()) {
            throw new IllegalStateException("Database storage is not configured/initialized in config.yml (Storage.Type).");
        }

        MigrationReport report = new MigrationReport();
        String serverName = plugin.getStorageServerName();

        if (!db.loadDocuments(serverName).isEmpty())
            throw new IllegalStateException("The database already contains a save for this server.");
        Map<String, String> worldDocuments = new LinkedHashMap<>();
        Map<String, Object> worlds = plugin.getResidenceManager().save();
        for (Entry<String, Object> entry : worlds.entrySet()) {
            String world = entry.getKey();
            Map<String, Object> records = new LinkedHashMap<>();
            Map<?, ?> messages = plugin.getResidenceManager().getMessageCatch(world);
            if (messages != null)
                records.put("Messages", messages);
            Map<?, ?> flags = plugin.getResidenceManager().getFlagsCatch(world);
            if (flags != null)
                records.put("Flags", flags);
            records.put("Residences", entry.getValue());

            worldDocuments.put(world, YMLSaveHelper.dumpToString(records));
            report.worldsProcessed++;
            if (entry.getValue() instanceof Map) {
                report.importedResidences += ((Map<?, ?>) entry.getValue()).size();
            }
        }

        Map<String, String> auxiliary = new LinkedHashMap<>();
        auxiliary.put("forsale", YMLSaveHelper.dumpToString(java.util.Collections.singletonMap("Economy", plugin.getTransactionManager().save())));
        auxiliary.put("leases", YMLSaveHelper.dumpToString(java.util.Collections.singletonMap("Leases", plugin.getLeaseManager().save())));
        auxiliary.put("permlists", YMLSaveHelper.dumpToString(java.util.Collections.singletonMap("PermissionLists", plugin.getPermissionListManager().save())));
        auxiliary.put("rent", YMLSaveHelper.dumpToString(java.util.Collections.singletonMap("RentSystem", plugin.getRentManager().save())));
        Map<String, Map<String, String>> documents = new LinkedHashMap<>();
        documents.put("world", worldDocuments);
        documents.put("aux", auxiliary);
        db.saveDocuments(serverName, documents);

        ResidenceGuiMetaManager guiMeta = plugin.getGuiMetaManager();
        if (guiMeta != null) {
            ResidenceGuiMetaManager.YamlGuiData yamlData = guiMeta.readLocalYamlData();
            Map<String, ResidenceGuiMetadata> mergedMeta = new LinkedHashMap<>(yamlData.getMetadata());
            for (Entry<String, ResidenceGuiMetadata> e : guiMeta.snapshotMetadata().entrySet()) {
                int idx = e.getKey().indexOf(':');
                String srv = idx > 0 ? e.getKey().substring(0, idx) : serverName;
                if (srv.equalsIgnoreCase(serverName)) {
                    mergedMeta.put(e.getKey(), e.getValue());
                }
            }
            for (Entry<String, ResidenceGuiMetadata> e : mergedMeta.entrySet()) {
                int idx = e.getKey().indexOf(':');
                String srv = idx > 0 ? e.getKey().substring(0, idx) : serverName;
                String resKey = idx > 0 ? e.getKey().substring(idx + 1) : e.getKey();
                db.saveGuiMetadata(srv, resKey, e.getValue());
            }

            Map<UUID, Map<String, SocialState>> mergedSocial = new LinkedHashMap<>();
            for (Entry<UUID, Map<String, SocialState>> p : yamlData.getSocial().entrySet()) {
                mergedSocial.put(p.getKey(), new LinkedHashMap<>(p.getValue()));
            }
            for (Entry<UUID, Map<String, SocialState>> p : guiMeta.snapshotSocial().entrySet()) {
                for (Entry<String, SocialState> r : p.getValue().entrySet()) {
                    int idx = r.getKey().indexOf(':');
                    String srv = idx > 0 ? r.getKey().substring(0, idx) : serverName;
                    if (srv.equalsIgnoreCase(serverName)) {
                        mergedSocial.computeIfAbsent(p.getKey(), k -> new LinkedHashMap<>()).put(r.getKey(), r.getValue());
                    }
                }
            }
            for (Entry<UUID, Map<String, SocialState>> p : mergedSocial.entrySet()) {
                for (Entry<String, SocialState> r : p.getValue().entrySet()) {
                    int idx = r.getKey().indexOf(':');
                    String srv = idx > 0 ? r.getKey().substring(0, idx) : serverName;
                    String resKey = idx > 0 ? r.getKey().substring(idx + 1) : r.getKey();
                    db.saveSocialState(p.getKey(), srv, resKey, r.getValue().isLiked(), r.getValue().isPinned());
                }
            }
            guiMeta.clearYamlFallbackFlag();
            guiMeta.refreshFromDatabaseNow();
        }

        if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
            plugin.getCrossServerManager().syncLocalIndexNow();
        }
        return report;
    }

    public static MigrationReport migrateDatabaseToYaml(Residence plugin) throws Exception {
        DatabaseStorageManager db = plugin.getDatabaseStorageManager();
        if (db == null || !db.isInitialized()) {
            throw new IllegalStateException("Database storage is not configured/initialized in config.yml (Storage.Type).");
        }

        MigrationReport report = new MigrationReport();
        String serverName = plugin.getStorageServerName();
        Map<String, Map<String, String>> documents = db.loadDocuments(serverName);
        if (documents.isEmpty())
            throw new IllegalStateException("No SQL save exists for this server.");
        File exportRoot = new File(plugin.getDataFolder(), "Exports");
        java.nio.file.Files.createDirectories(exportRoot.toPath());
        File saveFolder = java.nio.file.Files.createTempDirectory(exportRoot.toPath(), "sql-").toFile();
        File worldFolder = new File(saveFolder, "Worlds");
        report.details.add("Export directory: " + saveFolder.getAbsolutePath());

        Map<String, String> worldPayloads = documents.getOrDefault("world", java.util.Collections.emptyMap());
        for (Entry<String, String> e : worldPayloads.entrySet()) {
            Map<String, Object> parsed = YMLSaveHelper.loadFromString(e.getValue());
            if (parsed == null)
                continue;
            File dest = new File(worldFolder, Residence.saveFilePrefix + Residence.getSaveWorldName(e.getKey()) + ".yml");
            Residence.writeSaveRecord(dest, parsed);
            report.worldsProcessed++;
            Object resObj = parsed.get("Residences");
            if (resObj instanceof Map) {
                report.importedResidences += ((Map<?, ?>) resObj).size();
            }
        }

        String[] auxNames = new String[] { "forsale", "leases", "permlists", "rent" };
        Map<String, String> auxiliary = documents.getOrDefault("aux", java.util.Collections.emptyMap());
        for (String aux : auxNames) {
            String payload = auxiliary.get(aux);
            if (payload == null)
                continue;
            Map<String, Object> parsed = YMLSaveHelper.loadFromString(payload);
            if (parsed != null) {
                Residence.writeSaveRecord(new File(saveFolder, aux + ".yml"), parsed);
            }
        }

        if (plugin.getGuiMetaManager() != null) {
            plugin.getGuiMetaManager().refreshFromDatabaseNow();
            plugin.getGuiMetaManager().saveYaml(new File(saveFolder, ResidenceGuiMetaManager.YAML_FILE_NAME));
        }

        return report;
    }

    private static final class GpClaim {
        final String id;
        final String parentId;
        final String worldName;
        final int minX, minY, minZ;
        final int maxX, maxY, maxZ;
        final String ownerRaw;
        final List<String> builders;
        final List<String> containers;
        final List<String> accessors;
        final List<String> managers;
        final boolean restricted;

        GpClaim(String id, String parentId, String worldName,
                int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                String ownerRaw, List<String> builders, List<String> containers,
                List<String> accessors, List<String> managers, boolean restricted) {
            this.id = id;
            this.parentId = parentId;
            this.worldName = worldName;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.ownerRaw = ownerRaw;
            this.builders = builders;
            this.containers = containers;
            this.accessors = accessors;
            this.managers = managers;
            this.restricted = restricted;
        }

        boolean isSubclaim() {
            return parentId != null && !parentId.isEmpty() && !"-1".equals(parentId);
        }
    }

    public static MigrationReport importGriefPrevention(Residence plugin, File claimDataDir, boolean apply) {
        MigrationReport report = new MigrationReport();
        if (claimDataDir == null || !claimDataDir.isDirectory()) {
            report.errors++;
            report.details.add("ClaimData directory not found: " + (claimDataDir == null ? "null" : claimDataDir.getAbsolutePath()));
            return report;
        }

        File[] files = claimDataDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));
        if (files == null || files.length == 0) {
            report.details.add("No GriefPrevention .yml claim files found in " + claimDataDir.getAbsolutePath());
            return report;
        }

        List<GpClaim> topClaims = new ArrayList<>();
        List<GpClaim> subClaims = new ArrayList<>();

        for (File file : files) {
            try {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
                String id = file.getName().substring(0, file.getName().length() - 4);
                String lesser = cfg.getString("Lesser Boundary Corner");
                String greater = cfg.getString("Greater Boundary Corner");
                if (lesser == null || greater == null) {
                    report.skipped++;
                    continue;
                }
                String[] lParts = lesser.split(";");
                String[] gParts = greater.split(";");
                if (lParts.length != 4 || gParts.length != 4 || !lParts[0].equals(gParts[0])) {
                    report.skipped++;
                    continue;
                }
                String worldName = lParts[0];
                int lx = Integer.parseInt(lParts[1]);
                int ly = Integer.parseInt(lParts[2]);
                int lz = Integer.parseInt(lParts[3]);
                int gx = Integer.parseInt(gParts[1]);
                int gy = Integer.parseInt(gParts[2]);
                int gz = Integer.parseInt(gParts[3]);

                String parentId = cfg.getString("Parent Claim ID", "-1");
                String ownerRaw = cfg.getString("Owner", "");
                GpClaim claim = new GpClaim(
                        id,
                        parentId,
                        worldName,
                        Math.min(lx, gx), Math.min(ly, gy), Math.min(lz, gz),
                        Math.max(lx, gx), Math.max(ly, gy), Math.max(lz, gz),
                        ownerRaw,
                        cfg.getStringList("Builders"),
                        cfg.getStringList("Containers"),
                        cfg.getStringList("Accessors"),
                        cfg.getStringList("Managers"),
                        cfg.getBoolean("inheritNothing"));
                if (claim.isSubclaim())
                    subClaims.add(claim);
                else
                    topClaims.add(claim);
            } catch (Exception ex) {
                report.errors++;
                report.details.add("Failed reading " + file.getName() + ": " + ex.getMessage());
            }
        }

        topClaims.sort(Comparator.comparing(c -> c.id));
        subClaims.sort(Comparator.comparing(c -> c.id));

        Map<String, ClaimedResidence> importedByGpId = new HashMap<>();
        Set<String> validParents = new java.util.HashSet<>();

        for (GpClaim claim : topClaims) {
            World world = Bukkit.getWorld(claim.worldName);
            if (world == null) {
                report.skipped++;
                report.details.add("GP #" + claim.id + ": world not loaded (" + claim.worldName + ")");
                continue;
            }
            int minY = clampY(world, claim.minY, true);
            int maxY = getWorldMaxY(world);
            Location loc1 = new Location(world, claim.minX, minY, claim.minZ);
            Location loc2 = new Location(world, claim.maxX, maxY, claim.maxZ);
            CuboidArea area = new CuboidArea(loc1, loc2);

            if (plugin.getResidenceManager().checkAreaCollision(area, null) != null) {
                report.skipped++;
                report.details.add("GP #" + claim.id + ": collides with existing residence");
                continue;
            }

            UUID ownerUuid = parseUuidOrServer(plugin, claim.ownerRaw);
            if (ownerUuid == null) {
                report.skipped++;
                report.details.add("GP #" + claim.id + ": owner could not be resolved.");
                continue;
            }
            validParents.add(claim.id);
            if (!apply) {
                report.importedResidences++;
                report.importedAreas++;
                continue;
            }
            String ownerName = resolveOwnerName(plugin, ownerUuid);
            String resName = uniqueResidenceName(plugin, sanitizeName(ownerName) + "_gp_" + claim.id);

            boolean created = plugin.getResidenceManager().addResidence(null, ownerName, ownerUuid, resName, area, true, false);
            if (!created) {
                report.skipped++;
                continue;
            }

            ClaimedResidence res = plugin.getResidenceManager().getByName(resName);
            if (res != null) {
                applyGpTrust(res, claim);
                importedByGpId.put(claim.id, res);
                report.importedResidences++;
                report.importedAreas++;
            }
        }

        for (GpClaim sub : subClaims) {
            if (!apply) {
                if (validParents.contains(sub.parentId))
                    report.importedSubzones++;
                else
                    report.skipped++;
                continue;
            }
            ClaimedResidence parent = importedByGpId.get(sub.parentId);
            if (parent == null) {
                report.skipped++;
                continue;
            }
            World world = Bukkit.getWorld(sub.worldName);
            if (world == null) {
                report.skipped++;
                continue;
            }
            int minY = clampY(world, sub.minY, true);
            int maxY = getWorldMaxY(world);
            CuboidArea parentMain = parent.getMainArea();
            if (parentMain != null) {
                int pMinY = parentMain.getLowVector().getBlockY();
                int pMaxY = parentMain.getHighVector().getBlockY();
                minY = Math.max(pMinY, Math.min(pMaxY, minY));
                maxY = Math.max(minY, Math.min(pMaxY, maxY));
            }
            Location loc1 = new Location(world, sub.minX, minY, sub.minZ);
            Location loc2 = new Location(world, sub.maxX, maxY, sub.maxZ);
            String subName = sanitizeName("sub_" + sub.id);
            if (parent.addSubzone(null, parent.getOwner(), parent.getOwnerUUID(), loc1, loc2, subName, true)) {
                ClaimedResidence createdSub = parent.getSubzone(subName);
                if (createdSub != null) {
                    if (sub.restricted) {
                        createdSub.getPermissions().setInheritParentFlags(false);
                    } else {
                        createdSub.getPermissions().setInheritParentFlags(true);
                        if (plugin.getConfigManager().flagsInherit())
                            createdSub.getPermissions().setParent(parent.getPermissions());
                    }
                    applyGpTrust(createdSub, sub);
                    report.importedSubzones++;
                }
            } else {
                report.skipped++;
            }
        }

        if (apply && plugin.getCrossServerManager().isEnabled()) {
            plugin.getCrossServerManager().triggerAsyncSync();
        }
        return report;
    }

    private static void applyGpTrust(ClaimedResidence res, GpClaim claim) {
        for (String entry : claim.accessors) {
            applyGpFlagsForEntry(res, entry, Flags.use, Flags.door, Flags.button, Flags.lever);
        }
        for (String entry : claim.containers) {
            applyGpFlagsForEntry(res, entry, Flags.use, Flags.container, Flags.container_put, Flags.container_take);
        }
        for (String entry : claim.builders) {
            applyGpFlagsForEntry(res, entry, Flags.use, Flags.build, Flags.place, Flags.destroy,
                    Flags.container, Flags.container_put, Flags.container_take);
        }
        for (String entry : claim.managers) {
            // GP's permission to delegate trust does not include Residence's general admin permission.
            reportUnmappedGpPermission(res, claim.id, entry);
        }
    }

    private static void reportUnmappedGpPermission(ClaimedResidence residence, String claimId, String entry) {
        Residence.getInstance().getLogger().warning("GP #" + claimId + " -> " + residence.getName()
                + ": review the source Manage trust for " + entry + " before granting Residence admin.");
    }

    private static void applyGpFlagsForEntry(ClaimedResidence res, String rawEntry, Flags... flags) {
        if (rawEntry == null || rawEntry.trim().isEmpty())
            return;
        String trimmed = rawEntry.trim();
        if ("public".equalsIgnoreCase(trimmed)) {
            for (Flags flag : flags) {
                res.getPermissions().setFlag(flag.toString(), FlagState.TRUE);
            }
            return;
        }
        UUID uuid = tryParseUuid(trimmed);
        if (uuid != null) {
            for (Flags flag : flags) {
                res.getPermissions().setPlayerFlag(uuid, flag.toString(), FlagState.TRUE);
            }
        }
    }

    public static MigrationReport importLands(Residence plugin, File landsDir, boolean apply) {
        MigrationReport report = new MigrationReport();
        if (landsDir == null || !landsDir.isDirectory()) {
            report.errors++;
            report.details.add("Lands data directory not found: " + landsDir);
            return report;
        }
        File[] files = landsDir.listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".json"));
        if ((files == null || files.length == 0) && new File(landsDir, "Lands").isDirectory())
            files = new File(landsDir, "Lands").listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".json"));
        if (files == null || files.length == 0) {
            report.errors++;
            report.details.add("No compatible Lands JSON export found. Start Lands to import via its API.");
            return report;
        }

        for (File file : files) {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                JsonObject document = JsonParser.parseReader(reader).getAsJsonObject();
                String fallback = file.getName().substring(0, file.getName().length() - 5);
                String name = document.has("name") && !document.get("name").isJsonNull() ? document.get("name").getAsString() : fallback;
                UUID owner = document.has("owner") && !document.get("owner").isJsonNull() ? tryParseUuid(document.get("owner").getAsString()) : null;
                importLandGeometry(plugin, report, name, owner, extractLandsChunks(document), !extractLandsTrusted(document).isEmpty(), apply);
            } catch (Exception ex) {
                report.errors++;
                report.details.add("Failed reading " + file.getName() + ": " + ex.getMessage());
            }
        }
        if (apply && plugin.getCrossServerManager().isEnabled())
            plugin.getCrossServerManager().triggerAsyncSync();
        return report;
    }

    public static MigrationReport importLiveLands(Residence plugin, org.bukkit.plugin.Plugin source, boolean apply) {
        MigrationReport report = new MigrationReport();
        try {
            ClassLoader loader = source.getClass().getClassLoader();
            Class<?> integration = Class.forName("me.angeschossen.lands.api.LandsIntegration", true, loader);
            Class<?> landType = Class.forName("me.angeschossen.lands.api.land.Land", true, loader);
            Class<?> coordinate = Class.forName("me.angeschossen.lands.api.land.ChunkCoordinate", true, loader);
            Class<?> areaType = Class.forName("me.angeschossen.lands.api.land.Area", true, loader);

            Object api = integration.getMethod("of", org.bukkit.plugin.Plugin.class).invoke(null, plugin);
            Iterable<?> lands = (Iterable<?>) integration.getMethod("getLands").invoke(api);
            java.lang.reflect.Method getChunks = landType.getMethod("getChunks", World.class);
            java.lang.reflect.Method getSubAreas = landType.getMethod("getSubAreas", World.class);
            java.lang.reflect.Method getX = coordinate.getMethod("getX");
            java.lang.reflect.Method getZ = coordinate.getMethod("getZ");

            for (Object land : lands) {
                String name = (String) landType.getMethod("getName").invoke(land);
                UUID owner = (UUID) landType.getMethod("getOwnerUID").invoke(land);
                Map<String, Set<Long>> chunksByWorld = new LinkedHashMap<>();
                boolean hasSubAreas = false;
                for (World world : Bukkit.getWorlds()) {
                    if (!((java.util.Collection<?>) getSubAreas.invoke(land, world)).isEmpty()) {
                        hasSubAreas = true;
                        break;
                    }
                    Set<Long> chunks = new HashSet<>();
                    for (Object chunk : (Iterable<?>) getChunks.invoke(land, world))
                        chunks.add(packChunk((Integer) getX.invoke(chunk), (Integer) getZ.invoke(chunk)));
                    if (!chunks.isEmpty())
                        chunksByWorld.put(world.getName(), chunks);
                }
                if (hasSubAreas) {
                    report.skipped++;
                    report.details.add("Land " + name + ": has subareas; skipped to preserve its existing ownership and access boundaries.");
                    continue;
                }
                Object area = landType.getMethod("getDefaultArea").invoke(land);
                boolean hasTrust = !((java.util.Collection<?>) areaType.getMethod("getTrustedPlayers").invoke(area)).isEmpty();
                importLandGeometry(plugin, report, name, owner, chunksByWorld, hasTrust, apply);
            }
        } catch (ReflectiveOperationException | ClassCastException ex) {
            report.errors++;
            report.details.add("Lands API is unavailable or incompatible: " + ex.getMessage());
        }
        if (apply && plugin.getCrossServerManager().isEnabled())
            plugin.getCrossServerManager().triggerAsyncSync();
        return report;
    }

    private static void importLandGeometry(Residence plugin, MigrationReport report, String name, UUID owner,
            Map<String, Set<Long>> chunksByWorld, boolean hasTrust, boolean apply) {
        if (owner == null || chunksByWorld.isEmpty()) {
            report.skipped++;
            report.details.add("Land " + name + ": missing owner UUID or claimed chunks.");
            return;
        }
        if (hasTrust)
            report.details.add("Land " + name + ": review source trust roles; importing geometry and ownership.");
        boolean multipleWorlds = chunksByWorld.size() > 1;
        String ownerName = resolveOwnerName(plugin, owner);
        for (Entry<String, Set<Long>> worldEntry : chunksByWorld.entrySet()) {
            World world = Bukkit.getWorld(worldEntry.getKey());
            if (world == null) {
                report.skipped++;
                report.details.add("Land " + name + ": world unavailable (" + worldEntry.getKey() + ").");
                continue;
            }
            List<CuboidArea> areas = new ArrayList<>();
            boolean collision = false;
            for (int[] rect : mergeChunksIntoRectangles(worldEntry.getValue())) {
                CuboidArea area = new CuboidArea(new Location(world, rect[0] << 4, getWorldMinY(world), rect[1] << 4),
                        new Location(world, (rect[2] << 4) + 15, getWorldMaxY(world), (rect[3] << 4) + 15));
                if (plugin.getResidenceManager().checkAreaCollision(area, null) != null) {
                    collision = true;
                    break;
                }
                areas.add(area);
            }
            if (collision || areas.isEmpty()) {
                report.skipped++;
                report.details.add("Land " + name + " in " + world.getName() + ": no importable footprint or overlapping Residence.");
                continue;
            }
            if (!apply) {
                report.importedResidences++;
                report.importedAreas += areas.size();
                continue;
            }
            String candidate = sanitizeName(name) + (multipleWorlds ? "_" + sanitizeName(world.getName()) : "");
            String resName = uniqueResidenceName(plugin, candidate);
            if (!plugin.getResidenceManager().addResidence(null, ownerName, owner, resName, areas.get(0), true, false)) {
                report.skipped++;
                continue;
            }
            ClaimedResidence imported = plugin.getResidenceManager().getByName(resName);
            report.importedResidences++;
            report.importedAreas++;
            for (int index = 1; index < areas.size(); index++) {
                if (imported != null && imported.addArea(areas.get(index), "area_" + (index + 1)))
                    report.importedAreas++;
                else {
                    report.errors++;
                    report.details.add("Incomplete footprint in " + resName + " at area " + (index + 1) + ".");
                }
            }
        }
    }

    private static Map<String, Set<Long>> extractLandsChunks(JsonObject obj) {
        Map<String, Set<Long>> result = new LinkedHashMap<>();
        if (!obj.has("chunks") || obj.get("chunks").isJsonNull())
            return result;

        JsonElement chunksElem = obj.get("chunks");
        if (chunksElem.isJsonObject()) {
            for (Entry<String, JsonElement> worldEntry : chunksElem.getAsJsonObject().entrySet()) {
                String world = worldEntry.getKey();
                Set<Long> set = result.computeIfAbsent(world, k -> new HashSet<>());
                JsonElement val = worldEntry.getValue();
                if (val.isJsonArray()) {
                    for (JsonElement item : val.getAsJsonArray()) {
                        parseChunkCoordinate(item, set);
                    }
                } else if (val.isJsonObject()) {
                    for (Entry<String, JsonElement> chunkKey : val.getAsJsonObject().entrySet()) {
                        parseChunkKeyString(chunkKey.getKey(), set);
                    }
                }
            }
        } else if (chunksElem.isJsonArray()) {
            for (JsonElement item : chunksElem.getAsJsonArray()) {
                if (!item.isJsonObject())
                    continue;
                JsonObject cObj = item.getAsJsonObject();
                if (cObj.has("world") && cObj.has("x") && cObj.has("z")) {
                    String world = cObj.get("world").getAsString();
                    int cx = cObj.get("x").getAsInt();
                    int cz = cObj.get("z").getAsInt();
                    result.computeIfAbsent(world, k -> new HashSet<>()).add(packChunk(cx, cz));
                }
            }
        }
        return result;
    }

    private static void parseChunkCoordinate(JsonElement item, Set<Long> out) {
        if (item.isJsonPrimitive()) {
            parseChunkKeyString(item.getAsString(), out);
        } else if (item.isJsonObject()) {
            JsonObject o = item.getAsJsonObject();
            if (o.has("x") && o.has("z")) {
                out.add(packChunk(o.get("x").getAsInt(), o.get("z").getAsInt()));
            }
        }
    }

    private static void parseChunkKeyString(String raw, Set<Long> out) {
        if (raw == null)
            return;
        String[] parts = raw.trim().split("[,;:\\s]+");
        if (parts.length >= 2) {
            try {
                int cx = Integer.parseInt(parts[0]);
                int cz = Integer.parseInt(parts[1]);
                out.add(packChunk(cx, cz));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static Set<UUID> extractLandsTrusted(JsonObject obj) {
        Set<UUID> trusted = new HashSet<>();
        String[] keys = new String[] { "trusted", "members", "players" };
        for (String key : keys) {
            if (!obj.has(key) || obj.get(key).isJsonNull())
                continue;
            JsonElement elem = obj.get(key);
            if (elem.isJsonArray()) {
                JsonArray arr = elem.getAsJsonArray();
                for (JsonElement e : arr) {
                    if (e.isJsonPrimitive()) {
                        UUID u = tryParseUuid(e.getAsString());
                        if (u != null)
                            trusted.add(u);
                    }
                }
            } else if (elem.isJsonObject()) {
                for (String k : elem.getAsJsonObject().keySet()) {
                    UUID u = tryParseUuid(k);
                    if (u != null)
                        trusted.add(u);
                }
            }
        }
        return trusted;
    }

    public static List<int[]> mergeChunksIntoRectangles(Set<Long> packedChunks) {
        Set<Long> remaining = new HashSet<>(packedChunks);
        List<int[]> rectangles = new ArrayList<>();

        while (!remaining.isEmpty()) {
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            for (long p : remaining) {
                int x = unpackX(p);
                int z = unpackZ(p);
                if (x < minX || (x == minX && z < minZ)) {
                    minX = x;
                    minZ = z;
                }
            }

            int maxX = minX;
            while (remaining.contains(packChunk(maxX + 1, minZ))) {
                maxX++;
            }

            int maxZ = minZ;
            outer: while (true) {
                int nextZ = maxZ + 1;
                for (int x = minX; x <= maxX; x++) {
                    if (!remaining.contains(packChunk(x, nextZ)))
                        break outer;
                }
                maxZ = nextZ;
            }

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    remaining.remove(packChunk(x, z));
                }
            }
            rectangles.add(new int[] { minX, minZ, maxX, maxZ });
        }
        return rectangles;
    }

    public static long packChunk(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }

    private static int getWorldMinY(World world) {
        try {
            return world.getMinHeight();
        } catch (NoSuchMethodError legacy) {
            return 0;
        }
    }

    private static int getWorldMaxY(World world) {
        return Math.max(getWorldMinY(world), world.getMaxHeight() - 1);
    }

    private static int clampY(World world, int y, boolean isMin) {
        int worldMin = getWorldMinY(world);
        int worldMax = getWorldMaxY(world);
        if (isMin && y <= 0)
            return worldMin;
        if (!isMin && y >= 255)
            return worldMax;
        return Math.max(worldMin, Math.min(worldMax, y));
    }

    private static UUID parseUuidOrServer(Residence plugin, String raw) {
        UUID u = tryParseUuid(raw);
        if (u != null)
            return u;
        return raw != null && raw.trim().isEmpty() ? plugin.getServerUUID() : null;
    }

    private static UUID tryParseUuid(String raw) {
        if (raw == null || raw.trim().isEmpty())
            return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String resolveOwnerName(Residence plugin, UUID ownerUuid) {
        if (ownerUuid == null || ownerUuid.equals(plugin.getServerUUID()))
            return plugin.getServerLandName();
        String name = ResidencePlayer.getName(ownerUuid);
        if (name != null && !name.isEmpty())
            return name;
        String offline = Bukkit.getOfflinePlayer(ownerUuid).getName();
        return (offline != null && !offline.isEmpty()) ? offline : ownerUuid.toString().substring(0, 8);
    }

    private static String sanitizeName(String raw) {
        if (raw == null || raw.trim().isEmpty())
            return "claim";
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        if (cleaned.isEmpty())
            cleaned = "claim";
        if (cleaned.length() > 48)
            cleaned = cleaned.substring(0, 48);
        return cleaned;
    }

    private static String uniqueResidenceName(Residence plugin, String base) {
        String candidate = base;
        int suffix = 1;
        while (plugin.getResidenceManager().getByName(candidate) != null) {
            suffix++;
            candidate = base + "_" + suffix;
        }
        return candidate;
    }
}
