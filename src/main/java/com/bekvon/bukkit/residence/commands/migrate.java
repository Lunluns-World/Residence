package com.bekvon.bukkit.residence.commands;

import java.io.File;
import java.util.Arrays;

import org.bukkit.command.CommandSender;

import com.bekvon.bukkit.residence.LocaleManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.CommandAnnotation;
import com.bekvon.bukkit.residence.containers.cmd;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.storage.MigrationService;
import com.bekvon.bukkit.residence.storage.MigrationService.MigrationReport;

import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.FileHandler.ConfigReader;

public class migrate implements cmd {

    @Override
    @CommandAnnotation(simple = false, priority = 5800)
    public Boolean perform(Residence plugin, CommandSender sender, String[] args, boolean resadmin) {
        if (!resadmin || sender instanceof org.bukkit.entity.Player) {
            lm.General_AdminOnly.sendMessage(sender);
            return true;
        }
        if (args.length < 1) {
            return false;
        }

        String mode = args[0].toLowerCase();
        File pluginsDir = plugin.getDataFolder().getParentFile();

        try {
            MigrationReport report;
            switch (mode) {
            case "yaml2db":
                if (args.length != 2 || !args[1].equalsIgnoreCase("apply"))
                    return false;
                report = MigrationService.migrateYamlToDatabase(plugin);
                sender.sendMessage(CMIChatColor.translate(report.toSummary("YAML -> Database")));
                break;
            case "db2yaml":
                report = MigrationService.migrateDatabaseToYaml(plugin);
                sender.sendMessage(CMIChatColor.translate(report.toSummary("Database -> YAML")));
                break;
            case "griefprevention":
            case "gp": {
                boolean apply = args.length > 1 && args[1].equalsIgnoreCase("apply");
                if (args.length > 1 && !apply && !args[1].equalsIgnoreCase("preview"))
                    return false;
                File dir = args.length >= 3 ? new File(args[2]) : new File(new File(pluginsDir, "GriefPreventionData"), "ClaimData");
                report = MigrationService.importGriefPrevention(plugin, dir, apply);
                sender.sendMessage(CMIChatColor.translate(report.toSummary((apply ? "Import " : "Preview ") + "GriefPrevention")));
                break;
            }
            case "lands": {
                boolean apply = args.length > 1 && args[1].equalsIgnoreCase("apply");
                if (args.length > 1 && !apply && !args[1].equalsIgnoreCase("preview"))
                    return false;
                File dir = args.length >= 3 ? new File(args[2]) : new File(new File(pluginsDir, "Lands"), "Data");
                org.bukkit.plugin.Plugin lands = plugin.getServer().getPluginManager().getPlugin("Lands");
                report = args.length < 3 && lands != null && lands.isEnabled()
                        ? MigrationService.importLiveLands(plugin, lands, apply)
                        : MigrationService.importLands(plugin, dir, apply);
                sender.sendMessage(CMIChatColor.translate(report.toSummary((apply ? "Import " : "Preview ") + "Lands")));
                break;
            }
            default:
                return false;
            }

            int shown = 0;
            for (String line : report.details) {
                if (shown++ >= 10) {
                    sender.sendMessage(CMIChatColor.GRAY + "... and " + (report.details.size() - 10) + " more details.");
                    break;
                }
                sender.sendMessage(CMIChatColor.GRAY + " - " + line);
            }
        } catch (Exception ex) {
            sender.sendMessage(CMIChatColor.RED + "[Residence Migrate] Failed: " + ex.getMessage());
            ex.printStackTrace();
        }
        return true;
    }

    @Override
    public void getLocale() {
        ConfigReader c = Residence.getInstance().getLocaleManager().getLocaleConfig();
        c.get("Description", "Migrate storage (YAML <-> SQL) or import from GriefPrevention/Lands");
        c.get("Info", Arrays.asList(
                "&eUsage: &6/resadmin migrate yaml2db apply",
                "&eUsage: &6/resadmin migrate db2yaml",
                "&eUsage: &6/resadmin migrate griefprevention [preview/apply] [ClaimDataPath]",
                "&eUsage: &6/resadmin migrate lands [preview/apply] [LandsDataPath]"));
        LocaleManager.addTabCompleteMain(this, "yaml2db%%db2yaml%%griefprevention%%lands");
    }
}
