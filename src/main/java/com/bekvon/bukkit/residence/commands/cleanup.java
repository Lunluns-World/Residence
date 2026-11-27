package com.bekvon.bukkit.residence.commands;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bekvon.bukkit.residence.LocaleManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.CommandAnnotation;
import com.bekvon.bukkit.residence.containers.cmd;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.PlayerManager;
import com.bekvon.bukkit.residence.storage.CleanupReclaimManager;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter.ImportReport;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter.ImportedEntry;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter.MissingOwnerTarget;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter.Source;

import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.FileHandler.ConfigReader;

/**
 * 領地自動清理與保管管理指令：
 * - /res cleanup import [all|plan|husksync] [dryrun]
 * - /res cleanup setowner <residence> <player|uuid|clear> [displayName]
 * - /res cleanup archive <residence> [player|uuid] [displayName]
 * - /res cleanup clearowner <residence>
 */
public class cleanup implements cmd {

    @Override
    @CommandAnnotation(simple = false, priority = 960, regVar = { 1, 2, 3, 4 }, consoleVar = { 1, 2, 3, 4 })
    public Boolean perform(Residence plugin, CommandSender sender, String[] args, boolean resadmin) {
        if (!resadmin && !plugin.getPermissionManager().isResidenceAdmin(sender)) {
            lm.General_AdminOnly.sendMessage(sender);
            return true;
        }
        if (args.length < 1) {
            return false;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
        case "import":
            return handleImport(plugin, sender, args);
        case "setowner":
        case "originalowner":
        case "setoriginal":
            if (args.length < 3) {
                return false;
            }
            return handleSetOriginalOwner(plugin, sender, args[1], args[2], args.length >= 4 ? args[3] : null, false);
        case "archive":
            if (args.length < 2) {
                return false;
            }
            return handleArchive(plugin, sender, args[1], args.length >= 3 ? args[2] : null, args.length >= 4 ? args[3] : null);
        case "clearowner":
            if (args.length < 2) {
                return false;
            }
            return handleSetOriginalOwner(plugin, sender, args[1], "clear", null, false);
        default:
            return false;
        }
    }

