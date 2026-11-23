package com.bekvon.bukkit.residence.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.bekvon.bukkit.residence.utils.PlayerActivityImporter.ExternalActivityProvider;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter.ImportReport;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter.MissingOwnerTarget;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter.Source;

/**
 * Plan + HuskSync 外部最後上線時間匯入器（PlayerActivityImporter）單元測試。
 */
class PlayerActivityImporterTest {

    private static final UUID P1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
    private static final UUID P2 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
    private static final UUID P3 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");

    private ExternalActivityProvider fakeProvider(String name, boolean available, Map<UUID, Long> data) {
        return new ExternalActivityProvider() {
            @Override
            public String getName() { return name; }

            @Override
            public boolean isAvailable() { return available; }

            @Override
            public Map<UUID, Long> queryLastSeen(Collection<UUID> uuids) {
                Map<UUID, Long> out = new HashMap<>();
                for (UUID u : uuids) {
                    if (data.containsKey(u)) {
                        out.put(u, data.get(u));
                    }
                }
                return out;
            }
        };
    }

    @Test
    void mergesPlanFirstThenHuskSyncFallbackAndListsRemainingUnknown() {
        Map<UUID, Long> planData = Collections.singletonMap(P1, 1_700_000_000_000L);
        Map<UUID, Long> huskData = new HashMap<>();
        huskData.put(P1, 1_600_000_000_000L);
        huskData.put(P2, 1_710_000_000_000L);

        PlayerActivityImporter importer = new PlayerActivityImporter(
                null,
                Logger.getLogger("ImporterTest"),
                fakeProvider("Plan", true, planData),
                fakeProvider("HuskSync", true, huskData));

        List<MissingOwnerTarget> targets = Arrays.asList(
                new MissingOwnerTarget(P1, "Alice", Collections.singletonList("res1")),
                new MissingOwnerTarget(P2, "Bob", Collections.singletonList("res2")),
                new MissingOwnerTarget(P3, "Ghost", Collections.singletonList("res3")));

        ImportReport report = importer.resolveFromProviders(targets, Source.ALL, true);
        assertTrue(report.isDryRun());
        assertTrue(report.isPlanAvailable());
        assertTrue(report.isHuskSyncAvailable());
        assertEquals(3, report.getTotalScannedMissing());
        assertEquals(2, report.getImported().size());
        assertEquals(1, report.getImportedFromPlan());
        assertEquals(1, report.getImportedFromHuskSync());
        assertEquals(1, report.getStillUnknown().size());
        assertEquals(P3, report.getStillUnknown().get(0).getUuid());
    }

    @Test
    void respectsExplicitSourceSelectionAndHandlesProviderFailure() {
        ExternalActivityProvider brokenPlan = new ExternalActivityProvider() {
            @Override
            public String getName() { return "Plan"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public Map<UUID, Long> queryLastSeen(Collection<UUID> uuids) {
                throw new RuntimeException("simulated query failure");
            }
        };
        ExternalActivityProvider husk = fakeProvider("HuskSync", true, Collections.singletonMap(P2, 1_720_000_000_000L));

        PlayerActivityImporter importer = new PlayerActivityImporter(null, Logger.getLogger("ImporterTest"), brokenPlan, husk);
        List<MissingOwnerTarget> targets = Collections.singletonList(
                new MissingOwnerTarget(P2, "Bob", Collections.singletonList("res2")));

        // Plan 拋錯時不中斷，仍順利由 HuskSync 補回
        ImportReport allReport = importer.resolveFromProviders(targets, Source.ALL, false);
        assertEquals(1, allReport.getImportedFromHuskSync());
        assertEquals(0, allReport.getStillUnknown().size());

        // 僅指定 Plan 時不查詢 HuskSync
        ImportReport planOnly = importer.resolveFromProviders(targets, Source.PLAN, false);
        assertFalse(planOnly.isHuskSyncAvailable());
        assertEquals(0, planOnly.getImported().size());
        assertEquals(1, planOnly.getStillUnknown().size());
    }
}
