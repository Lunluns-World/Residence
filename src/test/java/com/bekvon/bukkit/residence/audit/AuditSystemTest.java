package com.bekvon.bukkit.residence.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bekvon.bukkit.residence.audit.AuditQueryParser.ErrorCode;
import com.bekvon.bukkit.residence.audit.AuditQueryParser.Mode;
import com.bekvon.bukkit.residence.audit.AuditQueryParser.ParseResult;
import com.bekvon.bukkit.residence.audit.ResidenceSnapshot.Box;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager;
import com.bekvon.bukkit.residence.storage.StorageType;

class AuditSystemTest {

    @Test
    void parsesCoreProtectStyleQueryParametersAndEnforcesSafetyGuards() {
        long now = 1_750_000_000_000L;

        assertEquals(90_000L, AuditQueryParser.parseDurationMillis("1m30s"));
        assertEquals(86_400_000L * 7 + 3_600_000L * 2, AuditQueryParser.parseDurationMillis("1w2h"));
        assertEquals(-1L, AuditQueryParser.parseDurationMillis("invalid"));

        // Rollback 不帶時間與 ID 時必須拒絕，防止全庫誤還原
        ParseResult missingTime = AuditQueryParser.parse(
                new String[] { "rb", "u:Griefer" }, 1, Mode.ROLLBACK, "world", 100, 200, now);
        assertFalse(missingTime.ok);
        assertEquals(ErrorCode.MISSING_TIME, missingTime.error);

        // 完整條件解析：玩家排除、動作篩選、半徑、時間區間、預覽
        ParseResult full = AuditQueryParser.parse(
                new String[] { "rb", "u:Alice,!Admin", "o:OldPlayer", "r:50", "t:7d-1d", "a:remove,flag", "res:SpawnHub", "#preview" },
                1, Mode.ROLLBACK, "survival", 120, -340, now);
        assertTrue(full.ok);
        AuditQuery q = full.query;
        assertTrue(q.actors.contains("alice"));
        assertTrue(q.excludedActors.contains("admin"));
        assertTrue(q.owners.contains("oldplayer"));
        assertEquals(50, q.radius);
        assertEquals("survival", q.worldName);
        assertEquals(120, q.centerX);
        assertEquals(-340, q.centerZ);
        assertTrue(q.preview);
        assertTrue(q.actions.contains(AuditAction.REMOVE));
        assertTrue(q.actions.contains(AuditAction.SUBZONE_REMOVE));
        assertTrue(q.actions.contains(AuditAction.FLAG));
        assertFalse(q.actions.contains(AuditAction.CREATE));
        assertEquals(now - 7L * 86_400_000L, q.sinceEpochMs);
        assertEquals(now - 1L * 86_400_000L, q.untilEpochMs);

        // Recover 模式未指定 a: 時預設僅恢復被刪除的主領地與子領地
        ParseResult recover = AuditQueryParser.parse(
                new String[] { "recover", "r:100", "t:30d" },
                1, Mode.RECOVER, "world", 0, 0, now);
        assertTrue(recover.ok);
        assertEquals(2, recover.query.actions.size());
        assertTrue(recover.query.actions.contains(AuditAction.REMOVE));
        assertTrue(recover.query.actions.contains(AuditAction.SUBZONE_REMOVE));
    }

    @Test
    void serializesResidenceSnapshotsAndProducesReadableDiffSummaries() {
        ResidenceSnapshot before = new ResidenceSnapshot();
        before.uuid = UUID.randomUUID().toString();
        before.resName = "PublicPlaza";
        before.fullName = "PublicPlaza";
        before.worldName = "world";
        before.ownerUuid = UUID.randomUUID().toString();
        before.ownerName = "Server_Unclaimed";
        before.areas.put("main", new Box(0, 60, 0, 19, 100, 19));
        before.permissions.cuboidFlags.put("tp", true);

        ResidenceSnapshot after = ResidenceSnapshot.fromJson(before.toJson());
        assertNotNull(after);
        assertFalse(before.hasPermissionDiffRecursive(after));
        assertFalse(before.hasOwnerDiffRecursive(after));

        after.permissions.cuboidFlags.put("hidden", true);
        String playerUuid = UUID.randomUUID().toString();
        after.permissions.playerFlags.put(playerUuid, Collections.singletonMap("build", true));
        after.permissions.playerNames.put(playerUuid, "BuilderBob");

        assertTrue(before.hasPermissionDiffRecursive(after));
        String flagSummary = ResidenceSnapshot.summarize(AuditAction.FLAG, before, after, "");
        assertTrue(flagSummary.contains("hidden:unset->true"));
        assertTrue(flagSummary.contains("p:BuilderBob build:unset->true"));

        after.archivedOriginalOwnerUuid = UUID.randomUUID().toString();
        after.archivedOriginalOwnerName = "VeteranOwner";
        assertTrue(before.hasOwnerDiffRecursive(after));
        assertEquals("custody-owner: unset -> VeteranOwner",
                ResidenceSnapshot.summarize(AuditAction.OWNER, before, after, ""));
    }

