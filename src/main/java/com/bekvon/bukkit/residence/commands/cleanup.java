package com.bekvon.bukkit.residence.commands;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bekvon.bukkit.residence.LocaleManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.CommandAnnotation;
import com.bekvon.bukkit.residence.containers.cmd;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter.ImportReport;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter.ImportedEntry;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter.MissingOwnerTarget;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter.Source;

import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.FileHandler.ConfigReader;

/**
 * 領地自動清理輔助指令：
 * /resadmin cleanup import [all|plan|husksync] [dryrun]
 * 自動掃描名下有領地但缺少最後上線時間的玩家，並從 Plan 與 HuskSync API 補齊至資料庫。
 */
public class cleanup implements cmd {

    @Override
    @CommandAnnotation(simple = false, priority = 960, regVar = { 1, 2, 3 }, consoleVar = { 1, 2, 3 })
    public Boolean perform(Residence plugin, CommandSender sender, String[] args, boolean resadmin) {
        if (!resadmin) {
            lm.General_AdminOnly.sendMessage(sender);
            return true;
        }
        if (args.length < 1 || !args[0].equalsIgnoreCase("import")) {
            return false;
        }

        if (plugin.getDatabaseStorageManager() == null || !plugin.getDatabaseStorageManager().isInitialized()) {
            lm.Cleanup_Import_DbRequired.sendMessage(sender);
            return true;
        }

        Source source = Source.ALL;
        boolean dryRun = false;
        for (int i = 1; i < args.length; i++) {
            String token = args[i].toLowerCase(Locale.ROOT);
            if (token.equals("dryrun") || token.equals("--dryrun") || token.equals("preview")) {
                dryRun = true;
            } else if (token.equals("all") || token.equals("plan") || token.equals("husksync") || token.equals("husk")) {
                source = Source.parse(token);
            }
        }

        lm.Cleanup_Import_Started.sendMessage(sender, source.name(), dryRun ? lm.Cleanup_Import_DryRunTag.getMessage() : "");

        PlayerActivityImporter importer = plugin.getPlayerActivityImporter();
        final boolean isDry = dryRun;
        importer.importMissingResidenceOwnersAsync(source, dryRun).whenComplete((report, err) -> {
            if (err != null) {
                sendSync(plugin, sender, lm.Cleanup_Import_Failed.getMessage(err.getMessage()));
                return;
            }
            sendSync(plugin, sender, formatReport(report, isDry));
        });
        return true;
    }

    private static String formatReport(ImportReport report, boolean dryRun) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();

        sb.append(lm.Cleanup_Import_Header.getMessage(dryRun ? lm.Cleanup_Import_DryRunTag.getMessage() : "")).append("\n");
        sb.append(lm.Cleanup_Import_Providers.getMessage(
                report.isPlanAvailable() ? "&aOK" : "&cN/A",
                report.isHuskSyncAvailable() ? "&aOK" : "&cN/A")).append("\n");
        sb.append(lm.Cleanup_Import_Summary.getMessage(
                report.getTotalScannedMissing(),
                report.getImported().size(),
                report.getImportedFromPlan(),
                report.getImportedFromHuskSync(),
                report.getStillUnknown().size()));

        List<ImportedEntry> imported = report.getImported();
        int shownImported = Math.min(imported.size(), 15);
        for (int i = 0; i < shownImported; i++) {
            ImportedEntry e = imported.get(i);
            long days = Math.max(0L, (now - e.getLastSeenMs()) / 86_400_000L);
            sb.append("\n").append(lm.Cleanup_Import_ImportedLine.getMessage(
                    e.getPlayerName(),
                    e.getSourceName(),
                    sdf.format(new Date(e.getLastSeenMs())),
                    days,
                    String.join(", ", e.getResidences())));
        }
        if (imported.size() > shownImported) {
            sb.append("\n").append(lm.Cleanup_Import_MoreLines.getMessage(imported.size() - shownImported));
        }

        List<MissingOwnerTarget> unknown = report.getStillUnknown();
        if (!unknown.isEmpty()) {
            sb.append("\n").append(lm.Cleanup_Import_StillUnknownHeader.getMessage(unknown.size()));
            int shownUnknown = Math.min(unknown.size(), 15);
            for (int i = 0; i < shownUnknown; i++) {
                MissingOwnerTarget u = unknown.get(i);
                sb.append("\n").append(lm.Cleanup_Import_StillUnknownLine.getMessage(
                        u.getLastKnownName(),
                        u.getUuid().toString(),
                        String.join(", ", u.getResidences())));
            }
            if (unknown.size() > shownUnknown) {
                sb.append("\n").append(lm.Cleanup_Import_MoreLines.getMessage(unknown.size() - shownUnknown));
            }
        }
        return sb.toString();
    }

    private static void sendSync(Residence plugin, CommandSender sender, String message) {
        String colored = CMIChatColor.translate(message);
        if (sender instanceof Player) {
            ((Player) sender).getScheduler().run(plugin, t -> sender.sendMessage(colored), null);
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> sender.sendMessage(colored));
        }
    }

    @Override
    public void getLocale() {
        ConfigReader c = Residence.getInstance().getLocaleManager().getLocaleConfig();
        c.get("Description", "Import missing residence owner last-seen timestamps from Plan and HuskSync API.");
        c.get("Info", Arrays.asList(
                "&eUsage: &6/resadmin cleanup import [all|plan|husksync] [dryrun]",
                "&7Automatically scans residence owners missing last-seen timestamps and imports them from Plan / HuskSync."));
        LocaleManager.addTabCompleteMain(this, "import", "all%%plan%%husksync", "dryrun");
    }
}
