package com.bekvon.bukkit.residence.commands;

import java.util.Arrays;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.bekvon.bukkit.residence.LocaleManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.CommandAnnotation;
import com.bekvon.bukkit.residence.containers.cmd;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.crossserver.CrossServerManager;
import com.bekvon.bukkit.residence.crossserver.RemoteResidenceEntry;
import com.bekvon.bukkit.residence.gui.ResidenceGuiMetaManager;
import com.bekvon.bukkit.residence.gui.ResidenceGuiMetadata;
import com.bekvon.bukkit.residence.gui.ResidenceListGui;
import com.bekvon.bukkit.residence.gui.ResidenceListGui.FilterMode;
import com.bekvon.bukkit.residence.gui.ResidenceListGui.SortMode;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;

import net.Zrips.CMILib.FileHandler.ConfigReader;
import net.Zrips.CMILib.Items.CMIMaterial;

public class gui implements cmd {

    @Override
    @CommandAnnotation(simple = true, priority = 310)
    public Boolean perform(Residence plugin, CommandSender sender, String[] args, boolean resadmin) {
        if (!(sender instanceof Player))
            return false;

        Player player = (Player) sender;
        if (!plugin.getConfigManager().isResidenceGuiEnabled()) {
            lm.Gui_Disabled.sendMessage(player);
            return true;
        }

        if (args.length == 0) {
            plugin.getResidenceListGui().openBrowser(player, FilterMode.ALL, SortMode.DEFAULT, null, resadmin, 1);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
        case "all":
            plugin.getResidenceListGui().openBrowser(player, FilterMode.ALL, SortMode.DEFAULT, null, resadmin, 1);
            return true;
        case "mine":
        case "my":
            plugin.getResidenceListGui().openBrowser(player, FilterMode.MINE, SortMode.DEFAULT, null, resadmin, 1);
            return true;
        case "pinned":
        case "pin":
        case "fav":
            plugin.getResidenceListGui().openBrowser(player, FilterMode.PINNED, SortMode.DEFAULT, null, resadmin, 1);
            return true;
        case "batch":
            plugin.getResidenceListGui().openBatchBrowser(player, FilterMode.ALL, SortMode.DEFAULT, null, resadmin, 1);
            return true;
        case "manage": {
            RemoteResidenceEntry entry = resolveManagedEntry(plugin, player, args.length >= 2 ? args[1] : null, resadmin);
            if (entry != null) {
                plugin.getResidenceListGui().openManageGui(player, entry, FilterMode.ALL, SortMode.DEFAULT, null, resadmin, 1);
            }
            return true;
        }
        case "alias": {
            if (args.length < 3)
                return false;
            RemoteResidenceEntry entry = resolveManagedEntry(plugin, player, args[1], resadmin);
            if (entry == null)
                return true;
            String joined = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
            String value = "clear".equalsIgnoreCase(joined) ? null : joined;
            if (value != null && value.length() > ResidenceListGui.MAX_ALIAS_LENGTH) {
                value = value.substring(0, ResidenceListGui.MAX_ALIAS_LENGTH);
            }
            ResidenceGuiMetaManager metaMgr = plugin.getGuiMetaManager();
            ResidenceGuiMetadata meta = metaMgr.getOrCreateMetadata(entry.getServerName(), entry.getResName());
            meta.setAlias(value);
            metaMgr.updateMetadata(entry.getServerName(), entry.getResName(), meta);
            if (meta.getAlias() == null)
                lm.Gui_Msg_AliasCleared.sendMessage(player, entry.getResName());
            else
                lm.Gui_Msg_AliasUpdated.sendMessage(player, entry.getResName(), meta.getAlias());
            return true;
        }
        case "desc":
        case "description": {
            if (args.length < 3)
                return false;
            RemoteResidenceEntry entry = resolveManagedEntry(plugin, player, args[1], resadmin);
            if (entry == null)
                return true;
            String joined = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
            String value = "clear".equalsIgnoreCase(joined) ? null : joined;
            if (value != null && value.length() > ResidenceListGui.MAX_DESCRIPTION_LENGTH) {
                value = value.substring(0, ResidenceListGui.MAX_DESCRIPTION_LENGTH);
            }
            ResidenceGuiMetaManager metaMgr = plugin.getGuiMetaManager();
            ResidenceGuiMetadata meta = metaMgr.getOrCreateMetadata(entry.getServerName(), entry.getResName());
            meta.setDescription(value);
            metaMgr.updateMetadata(entry.getServerName(), entry.getResName(), meta);
            if (meta.getDescription() == null)
                lm.Gui_Msg_DescCleared.sendMessage(player, entry.getResName());
            else
                lm.Gui_Msg_DescUpdated.sendMessage(player, entry.getResName(), meta.getDescription());
            return true;
        }
        case "icon": {
            if (args.length < 2)
                return false;
            RemoteResidenceEntry entry = resolveManagedEntry(plugin, player, args[1], resadmin);
            if (entry == null)
                return true;
            String iconVal;
            if (args.length >= 3) {
                if ("clear".equalsIgnoreCase(args[2])) {
                    iconVal = null;
                } else {
                    CMIMaterial mat = CMIMaterial.get(args[2]);
                    if (mat == null || mat.isNone() || mat.isAir()) {
                        lm.Gui_Msg_UnknownMaterial.sendMessage(player, args[2]);
                        return true;
                    }
                    iconVal = mat.name();
                }
            } else {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand == null || hand.getType() == Material.AIR) {
                    lm.Gui_Msg_IconHoldItem.sendMessage(player);
                    return true;
                }
                iconVal = hand.getType().name();
            }
            ResidenceGuiMetaManager metaMgr = plugin.getGuiMetaManager();
            ResidenceGuiMetadata meta = metaMgr.getOrCreateMetadata(entry.getServerName(), entry.getResName());
            meta.setIcon(iconVal);
            metaMgr.updateMetadata(entry.getServerName(), entry.getResName(), meta);
            if (iconVal == null)
                lm.Gui_Msg_IconReset.sendMessage(player);
            else
                lm.Gui_Msg_IconUpdated.sendMessage(player, entry.getResName(), iconVal);
            return true;
        }
        case "toggle": {
            RemoteResidenceEntry entry = resolveManagedEntry(plugin, player, args.length >= 2 ? args[1] : null, resadmin);
            if (entry == null)
                return true;
            ResidenceGuiMetaManager metaMgr = plugin.getGuiMetaManager();
            ResidenceGuiMetadata meta = metaMgr.getOrCreateMetadata(entry.getServerName(), entry.getResName());
            meta.setListed(!meta.isListed());
            metaMgr.updateMetadata(entry.getServerName(), entry.getResName(), meta);
            lm.Gui_Msg_VisibilityUpdated.sendMessage(player, entry.getResName(),
                    meta.isListed() ? lm.Gui_Entry_StatusPublic.getMessage() : lm.Gui_Entry_StatusHidden.getMessage());
            return true;
        }
        default: {
            // Optional server filter directly: /res gui <serverName>
            if (plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
                for (String srv : plugin.getCrossServerManager().getAvailableServers()) {
                    if (plugin.getConfigManager().matchesCrossServerName(args[0], srv)) {
                        plugin.getResidenceListGui().openBrowser(player, FilterMode.ALL, SortMode.DEFAULT, srv, resadmin, 1);
                        return true;
                    }
                }
            }
            return false;
        }
        }
    }

    private static RemoteResidenceEntry resolveManagedEntry(Residence plugin, Player player, String nameArg, boolean resadmin) {
        String localName = nameArg;
        if (localName != null) {
            int colon = localName.indexOf(':');
            if (colon > 0 && colon < localName.length() - 1) {
                String prefixServer = localName.substring(0, colon);
                if (plugin.getConfigManager().matchesCrossServerName(prefixServer, plugin.getStorageServerName())) {
                    localName = localName.substring(colon + 1);
                }
            }
        }
        ClaimedResidence local = localName != null
                ? plugin.getResidenceManager().getByName(localName)
                : plugin.getResidenceManager().getByLoc(player.getLocation());
        if (local != null) {
            if (!resadmin && !local.isOwner(player)) {
                lm.Residence_NotOwner.sendMessage(player);
                return null;
            }
            return CrossServerManager.toEntry(local, plugin.getStorageServerName(), plugin.getGuiMetaManager());
        }

        if (nameArg != null && plugin.getCrossServerManager() != null && plugin.getCrossServerManager().isEnabled()) {
            List<RemoteResidenceEntry> matches = plugin.getCrossServerManager().findRemoteMatches(nameArg);
            if (matches.size() == 1) {
                RemoteResidenceEntry entry = matches.get(0);
                if (!resadmin && !entry.isOwner(player.getUniqueId())) {
                    lm.Residence_NotOwner.sendMessage(player);
                    return null;
                }
                return entry;
            }
        }

        lm.Invalid_Residence.sendMessage(player);
        return null;
    }

    @Override
    public void getLocale() {
        ConfigReader c = Residence.getInstance().getLocaleManager().getLocaleConfig();
        c.get("Description", "Open Residence Browser & Management GUI");
        c.get("Info", Arrays.asList(
                "&eUsage: &6/res gui [all|mine|pinned|manage|alias|desc|icon|toggle]",
                "Browse, favorite, like, manage, and teleport to local or cross-server residences."));
        LocaleManager.addTabCompleteMain(this, "all%%mine%%pinned%%manage%%alias%%desc%%icon%%toggle", "[residence]");
    }
}