    @Test
    void persistsAndQueriesSpatialAndOrderedRollbackRecordsInSqlite(@TempDir Path tempDir) throws Exception {
        DatabaseStorageManager storage = new DatabaseStorageManager(
                StorageType.SQLITE,
                tempDir.toFile(),
                "audit_test.db",
                "", 0, "", "", "", "res_", "");
        storage.initialize();

        AuditDatabase db = new AuditDatabase(storage);
        db.initialize();

        long baseTime = 1_750_000_000_000L;
        UUID resUuid = UUID.randomUUID();
        UUID actorUuid = UUID.randomUUID();

        AuditRecord r1 = new AuditRecord(
                0L, "survival-1", baseTime - 300_000L, actorUuid, "Alice", AuditAction.CREATE,
                resUuid, "townhall", "townhall", "TownHall", actorUuid, "Alice",
                "world", 100, 60, 100, 140, 120, 140, "created (41x61x41, owner=Alice)",
                null, "{\"resName\":\"TownHall\"}", false);

        AuditRecord r2 = new AuditRecord(
                0L, "survival-1", baseTime - 120_000L, actorUuid, "Alice", AuditAction.FLAG,
                resUuid, "townhall", "townhall", "TownHall", actorUuid, "Alice",
                "world", 100, 60, 100, 140, 120, 140, "hidden:unset->true",
                "{\"resName\":\"TownHall\"}", "{\"resName\":\"TownHall\"}", false);

        AuditRecord r3Far = new AuditRecord(
                0L, "survival-1", baseTime - 60_000L, null, "#autocleanup", AuditAction.REMOVE,
                UUID.randomUUID(), "faraway", "faraway", "FarAway", UUID.randomUUID(), "OldGuy",
                "world", 5000, 60, 5000, 5020, 90, 5020, "removed (21x31x21, owner=OldGuy)",
                "{\"resName\":\"FarAway\"}", null, false);

        long id1 = db.insert(r1);
        long id2 = db.insert(r2);
        long id3 = db.insert(r3Far);
        assertTrue(id1 > 0 && id2 > id1 && id3 > id2);

        // 半徑查詢：站在 (150, 150) 查 r:20，應命中 [100..140] 的 TownHall 兩筆，且排除 5000 外的 FarAway
        AuditQuery nearQuery = new AuditQuery(
                null, null, null, null, null, null,
                "world", 20, 150, 150, baseTime - 600_000L, null, false, false, 1);
        assertEquals(2, db.countMatching(nearQuery, "survival-1"));

        // Rollback 執行查詢必須為 DESC（先還原 id2 再還原 id1）
        List<AuditRecord> forRollback = db.queryForExecution(nearQuery, "survival-1", false, false, 100);
        assertEquals(2, forRollback.size());
        assertEquals(id2, forRollback.get(0).id);
        assertEquals(id1, forRollback.get(1).id);
        assertNotNull(forRollback.get(0).afterSnapshot);

        // 標記已還原後，Restore 執行查詢必須為 ASC（先重做 id1 再重做 id2）
        db.markRolledBackBatch(Arrays.asList(id1, id2), true);
        assertEquals(0, db.queryForExecution(nearQuery, "survival-1", false, false, 100).size());

        List<AuditRecord> forRestore = db.queryForExecution(nearQuery, "survival-1", true, true, 100);
        assertEquals(2, forRestore.size());
        assertEquals(id1, forRestore.get(0).id);
        assertEquals(id2, forRestore.get(1).id);

        // 清理舊紀錄
        int purged = db.purgeOlderThan(baseTime - 200_000L, "survival-1");
        assertEquals(1, purged);
        assertNull(db.getById(id1));
        assertNotNull(db.getById(id2));
    }

    @Test
    void validatesChineseTwYamlContainsAllNewLocaleKeys() {
        File ymlFile = new File("src/main/resources/Language/ChineseTW.yml");
        assertTrue(ymlFile.isFile(), "ChineseTW.yml should exist");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(ymlFile);

        lm[] requiredNewKeys = new lm[] {
                lm.Residence_OwnerLastSeen,
                lm.Residence_ArchivedOwnerLastSeen,
                lm.Residence_ArchivedOwnerUnset,
                lm.Residence_LastSeenOnline,
                lm.Residence_LastSeenDaysAgo,
                lm.Gui_Entry_LastSeen,
                lm.Gui_Entry_OriginalLastSeen,
                lm.Gui_Manage_OriginalOwnerName,
                lm.Gui_Batch_Title,
                lm.Gui_Batch_Btn_QuickHiddenName,
                lm.Gui_Batch_Btn_AllFlagsName,
                lm.Gui_Batch_Picker_Title,
                lm.Gui_Batch_Msg_Applied,
                lm.Cleanup_SetOwner_Success,
                lm.Cleanup_Archive_Success,
                lm.Audit_Header_Lookup,
                lm.Audit_Exec_Summary,
                lm.Audit_Inspect_Enabled
        };

        for (lm key : requiredNewKeys) {
            String path = key.getPath();
            assertTrue(yml.contains(path), "Missing ChineseTW translation for " + path);
        }
        assertTrue(yml.contains("CommandHelp.SubCommands.res.SubCommands.flags.SubCommands.neighbor.Translated"));
        assertTrue(yml.contains("CommandHelp.SubCommands.res.SubCommands.neighbor.Description"));
        assertTrue(yml.contains("CommandHelp.SubCommands.res.SubCommands.co.Description"));
    }
}
