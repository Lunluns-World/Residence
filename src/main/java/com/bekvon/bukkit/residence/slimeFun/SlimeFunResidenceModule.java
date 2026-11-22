package com.bekvon.bukkit.residence.slimeFun;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.ResAdmin;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.listeners.ResidenceBlockListener;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagCombo;
import com.bekvon.bukkit.residence.protection.ResidencePermissions;

import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.ProtectionModule;

public class SlimeFunResidenceModule implements ProtectionModule {

    private final Residence residence;

    public SlimeFunResidenceModule(Plugin plugin) {
        this.residence = (Residence) plugin;
    }

    @Override
    public Plugin getPlugin() {
        return this.residence;
    }

    @Override
    public void load() {
    }

    @Override
    public boolean hasPermission(OfflinePlayer op, Location loc, Interaction action) {

        if (op == null || loc == null)
            return false;

        if (residence.isDisabledWorldListener(loc.getWorld()))
            return true;

        Player onlinePlayer = Bukkit.getPlayer(op.getUniqueId());
        if (onlinePlayer != null && ResAdmin.isResAdmin(onlinePlayer))
            return true;

        ClaimedResidence res = residence.getResidenceManager().getByLoc(loc);
        ResidencePlayer rPlayer = ResidencePlayer.get(op.getUniqueId());

        switch (action) {
        case INTERACT_BLOCK:
            if (res != null) {
                FlagPermissions perms = res.getPermissions();
                boolean allow = onlinePlayer != null
                        ? perms.playerHas(onlinePlayer, Flags.container, perms.playerHas(onlinePlayer, Flags.use, false))
                        : perms.playerHas(rPlayer, Flags.container, perms.playerHas(rPlayer, Flags.use, false));
                if (!allow && onlinePlayer != null)
                    lm.Flag_Deny.sendMessage(onlinePlayer, Flags.container);
                return allow;
            }
            return true;
        case BREAK_BLOCK:
            if (onlinePlayer == null) {
                if (res == null)
                    return true;
                FlagPermissions perms = res.getPermissions();
                return perms.playerHas(rPlayer, Flags.destroy, perms.playerHas(rPlayer, Flags.build, true));
            }
            ResidencePermissions.setEventCallsSuspended(true);
            try {
                return ResidenceBlockListener.canBreakBlock(onlinePlayer, loc, true);
            } finally {
                ResidencePermissions.setEventCallsSuspended(false);
            }
        case PLACE_BLOCK:
            if (onlinePlayer == null) {
                if (res == null)
                    return true;
                FlagPermissions perms = res.getPermissions();
                return perms.playerHas(rPlayer, Flags.place, perms.playerHas(rPlayer, Flags.build, true));
            }
            ResidencePermissions.setEventCallsSuspended(true);
            try {
                return ResidenceBlockListener.canPlaceBlock(onlinePlayer, loc.getBlock(), true);
            } finally {
                ResidencePermissions.setEventCallsSuspended(false);
            }
        case INTERACT_ENTITY:
            if (res != null) {
                FlagPermissions perms = res.getPermissions();
                boolean allow = onlinePlayer != null
                        ? perms.playerHas(onlinePlayer, Flags.use, true)
                        : perms.playerHas(rPlayer, Flags.use, true);
                if (!allow && onlinePlayer != null)
                    lm.Flag_Deny.sendMessage(onlinePlayer, Flags.use);
                return allow;
            }
            return true;
        case ATTACK_ENTITY:
            if (res != null) {
                FlagPermissions perms = res.getPermissions();
                boolean allowAnimal = !Flags.animalkilling.isGlobalyEnabled()
                        || (onlinePlayer != null
                                ? perms.playerHas(onlinePlayer, Flags.animalkilling, true)
                                : perms.playerHas(rPlayer, Flags.animalkilling, true));
                if (!allowAnimal) {
                    if (onlinePlayer != null)
                        lm.Flag_Deny.sendMessage(onlinePlayer, Flags.animalkilling);
                    return false;
                }
                boolean allowMob = !Flags.mobkilling.isGlobalyEnabled()
                        || (onlinePlayer != null
                                ? perms.playerHas(onlinePlayer, Flags.mobkilling, true)
                                : perms.playerHas(rPlayer, Flags.mobkilling, true));
                if (!allowMob) {
                    if (onlinePlayer != null)
                        lm.Flag_Deny.sendMessage(onlinePlayer, Flags.mobkilling);
                    return false;
                }
            }
            return true;
        case ATTACK_PLAYER:
            if (Flags.pvp.isGlobalyEnabled()) {
                if (FlagPermissions.has(loc, Flags.pvp, FlagCombo.OnlyFalse)) {
                    if (onlinePlayer != null)
                        lm.General_NoPVPZone.sendMessage(onlinePlayer);
                    return false;
                }
                if (onlinePlayer != null && FlagPermissions.has(onlinePlayer.getLocation(), Flags.pvp, FlagCombo.OnlyFalse)) {
                    lm.General_NoPVPZone.sendMessage(onlinePlayer);
                    return false;
                }
                if (residence.getWorldFlags().getPerms(loc.getWorld()).has(Flags.pvp, FlagCombo.OnlyFalse)) {
                    ClaimedResidence attackerRes = onlinePlayer != null ? residence.getResidenceManager().getByLoc(onlinePlayer.getLocation()) : null;
                    if (res == null || !res.getPermissions().has(Flags.pvp, FlagCombo.OnlyTrue)
                            || (onlinePlayer != null && (attackerRes == null || !attackerRes.getPermissions().has(Flags.pvp, FlagCombo.OnlyTrue)))) {
                        if (onlinePlayer != null)
                            lm.General_WorldPVPDisabled.sendMessage(onlinePlayer);
                        return false;
                    }
                }
            }
            return true;
        default:
            if (res != null) {
                FlagPermissions perms = res.getPermissions();
                return onlinePlayer != null
                        ? perms.playerHas(onlinePlayer, Flags.build, true)
                        : perms.playerHas(rPlayer, Flags.build, true);
            }
            return true;
        }
    }

}