    private Boolean handleImport(Residence plugin, CommandSender sender, String[] args) {
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

    private Boolean handleArchive(Residence plugin, CommandSender sender, String resName, String playerInput, String displayName) {
        CleanupReclaimManager reclaim = plugin.getCleanupReclaimManager();
        String holder = reclaim != null ? reclaim.getConfiguredArchivedHolderName() : null;
        if (holder == null) {
            lm.Cleanup_HolderNotConfigured.sendMessage(sender);
            return true;
        }

        ClaimedResidence res = plugin.getResidenceManager().getByName(resName);
        if (res == null) {
            lm.Invalid_Residence.sendMessage(sender);
            return true;
        }
        if (!res.isTopArea()) {
            lm.Cleanup_TopLevelOnly.sendMessage(sender);
            return true;
        }

        if (playerInput == null || playerInput.trim().isEmpty()) {
            // 若未指定玩家，且目前領地擁有者為一般真實玩家，則自動將目前擁有者記為原領地主並轉入保管
            UUID curUuid = res.getOwnerUUID();
            String curName = res.getOwner();
            boolean isRealOwner = curUuid != null
                    && !PlayerManager.isTempUUID(curUuid)
                    && !curUuid.equals(plugin.getServerUUID())
                    && !reclaim.isArchivedHolder(curName)
                    && !curName.equalsIgnoreCase("Server Land")
                    && !curName.equalsIgnoreCase(plugin.getServerLandName());
            transferToCustodyOnMain(plugin, res, holder);
            lm.Cleanup_Archive_Success.sendMessage(sender, res.getName(), holder);
            if (isRealOwner) {
                reclaim.recordArchived(res, curUuid, curName);
                lm.Cleanup_SetOwner_Success.sendMessage(sender, res.getName(), curName, curUuid.toString(), "CurrentOwner");
                notifyOrImportLastSeen(plugin, sender, curUuid, curName);
            }
            plugin.saveResidenceDataNow();
            return true;
        }

        return handleSetOriginalOwner(plugin, sender, resName, playerInput, displayName, true);
    }

    public static Boolean handleSetOriginalOwner(
            Residence plugin,
            CommandSender sender,
            String resName,
            String playerInput,
            String explicitDisplayName,
            boolean autoTransferToCustody) {
        return handleSetOriginalOwner(plugin, sender, resName, playerInput, explicitDisplayName, autoTransferToCustody, null);
    }

    public static Boolean handleSetOriginalOwner(
            Residence plugin,
            CommandSender sender,
            String resName,
            String playerInput,
            String explicitDisplayName,
            boolean autoTransferToCustody,
            Runnable afterUpdateOnMain) {
        CleanupReclaimManager reclaim = plugin.getCleanupReclaimManager();
        if (reclaim == null) {
            return true;
        }
        String holder = reclaim.getConfiguredArchivedHolderName();
        if (holder == null) {
            lm.Cleanup_HolderNotConfigured.sendMessage(sender);
            return true;
        }

        ClaimedResidence res = plugin.getResidenceManager().getByName(resName);
        if (res == null) {
            lm.Invalid_Residence.sendMessage(sender);
            return true;
        }
        if (!res.isTopArea()) {
            lm.Cleanup_TopLevelOnly.sendMessage(sender);
            return true;
        }

        String trimmedInput = playerInput == null ? "" : playerInput.trim();
        if (trimmedInput.equalsIgnoreCase("clear")
                || trimmedInput.equalsIgnoreCase("none")
                || trimmedInput.equalsIgnoreCase("remove")
                || trimmedInput.equalsIgnoreCase("unset")) {
            if (plugin.getAuditManager() != null) {
                plugin.getAuditManager().captureBeforeMutation(sender, res, com.bekvon.bukkit.residence.audit.AuditAction.OWNER);
            }
            reclaim.removeArchivedRecord(res);
            lm.Cleanup_SetOwner_Cleared.sendMessage(sender, res.getName());
            if (afterUpdateOnMain != null) {
                afterUpdateOnMain.run();
            }
            return true;
        }

        if (!autoTransferToCustody && !reclaim.isArchivedHolder(res.getOwner())) {
            lm.Cleanup_SetOwner_NotArchived.sendMessage(sender, res.getName(), res.getOwner(), holder);
            return true;
        }

        lm.Cleanup_SetOwner_Resolving.sendMessage(sender, trimmedInput);
        UUID targetResUuid = res.getUniqueId();
        String targetResName = res.getName();

        plugin.getPlayerActivityImporter().resolvePlayerIdentityAsync(trimmedInput, explicitDisplayName)
                .whenComplete((identity, err) -> runOnMain(plugin, sender, () -> {
                    if (err != null || identity == null || identity.getUuid() == null) {
                        lm.Cleanup_SetOwner_PlayerNotFound.sendMessage(sender, trimmedInput);
                        return;
                    }
                    ClaimedResidence live = targetResUuid != null
                            ? plugin.getResidenceManager().getByUUID(targetResUuid)
                            : plugin.getResidenceManager().getByName(targetResName);
                    if (live == null) {
                        lm.Invalid_Residence.sendMessage(sender);
                        return;
                    }
                    if (!reclaim.isArchivedHolder(live.getOwner())) {
                        if (autoTransferToCustody) {
                            transferToCustodyOnMain(plugin, live, holder);
                            lm.Cleanup_Archive_Success.sendMessage(sender, live.getName(), holder);
                        } else {
                            lm.Cleanup_SetOwner_NotArchived.sendMessage(sender, live.getName(), live.getOwner(), holder);
                            return;
                        }
                    }

                    if (plugin.getAuditManager() != null) {
                        plugin.getAuditManager().captureBeforeMutation(sender, live, com.bekvon.bukkit.residence.audit.AuditAction.OWNER);
                    }
                    reclaim.recordArchived(live, identity.getUuid(), identity.getName());
                    if (autoTransferToCustody) {
                        plugin.saveResidenceDataNow();
                    }
                    lm.Cleanup_SetOwner_Success.sendMessage(
                            sender,
                            live.getName(),
                            identity.getName(),
                            identity.getUuid().toString(),
                            identity.getSource());

                    notifyOrImportLastSeen(plugin, sender, identity.getUuid(), identity.getName());
                    if (afterUpdateOnMain != null) {
                        afterUpdateOnMain.run();
                    }
                }));
        return true;
    }

    private static void transferToCustodyOnMain(Residence plugin, ClaimedResidence res, String holder) {
        boolean resetFlags = !plugin.getConfigManager().isAutoCleanKeepFlags();
        res.getPermissions().setOwner(holder, resetFlags);
        if (plugin.getRentManager().isForRent(res)) {
            plugin.getRentManager().removeRentable(res);
        }
        if (plugin.getTransactionManager().isForSale(res)) {
            plugin.getTransactionManager().removeFromSale(res);
        }
        plugin.getSignUtil().updateSignResName(res);
    }

    private static void notifyOrImportLastSeen(Residence plugin, CommandSender sender, UUID uuid, String playerName) {
        long existingTs = plugin.getCrossServerManager() != null
                ? plugin.getCrossServerManager().resolveEffectiveLastSeen(uuid)
                : 0L;
        if (existingTs > 0L) {
            String formatted = plugin.getCrossServerManager().formatLastSeenValue(uuid, true);
            lm.Cleanup_SetOwner_LastSeenKnown.sendMessage(sender, formatted);
            return;
        }
        plugin.getPlayerActivityImporter().importSinglePlayerIfMissingAsync(uuid, playerName, Source.ALL)
                .whenComplete((imported, err) -> runOnMain(plugin, sender, () -> {
                    if (imported != null && imported.getLastSeenMs() > 0L && plugin.getCrossServerManager() != null) {
                        String formatted = plugin.getCrossServerManager().formatLastSeenValue(uuid, true);
                        lm.Cleanup_SetOwner_LastSeenImported.sendMessage(sender, imported.getSourceName(), formatted);
                    } else {
                        lm.Cleanup_SetOwner_LastSeenMissing.sendMessage(sender);
                    }
                }));
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

    private static void runOnMain(Residence plugin, CommandSender sender, Runnable task) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            player.getScheduler().run(plugin, t -> {
                if (player.isOnline()) {
                    task.run();
                }
            }, null);
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        }
    }

    private static void sendSync(Residence plugin, CommandSender sender, String message) {
        String colored = CMIChatColor.translate(message);
        runOnMain(plugin, sender, () -> sender.sendMessage(colored));
    }

    @Override
    public void getLocale() {
        ConfigReader c = Residence.getInstance().getLocaleManager().getLocaleConfig();
        c.get("Description", "Manage archived residences and import missing owner last-seen timestamps.");
        c.get("Info", Arrays.asList(
                "&eUsage: &6/res cleanup import [all|plan|husksync] [dryrun]",
                "&eUsage: &6/res cleanup setowner <residence> <player|uuid|clear> [displayName]",
                "&eUsage: &6/res cleanup archive <residence> [player|uuid] [displayName]",
                "&7Manage Server_Unclaimed original owners and import last-seen timestamps from Plan / HuskSync."));
        LocaleManager.addTabCompleteMain(this, "import%%setowner%%archive%%clearowner", "[cresidence]%%all%%plan%%husksync", "[playername]%%dryrun");
    }
}
