package com.bekvon.bukkit.residence.listeners;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.minecart.CommandMinecart;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import com.bekvon.bukkit.residence.ConfigManager;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.chat.ChatChannel;
import com.bekvon.bukkit.residence.chat.ChatManager;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.ResAdmin;
import com.bekvon.bukkit.residence.containers.ResidencePlayer;
import com.bekvon.bukkit.residence.containers.StuckInfo;
import com.bekvon.bukkit.residence.containers.Visualizer;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.containers.playerPersistentData;
import com.bekvon.bukkit.residence.containers.playerTempData;
import com.bekvon.bukkit.residence.economy.rent.RentableLand;
import com.bekvon.bukkit.residence.economy.rent.RentedLand;
import com.bekvon.bukkit.residence.event.ResidenceChangedEvent;
import com.bekvon.bukkit.residence.event.ResidenceDeleteEvent;
import com.bekvon.bukkit.residence.event.ResidenceEvent;
import com.bekvon.bukkit.residence.event.ResidenceFlagChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceOwnerChangeEvent;
import com.bekvon.bukkit.residence.event.ResidenceRenameEvent;
import com.bekvon.bukkit.residence.permissions.PermissionGroup;
import com.bekvon.bukkit.residence.permissions.PermissionManager.ResPerm;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagCombo;
import com.bekvon.bukkit.residence.protection.FlagPermissions.FlagState;
import com.bekvon.bukkit.residence.protection.ResidenceManager.ChunkRef;
import com.bekvon.bukkit.residence.selection.SelectionManager;
import com.bekvon.bukkit.residence.selection.VisualizerConfig;
import com.bekvon.bukkit.residence.selection.selectorOptions;
import com.bekvon.bukkit.residence.signsStuff.Signs;
import com.bekvon.bukkit.residence.utils.GetTime;
import com.bekvon.bukkit.residence.utils.PlayerLocationChecker;
import com.bekvon.bukkit.residence.utils.Teleporting;
import com.bekvon.bukkit.residence.utils.PotionUtils;
import com.bekvon.bukkit.residence.utils.Utils;

import net.Zrips.CMILib.ActionBar.CMIActionBar;
import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.Container.CMINumber;
import net.Zrips.CMILib.Container.CMIWorld;
import net.Zrips.CMILib.Items.CMIMC;
import net.Zrips.CMILib.Items.CMIMaterial;
import net.Zrips.CMILib.Logs.CMIDebug;
import net.Zrips.CMILib.TitleMessages.CMITitleMessage;
import net.Zrips.CMILib.Util.CMIVersionChecker;
import net.Zrips.CMILib.RawMessages.RawMessage;
import net.Zrips.CMILib.Version.Version;
import net.Zrips.CMILib.Version.Schedulers.CMIScheduler;

public class ResidencePlayerListener implements Listener {

    private final Residence plugin;

    private PlayerLocationChecker locationChecker = new PlayerLocationChecker();

    private final static String ResidenceFlyState = "ResidenceFlyState";

    public ResidencePlayerListener(Residence plugin) {
        this.plugin = plugin;

        playerTempData.clear();

        locationChecker.start();
    }

    public void reload() {
        playerTempData.clear();
    }

    @EventHandler
    public void onJump(PlayerMoveEvent event) {

        if (!Flags.jump3.isGlobalyEnabled() && !Flags.jump2.isGlobalyEnabled())
            return;

        Player player = event.getPlayer();
        if (player.isFlying())
            return;

        if (event.getTo().getY() - event.getFrom().getY() != 0.41999998688697815D)
            return;

        if (player.hasMetadata("NPC"))
            return;

        FlagPermissions perms = FlagPermissions.getPerms(player.getLocation());
        if (Flags.jump2.isGlobalyEnabled() && perms.has(Flags.jump2, FlagCombo.OnlyTrue))
            player.setVelocity(player.getVelocity().add(player.getVelocity().multiply(0.3)));
        else if (Flags.jump3.isGlobalyEnabled() && perms.has(Flags.jump3, FlagCombo.OnlyTrue))
            player.setVelocity(player.getVelocity().add(player.getVelocity().multiply(0.6)));

    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerPickupItemEvent(PlayerPickupItemEvent event) {
        if (!Flags.itempickup.isGlobalyEnabled())
            return;

        // As of 1.12 version EntityPickupItemEvent event needs to be used. This is only
        // for older servers
        // New handling located at ResidencePlayerListener1_12
        if (Version.isCurrentEqualOrHigher(Version.v1_12_R1))
            return;

        ClaimedResidence res = plugin.getResidenceManager().getByLoc(event.getItem().getLocation());
        if (res == null)
            return;
        if (event.getPlayer().hasMetadata("NPC"))
            return;
        if (!res.getPermissions().playerHas(event.getPlayer(), Flags.itempickup, FlagCombo.OnlyFalse))
            return;
        if (ResPerm.bypass_itempickup.hasPermission(event.getPlayer(), 10000L))
            return;
        event.setCancelled(true);
        event.getItem().setPickupDelay(plugin.getConfigManager().getItemPickUpDelay() * 20);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerDropItemEvent(PlayerDropItemEvent event) {

        if (!Flags.itemdrop.isGlobalyEnabled())
            return;

        Player player = event.getPlayer();

        ClaimedResidence res = ClaimedResidence.getByLoc(player.getLocation());

        if (res == null)
            return;

        if (player.hasMetadata("NPC"))
            return;

        if (!res.getPermissions().playerHas(player, Flags.itemdrop, FlagCombo.OnlyFalse))
            return;

        if (ResPerm.bypass_itemdrop.hasPermission(player, 10000L))
            return;

        event.setCancelled(true);
    }

    // Adding to chat prefix main residence name
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerGlobalChat(AsyncPlayerChatEvent event) {
        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getPlayer()))
            return;
        if (!plugin.getConfigManager().isGlobalChatEnabled())
            return;
        if (!plugin.getConfigManager().isGlobalChatSelfModify())
            return;
        Player player = event.getPlayer();

        ResidencePlayer rPlayer = plugin.getPlayerManager().getResidencePlayer(player);

        if (rPlayer == null)
            return;

        if (rPlayer.getResList().isEmpty())
            return;

        ClaimedResidence res = rPlayer.getMainResidence();

        if (res == null)
            return;

        String honorific = plugin.getConfigManager().getGlobalChatFormat().replace("%1", res.getTopParentName());

        String format = event.getFormat();
        format = format.replace("%1$s", honorific + "%1$s");
        event.setFormat(format);
    }

    // Changing chat prefix variable to residence name
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerChatGlobalLow(AsyncPlayerChatEvent event) {
        procEvent(event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    private void processNEvent(AsyncPlayerChatEvent event) {
        procEvent(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    private void processHEvent(AsyncPlayerChatEvent event) {
        procEvent(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void processHHEvent(AsyncPlayerChatEvent event) {
        procEvent(event);
    }

    private void procEvent(AsyncPlayerChatEvent event) {
        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getPlayer()))
            return;
        if (!plugin.getConfigManager().isGlobalChatEnabled())
            return;
        if (plugin.getConfigManager().isGlobalChatSelfModify())
            return;

        String format = event.getFormat();
        if (!format.contains("{residence}"))
            return;

        Player player = event.getPlayer();

        ResidencePlayer rPlayer = plugin.getPlayerManager().getResidencePlayer(player);

        if (rPlayer == null)
            return;

        ClaimedResidence res = rPlayer.getMainResidence();

        String honorific = plugin.getConfigManager().getGlobalChatFormat().replace("%1", res == null ? "" : res.getTopParentName());
        if (honorific.equalsIgnoreCase(" "))
            honorific = "";

        format = format.replace("{residence}", honorific);
        event.setFormat(format);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onResidenceBackup(ResidenceFlagChangeEvent event) {
        if (!event.getFlag().equalsIgnoreCase(Flags.backup.toString()))
            return;
        Player player = event.getPlayer();
        if (event.getResidence() != null && event.getResidence().isRented()) {
            event.setCancelled(true);
            return;
        }
        if (!plugin.getConfigManager().RestoreAfterRentEnds)
            return;
        if (!plugin.getConfigManager().SchematicsSaveOnFlagChange)
            return;
        if (plugin.getSchematicManager() == null)
            return;
        if (player != null && !ResPerm.backup.hasPermission(player)) {
            event.setCancelled(true);
            return;
        }
        if (event.getNewState() == FlagState.TRUE) {
            plugin.getSchematicManager().save(event.getResidence());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidenceBackupRename(ResidenceRenameEvent event) {
        if (plugin.getSchematicManager() == null)
            return;
        plugin.getSchematicManager().rename(event.getResidence(), event.getNewResidenceName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidenceDelete(ResidenceDeleteEvent event) {
        if (plugin.getSchematicManager() == null)
            return;
        plugin.getSchematicManager().delete(event.getResidence());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (!plugin.getConfigManager().isRentInformOnEnding())
            return;
        final Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline())
                return;
            List<ClaimedResidence> list = plugin.getRentManager().getRentedLandsList(player.getUniqueId());
            if (list.isEmpty())
                return;
            for (ClaimedResidence one : list) {
                RentedLand rentedland = one.getRentedLand();
                if (rentedland == null)
                    continue;
                if (rentedland.AutoPay)
                    continue;
                if (rentedland.endTime - System.currentTimeMillis() < plugin.getConfigManager().getRentInformBefore() * 60 * 24 * 7) {
                    lm.Residence_EndingRent.sendMessage(player, one, GetTime.getTime(rentedland.endTime));
                }
            }
        }, null, plugin.getConfigManager().getRentInformDelay() * 20L);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFishingRodUse(PlayerFishEvent event) {

        if (event == null)
            return;

        Player player = event.getPlayer();

        if (FlagPermissions.shouldIgnoreCheck(Flags.hook, player)) {
            return;
        }
        Entity caught = event.getCaught();
        if (caught == null)
            return;
        if (Utils.isArmorStand(caught) || caught instanceof Vehicle || caught instanceof LivingEntity) {
            if (FlagPermissions.shouldDenyAndNotify(player, caught, Flags.hook, null)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlagChangeDayNight(ResidenceFlagChangeEvent event) {
        if (event.isCancelled())
            return;

        if (!event.getFlag().equalsIgnoreCase(Flags.day.toString()) &&
                !event.getFlag().equalsIgnoreCase(Flags.night.toString()))
            return;

        switch (event.getNewState()) {
        case NEITHER:
        case FALSE:
            for (Player one : event.getResidence().getPlayersInResidence())
                one.resetPlayerTime();
            break;
        case INVALID:
            break;
        case TRUE:
            if (event.getFlag().equalsIgnoreCase(Flags.day.toString()))
                for (Player one : event.getResidence().getPlayersInResidence())
                    one.setPlayerTime(6000L, false);
            if (event.getFlag().equalsIgnoreCase(Flags.night.toString()))
                for (Player one : event.getResidence().getPlayersInResidence())
                    one.setPlayerTime(14000L, false);
            break;
        default:
            break;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlagChangeGlow(ResidenceFlagChangeEvent event) {
        if (event.isCancelled())
            return;

        if (!event.getFlag().equalsIgnoreCase(Flags.glow.toString()))
            return;

        switch (event.getNewState()) {
        case NEITHER:
        case FALSE:
            if (Version.isCurrentEqualOrHigher(Version.v1_9_R1) && event.getFlag().equalsIgnoreCase(Flags.glow.toString()))
                for (Player one : event.getResidence().getPlayersInResidence())
                    one.setGlowing(false);
            break;
        case INVALID:
            break;
        case TRUE:
            if (event.getFlag().equalsIgnoreCase(Flags.glow.toString()) && Version.isCurrentEqualOrHigher(Version.v1_9_R1))
                for (Player one : event.getResidence().getPlayersInResidence()) {
                    one.setGlowing(true);
                }
            break;
        default:
            break;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidenceDeleteEvent(ResidenceDeleteEvent event) {
        if (event.isCancelled())
            return;

        ClaimedResidence res = event.getResidence();
        if (res.getPermissions().has(Flags.wspeed1, FlagCombo.OnlyTrue) || res.getPermissions().has(Flags.wspeed2, FlagCombo.OnlyTrue))
            for (Player one : event.getResidence().getPlayersInResidence())
                one.setWalkSpeed(0.2F);

        if (res.getPermissions().has(Flags.sun, FlagCombo.OnlyTrue) || res.getPermissions().has(Flags.rain, FlagCombo.OnlyTrue))
            for (Player one : event.getResidence().getPlayersInResidence())
                one.resetPlayerWeather();

        if (res.getPermissions().has(Flags.fly, FlagCombo.OnlyTrue))
            for (Player one : event.getResidence().getPlayersInResidence())
                fly(one, false, event.getResidence());

        if (res.getPermissions().has(Flags.glow, FlagCombo.OnlyTrue) && Version.isCurrentEqualOrHigher(Version.v1_9_R1))
            for (Player one : event.getResidence().getPlayersInResidence())
                one.setGlowing(false);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerQuitEvent(PlayerQuitEvent event) {

        Player player = event.getPlayer();

        plugin.getPermissionManager().removeFromCache(player);

        checkSpecialFlags(player, plugin.getResidenceManager().getByLoc(player.getLocation()), playerTempData.getCurrentResidence(player.getUniqueId()));

        plugin.getPlayerManager().getResidencePlayer(player).onQuit();
        plugin.getTeleportMap().remove(player.getUniqueId());
        playerTempData.clearCache(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlagChangeWSpeed(ResidenceFlagChangeEvent event) {
        if (event.isCancelled())
            return;

        if (!event.getFlag().equalsIgnoreCase(Flags.wspeed1.toString()) &&
                !event.getFlag().equalsIgnoreCase(Flags.wspeed2.toString()))
            return;

        switch (event.getNewState()) {
        case NEITHER:
        case FALSE:
            for (Player one : event.getResidence().getPlayersInResidence())
                one.setWalkSpeed(0.2F);
            break;
        case INVALID:
            break;
        case TRUE:
            if (event.getFlag().equalsIgnoreCase(Flags.wspeed1.toString())) {
                for (Player one : event.getResidence().getPlayersInResidence())
                    one.setWalkSpeed(plugin.getConfigManager().getWalkSpeed1().floatValue());
                if (event.getResidence().getPermissions().has(Flags.wspeed2, FlagCombo.OnlyTrue))
                    event.getResidence().getPermissions().setFlag(Flags.wspeed2.toString(), FlagState.NEITHER);
            } else if (event.getFlag().equalsIgnoreCase(Flags.wspeed2.toString())) {
                for (Player one : event.getResidence().getPlayersInResidence())
                    one.setWalkSpeed(plugin.getConfigManager().getWalkSpeed2().floatValue());
                if (event.getResidence().getPermissions().has(Flags.wspeed1, FlagCombo.OnlyTrue))
                    event.getResidence().getPermissions().setFlag(Flags.wspeed1.toString(), FlagState.NEITHER);
            }
            break;
        default:
            break;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlagChangeWSpeed(ResidenceOwnerChangeEvent event) {
        if (event.isCancelled())
            return;

        ClaimedResidence res = event.getResidence();
        if (res.getPermissions().has(Flags.wspeed1, FlagCombo.OnlyTrue) || res.getPermissions().has(Flags.wspeed2, FlagCombo.OnlyTrue))
            for (Player one : event.getResidence().getPlayersInResidence())
                one.setWalkSpeed(0.2F);

        if (res.getPermissions().has(Flags.sun, FlagCombo.OnlyTrue) || res.getPermissions().has(Flags.rain, FlagCombo.OnlyTrue))
            for (Player one : event.getResidence().getPlayersInResidence())
                one.resetPlayerWeather();

        if (res.getPermissions().has(Flags.fly, FlagCombo.OnlyTrue))
            for (Player one : event.getResidence().getPlayersInResidence())
                fly(one, false, event.getResidence());

        if (res.getPermissions().has(Flags.glow, FlagCombo.OnlyTrue) && Version.isCurrentEqualOrHigher(Version.v1_9_R1))
            for (Player one : event.getResidence().getPlayersInResidence())
                one.setGlowing(false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlagChangeJump(ResidenceFlagChangeEvent event) {
        if (event.isCancelled())
            return;

        if (!event.getFlag().equalsIgnoreCase(Flags.jump2.toString()) &&
                !event.getFlag().equalsIgnoreCase(Flags.jump3.toString()))
            return;

        switch (event.getNewState()) {
        case NEITHER:
        case FALSE:
        case INVALID:
            break;
        case TRUE:
            if (event.getFlag().equalsIgnoreCase(Flags.jump2.toString())) {
                if (event.getResidence().getPermissions().has(Flags.jump3, FlagCombo.OnlyTrue))
                    event.getResidence().getPermissions().setFlag(Flags.jump3.toString(), FlagState.NEITHER);
            } else if (event.getFlag().equalsIgnoreCase(Flags.jump3.toString()) && event.getResidence().getPermissions().has(Flags.jump2, FlagCombo.OnlyTrue)) {
                event.getResidence().getPermissions().setFlag(Flags.jump2.toString(), FlagState.NEITHER);
            }
            break;
        default:
            break;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlagChangeSunRain(ResidenceFlagChangeEvent event) {
        if (event.isCancelled())
            return;

        if (!event.getFlag().equalsIgnoreCase(Flags.sun.toString()) && !event.getFlag().equalsIgnoreCase(Flags.rain.toString()))
            return;

        switch (event.getNewState()) {
        case NEITHER:
        case FALSE:
            for (Player one : event.getResidence().getPlayersInResidence())
                one.resetPlayerWeather();
            break;
        case INVALID:
            break;
        case TRUE:
            if (event.getFlag().equalsIgnoreCase(Flags.sun.toString()))
                for (Player player : event.getResidence().getPlayersInResidence())
                    player.setPlayerWeather(WeatherType.CLEAR);
            if (event.getFlag().equalsIgnoreCase(Flags.rain.toString()))
                for (Player player : event.getResidence().getPlayersInResidence())
                    player.setPlayerWeather(WeatherType.DOWNFALL);
            break;
        default:
            break;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlagChangeFly(ResidenceFlagChangeEvent event) {
        if (event.isCancelled())
            return;

        if (!event.getFlag().equalsIgnoreCase(Flags.fly.toString()))
            return;

        switch (event.getNewState()) {
        case NEITHER:
        case FALSE:
            for (Player one : event.getResidence().getPlayersInResidence())
                fly(one, false, event.getResidence());
            break;
        case INVALID:
            break;
        case TRUE:
            for (Player one : event.getResidence().getPlayersInResidence())
                fly(one, true, event.getResidence());
            break;
        default:
            break;
        }
    }

    private boolean canUseCommand(String command, List<String> whiteListed, List<String> blackListed) {
        int white = 0;
        for (String oneWhite : whiteListed) {
            String t = oneWhite.toLowerCase();
            if (command.startsWith("/" + t)) {
                if (t.contains("_") && t.split("_").length > white)
                    white = t.split("_").length;
                else if (white == 0)
                    white = 1;
            }
        }

        int black = 0;
        for (String oneBlack : blackListed) {
            String t = oneBlack.toLowerCase();
            if (command.startsWith("/" + t)) {
                if (command.contains("_"))
                    black = t.split("_").length;
                else
                    black = 1;
                break;
            }
        }

        if (black == 0)
            for (String oneBlack : blackListed) {
                String t = oneBlack.toLowerCase();
                if (t.equalsIgnoreCase("*")) {
                    if (command.contains("_"))
                        black = command.split("_").length;
                    else
                        black = 1;
                    break;
                }
            }

        return white != 0 && white >= black || black == 0;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {

        Player player = event.getPlayer();

        if (FlagPermissions.shouldIgnoreCheck(Flags.command, player)) {
            return;
        }
        FlagPermissions perms = FlagPermissions.getPerms(player.getLocation(), player);

        FlagPermissions globalPerm = plugin.getWorldFlags().getPerms(player);
        boolean globalLimited = globalPerm.playerHas(player, Flags.command, FlagCombo.OnlyFalse);
        boolean areaAllowed = perms.playerHas(player, Flags.command, FlagCombo.OnlyTrue);

        if (!globalLimited && !areaAllowed)
            return;

        if (plugin.getPermissionManager().isResidenceAdmin(player))
            return;

        if (ResPerm.bypass_command.hasPermission(player, 10000L))
            return;

        ClaimedResidence res = getCurrentResidence(player.getUniqueId());

        String msg = event.getMessage().replace(" ", "_").toLowerCase();

        if (res == null) {
            if (!globalLimited)
                return;
            if (canUseCommand(msg, globalPerm.getCMDWhiteList(), globalPerm.getCMDBlackList()))
                return;
            event.setCancelled(true);
            lm.Residence_BaseFlagDeny.sendMessage(player, Flags.command);
            return;
        }

        List<String> w = new ArrayList<String>(res.getCmdWhiteList());
        List<String> b = new ArrayList<String>(res.getCmdBlackList());

        if (!areaAllowed) {
            w.clear();
            b.clear();
        }

        if (globalPerm.isInheritCMDLimits()) {
            w.addAll(globalPerm.getCMDWhiteList());
            b.removeAll(globalPerm.getCMDWhiteList());
            b.addAll(globalPerm.getCMDBlackList());
        }

        if (canUseCommand(msg, w, b))
            return;

        event.setCancelled(true);
        lm.Residence_FlagDeny.sendMessage(player, Flags.command, res.getName());

    }

    private void handlePlayerClickMarketSign(PlayerInteractEvent event, Player player, Block block) {

        if (event.useInteractedBlock() == Result.DENY) {
            return;
        }
        if (!CMIMaterial.isSign(block.getType())) {
            return;
        }
        Location loc = block.getLocation();

        Signs s = plugin.getSignUtil().getSigns().getResSign(loc);

        if (s == null)
            return;

        ClaimedResidence res = s.getResidence();

        boolean ForSale = res.isForSell();
        boolean ForRent = res.isForRent();
        String landName = res.getName();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (ForRent && res.isRented() && player.isSneaking()) {
                Bukkit.dispatchCommand(player, "res market release " + landName);
                return;
            }
            if (!ForSale && !ForRent)
                return;

            if (!Bukkit.dispatchCommand(player, "res market info " + landName))
                return;
            RawMessage actions = new RawMessage();
            if (ForSale) {
                actions.addText(lm.Sign_ConfirmBuy.getMessage()).addCommand("res market buy " + landName);
            } else if (!res.isRented()) {
                actions.addText(lm.Sign_ConfirmRent.getMessage()).addCommand("res market rent " + landName + " false");
                RentableLand rentable = res.getRentable();
                if (rentable != null && rentable.AllowAutoPay) {
                    actions.addText(" ");
                    actions.addText(lm.Sign_ConfirmRentAutoPay.getMessage()).addCommand("res market rent " + landName + " true");
                }
            }
            if (ForSale || !res.isRented())
                actions.show(player);
        } else if (event.getAction() == Action.LEFT_CLICK_BLOCK && ForRent && res.isRented() && plugin.getRentManager().getRentingPlayer(res).equals(player.getName())) {
            plugin.getRentManager().payRent(player, res, false);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSignCreate(SignChangeEvent event) {

        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getPlayer()))
            return;
        Block block = event.getBlock();

        if (!(block.getState() instanceof Sign))
            return;

        Sign sign = (Sign) block.getState();

        if (!CMIChatColor.stripColor(event.getLine(0)).equalsIgnoreCase(lm.Sign_TopLine.getMessage()))
            return;

        Signs signInfo = new Signs();

        Location loc = sign.getLocation();

        Player player = event.getPlayer();
        if (player.hasMetadata("NPC"))
            return;
        ClaimedResidence res = null;
        if (!event.getLine(1).equalsIgnoreCase("")) {

            String resname = event.getLine(1);
            if (!event.getLine(2).equalsIgnoreCase(""))
                resname += "." + event.getLine(2);
            if (!event.getLine(3).equalsIgnoreCase(""))
                resname += "." + event.getLine(3);

            res = plugin.getResidenceManager().getByName(resname);

            if (res == null) {
                lm.Invalid_Residence.sendMessage(player);
                return;
            }
        } else {
            res = plugin.getResidenceManager().getByLoc(loc);
        }

        if (res == null) {
            lm.Invalid_Residence.sendMessage(player);
            return;
        }

        ClaimedResidence currentRes = plugin.getResidenceManager().getByLoc(loc);
        if (!ResAdmin.isResAdmin(player)
                && (!res.isOwner(player) || (currentRes != null && !currentRes.isOwner(player)))) {
            lm.Residence_NotOwner.sendMessage(player);
            return;
        }

        if (res.getSignsInResidence().size() >= plugin.getConfigManager().getSignsMaxPerResidence()) {
            lm.Sign_TooMany.sendMessage(player);
            return;
        }

        final ClaimedResidence residence = res;

        signInfo.setResidence(res);
        signInfo.setLocation(loc);
        plugin.getSignUtil().getSigns().addSign(signInfo);
        plugin.getSignUtil().saveSigns();

        Bukkit.getRegionScheduler().runDelayed(plugin, loc, task -> plugin.getSignUtil().checkSign(residence), 5L);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignDestroy(BlockBreakEvent event) {

        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getPlayer()))
            return;

        Block block = event.getBlock();

        if (!CMIMaterial.isSign(block.getType()))
            return;

        Location loc = block.getLocation();
        if (event.getPlayer().hasMetadata("NPC"))
            return;

        Signs s = plugin.getSignUtil().getSigns().getResSign(loc);
        if (s == null)
            return;

        plugin.getSignUtil().getSigns().removeSign(s);
        if (s.getResidence() != null)
            s.getResidence().getSignsInResidence().remove(s);
        plugin.getSignUtil().saveSigns();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        plugin.getChatManager().removeFromChannel(uuid);
        ChatManager.removePlayerResidenceChat(uuid);

        playerPersistentData.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerWorldChange(PlayerChangedWorldEvent event) {
        // Disabling listener if flag disabled globally
        if (!Flags.nofly.isGlobalyEnabled())
            return;
        Player player = event.getPlayer();
        if (player.hasMetadata("NPC"))
            return;
        FlagPermissions perms = FlagPermissions.getPerms(player.getLocation(), player);

        if ((player.getAllowFlight() || player.isFlying()) && perms.has(Flags.nofly, false) && !ResAdmin.isResAdmin(player)
                && !ResPerm.bypass_nofly.hasPermission(player, 10000L)) {

            ClaimedResidence res = plugin.getResidenceManager().getByLoc(player.getLocation());
            if (res != null && res.isOwner(player))
                return;

            Location lc = player.getLocation();
            Location location = new Location(lc.getWorld(), lc.getX(), lc.getBlockY(), lc.getZ());
            location.setPitch(lc.getPitch());
            location.setYaw(lc.getYaw());
            int from = location.getBlockY();

            int maxH = location.getWorld().getHighestBlockAt(location).getLocation().getBlockY() + 3;

            if (location.getWorld().getEnvironment() == Environment.NETHER)
                maxH = 100;

            for (int i = 0; i < maxH; i++) {
                location.setY(from - i);
                Block block = location.getBlock();
                if (!isEmptyBlock(block)) {
                    location.setY(from - i + 1);
                    break;
                }
                if (location.getBlockY() <= 0) {
                    player.setFlying(false);
                    player.setAllowFlight(false);
                    lm.Residence_FlagDeny.sendMessage(player, Flags.nofly, location.getWorld().getName());
                    return;
                }
            }
            lm.Residence_FlagDeny.sendMessage(player, Flags.nofly, location.getWorld().getName());
            player.closeInventory();
            Teleporting.teleport(player, location);

            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (plugin.getPermissionManager().isResidenceAdmin(player)) {
            ResAdmin.turnResAdminOn(player);
        }

        player.getScheduler().runDelayed(plugin, task -> {
            handleNewLocation(player, player.getLocation(), true);
            ClaimedResidence res = plugin.getResidenceManager().getByLoc(player.getLocation());
            if (res != null) {
                if (Flags.wspeed1.isGlobalyEnabled() && res.getPermissions().has(Flags.wspeed1, FlagCombo.OnlyTrue))
                    player.setWalkSpeed(plugin.getConfigManager().getWalkSpeed1().floatValue());
                else if (Flags.wspeed2.isGlobalyEnabled() && res.getPermissions().has(Flags.wspeed2, FlagCombo.OnlyTrue))
                    player.setWalkSpeed(plugin.getConfigManager().getWalkSpeed2().floatValue());
            }
        }, null, 1L);

        plugin.getPlayerManager().playerJoin(player);

        if (ResPerm.versioncheck.hasPermission(player)) {
            CMIVersionChecker.VersionCheck(player, 11480, plugin.getDescription());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerSpawn(PlayerRespawnEvent event) {

        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getRespawnLocation()))
            return;
        Location loc = event.getRespawnLocation();
        boolean bed = event.isBedSpawn();
        Player player = event.getPlayer();
        if (player.hasMetadata("NPC"))
            return;
        ClaimedResidence res = plugin.getResidenceManager().getByLoc(loc);
        if (res == null)
            return;

        if (!res.getPermissions().playerHas(player, Flags.move, FlagCombo.OnlyFalse)) {
            return;
        }
        if (bed) {
            loc = player.getWorld().getSpawnLocation();
        }
        res = plugin.getResidenceManager().getByLoc(loc);
        if (res != null && res.getPermissions().playerHas(player, Flags.move, FlagCombo.OnlyFalse)) {
            res.kickFromResidence(player);
        }

        lm.General_NoSpawn.sendMessage(player);
        if (loc != null)
            event.setRespawnLocation(loc);

    }

    public static boolean isEmptyBlock(Block block) {
        CMIMaterial cb = CMIMaterial.get(block.getType());

        switch (cb) {
        case COBWEB:
        case STRING:
        case WALL_SIGN:
        case VINE:
        case TRIPWIRE_HOOK:
        case TRIPWIRE:
        case PAINTING:
        case ITEM_FRAME:
        case GLOW_ITEM_FRAME:
        case NONE:
            return true;
        default:
            break;
        }

        if (cb.isSapling())
            return true;
        if (cb.isAir())
            return true;
        if (cb.isButton())
            return true;

        return false;
    }

    private boolean isBlockFertilizable(CMIMaterial block) {
        if (block.containsCriteria(CMIMC.SAPLING)) {
            return true;
        }
        // 1.17+ has BlockFertilizeEvent, only need to check the Sapling above
        if (Version.isCurrentEqualOrHigher(Version.v1_17_0)) {
            return false;
        }
        switch (block) {
        case COCOA:
        case GRASS_BLOCK:
        case KELP:
        case KELP_PLANT:
        case SEAGRASS:
        case SEA_PICKLE:
        case SHORT_GRASS:
            return true;
        default:
            break;
        }
        return false;
    }

    private boolean isLegacyCauldronInteraction(CMIMaterial held) {
        // Cauldron uses CauldronLevelChangeEvent for checks on 1.9+
        if (Version.isCurrentEqualOrHigher(Version.v1_9_0)) {
            return false;
        }
        switch (held) {
        case GLASS_BOTTLE:
        case WATER_BUCKET:
            return true;
        default:
            return false;
        }
    }

    private boolean isBuildClickBlock(CMIMaterial block, CMIMaterial held, Player player) {
        if (held == CMIMaterial.BONE_MEAL) {
            return isBlockFertilizable(block);
        }
        if (block.containsCriteria(CMIMC.SIGN)) {
            return held.containsCriteria(CMIMC.DYE) || held == CMIMaterial.HONEYCOMB;
        }
        switch (block) {
        case CAULDRON:
            return !player.isSneaking() && isLegacyCauldronInteraction(held);
        case END_PORTAL_FRAME:
            // Fix upstream dupe
            // bug(https://github.com/PaperMC/Paper/issues/13586)
            return held == CMIMaterial.ENDER_EYE;
        case PUMPKIN:
            return held == CMIMaterial.SHEARS;
        case REDSTONE_WIRE:
            return true;
        case ROOTED_DIRT:
            // check Hoe interact Rooted_Dirt, Fix upstream dupe
            // bug(https://github.com/PaperMC/Paper/issues/13536)
            return held.name().endsWith("_HOE");
        case SPAWNER:
        case TRIAL_SPAWNER:
            return held.containsCriteria(CMIMC.SPAWNEGG);
        default:
            break;
        }
        return false;
    }

    private boolean isBuildClickBlockFace(CMIMaterial block, CMIMaterial held) {
        switch (held) {
        case ARMOR_STAND:
            return true;
        case COCOA_BEANS:
            return block == CMIMaterial.JUNGLE_LOG || block == CMIMaterial.JUNGLE_WOOD;
        default:
            break;
        }
        return false;
    }

    private boolean isBuildClickBlockTop(CMIMaterial block, CMIMaterial held) {
        if (held == CMIMaterial.END_CRYSTAL) {
            return block == CMIMaterial.BEDROCK || block == CMIMaterial.OBSIDIAN;
        }
        return false;
    }

    // Handle player placement or block modification when no specific event is
    // available
    // Even if dedicated events are added in the future, legacy version servers
    // still exist
    private void handlePlayerInteractBuild(PlayerInteractEvent event, Player player, Block block) {

        if (!Flags.place.isGlobalyEnabled() && !Flags.build.isGlobalyEnabled() && !Flags.destroy.isGlobalyEnabled()) {
            return;
        }
        if (event.useInteractedBlock() == Result.DENY) {
            return;
        }
        Location loc = null;

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            CMIMaterial blockType = CMIMaterial.get(block.getType());
            CMIMaterial heldItem = CMIMaterial.get(event.getItem());

            if (blockType == CMIMaterial.END_PORTAL_FRAME && heldItem == CMIMaterial.ENDER_EYE) {
                if (shouldDenyEndPortalActivation(player, block)) {
                    event.setCancelled(true);
                }
                return;
            }
            if (isBuildClickBlockTop(blockType, heldItem)) {
                if (shouldDenyEndCrystalPlacement(player, block, blockType)) {
                    event.setCancelled(true);
                }
                return;
            }
            if (heldItem.containsCriteria(CMIMC.SPAWNEGG) && blockType != CMIMaterial.SPAWNER && blockType != CMIMaterial.TRIAL_SPAWNER) {
                Location spawnLoc = block.getRelative(event.getBlockFace()).getLocation();
                if (shouldDenySpawnEggInteract(player, spawnLoc, heldItem)) {
                    event.setCancelled(true);
                }
                return;
            }

            if (isBuildClickBlock(blockType, heldItem, player)) {
                loc = block.getLocation();

            } else if (isBuildClickBlockFace(blockType, heldItem)) {
                loc = block.getRelative(event.getBlockFace()).getLocation();

            }
        } else if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            Block relative = block.getRelative(event.getBlockFace());
            CMIMaterial clickedMat = CMIMaterial.get(block.getType());
            CMIMaterial relativeMat = CMIMaterial.get(relative.getType());
            if (clickedMat == CMIMaterial.FIRE || clickedMat == CMIMaterial.SOUL_FIRE) {
                loc = block.getLocation();
            } else if (relativeMat == CMIMaterial.FIRE || relativeMat == CMIMaterial.SOUL_FIRE) {
                loc = relative.getLocation();
            }
        }
        if (loc == null) {
            return;
        }
        if (FlagPermissions.shouldDenyAndNotify(player, loc, Flags.place, Flags.build)) {
            event.setCancelled(true);
        }
    }

    private static final org.bukkit.block.BlockFace[] HORIZONTAL_FACES = new org.bukkit.block.BlockFace[] {
            org.bukkit.block.BlockFace.NORTH,
            org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.EAST,
            org.bukkit.block.BlockFace.WEST
    };

    private static org.bukkit.block.BlockFace getPerpendicularHorizontalFace(org.bukkit.block.BlockFace inward) {
        return (inward == org.bukkit.block.BlockFace.NORTH || inward == org.bukkit.block.BlockFace.SOUTH)
                ? org.bukkit.block.BlockFace.EAST
                : org.bukkit.block.BlockFace.SOUTH;
    }

    private boolean shouldDenyEndPortalActivation(Player player, Block clickedFrame) {
        if (FlagPermissions.shouldDenyAndNotify(player, clickedFrame.getLocation(), Flags.place, Flags.build)) {
            return true;
        }
        List<org.bukkit.block.BlockFace> candidateInwards = new ArrayList<>(4);
        if (clickedFrame.getBlockData() instanceof org.bukkit.block.data.type.EndPortalFrame) {
            org.bukkit.block.BlockFace facing = ((org.bukkit.block.data.type.EndPortalFrame) clickedFrame.getBlockData()).getFacing();
            if (facing != null) {
                candidateInwards.add(facing);
            }
        }
        for (org.bukkit.block.BlockFace face : HORIZONTAL_FACES) {
            if (!candidateInwards.contains(face)) {
                org.bukkit.block.BlockFace perp = getPerpendicularHorizontalFace(face);
                if (clickedFrame.getRelative(perp, 1).getType() == Material.END_PORTAL_FRAME
                        || clickedFrame.getRelative(perp, -1).getType() == Material.END_PORTAL_FRAME
                        || clickedFrame.getRelative(face, 1).getRelative(perp, 1).getType() == Material.END_PORTAL_FRAME
                        || clickedFrame.getRelative(face, 1).getRelative(perp, -1).getType() == Material.END_PORTAL_FRAME) {
                    candidateInwards.add(face);
                }
            }
        }
        if (candidateInwards.isEmpty()) {
            candidateInwards.addAll(Arrays.asList(HORIZONTAL_FACES));
        }

        for (org.bukkit.block.BlockFace inward : candidateInwards) {
            org.bukkit.block.BlockFace perp = getPerpendicularHorizontalFace(inward);
            boolean left1 = clickedFrame.getRelative(perp, -1).getType() == Material.END_PORTAL_FRAME;
            boolean left2 = clickedFrame.getRelative(perp, -2).getType() == Material.END_PORTAL_FRAME;
            boolean right1 = clickedFrame.getRelative(perp, 1).getType() == Material.END_PORTAL_FRAME;
            boolean right2 = clickedFrame.getRelative(perp, 2).getType() == Material.END_PORTAL_FRAME;
            boolean cornerLeft = clickedFrame.getRelative(inward, 1).getRelative(perp, -1).getType() == Material.END_PORTAL_FRAME;
            boolean cornerRight = clickedFrame.getRelative(inward, 1).getRelative(perp, 1).getType() == Material.END_PORTAL_FRAME;
            boolean midCornerLeft = clickedFrame.getRelative(inward, 1).getRelative(perp, -2).getType() == Material.END_PORTAL_FRAME;
            boolean midCornerRight = clickedFrame.getRelative(inward, 1).getRelative(perp, 2).getType() == Material.END_PORTAL_FRAME;

            List<Integer> startOffsets = new ArrayList<>(3);
            if (left1 || right1 || midCornerLeft || midCornerRight) {
                startOffsets.add(-1);
            }
            if (right1 || right2 || cornerLeft) {
                startOffsets.add(0);
            }
            if (left1 || left2 || cornerRight) {
                startOffsets.add(-2);
            }
            if (startOffsets.isEmpty()) {
                startOffsets.add(-1);
            }

            Set<Block> checkedBlocks = new HashSet<>(15);
            for (int startOffset : startOffsets) {
                for (int inwardDist = 1; inwardDist <= 3; inwardDist++) {
                    Block rowBase = clickedFrame.getRelative(inward, inwardDist);
                    for (int p = startOffset; p <= startOffset + 2; p++) {
                        Block interiorBlock = p == 0 ? rowBase : rowBase.getRelative(perp, p);
                        if (checkedBlocks.add(interiorBlock) && !canMutatePortalOrPodiumBlock(player, interiorBlock)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean shouldDenyEndCrystalPlacement(Player player, Block clickedBlock, CMIMaterial blockType) {
        Block above = clickedBlock.getRelative(org.bukkit.block.BlockFace.UP);
        if (FlagPermissions.shouldDenyAndNotify(player, above.getLocation(), Flags.place, Flags.build)) {
            return true;
        }
        if (blockType == CMIMaterial.BEDROCK && clickedBlock.getWorld().getEnvironment() == Environment.THE_END) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    for (int dy = -1; dy <= 4; dy++) {
                        Block podiumBlock = clickedBlock.getRelative(dx, dy, dz);
                        if (!canMutatePortalOrPodiumBlock(player, podiumBlock)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean canMutatePortalOrPodiumBlock(Player player, Block targetBlock) {
        if (!ResidenceBlockListener.canPlaceBlock(player, targetBlock, true)) {
            return false;
        }
        if (!ResidenceBlockListener.canBreakBlock(player, targetBlock, true)) {
            return false;
        }
        Material type = targetBlock.getType();
        if (!type.isAir() && (Utils.isContainer(type) || targetBlock.getState(false) instanceof InventoryHolder)) {
            if (Flags.container.isGlobalyEnabled()
                    && FlagPermissions.shouldDenyAndNotify(player, targetBlock, Flags.container, Flags.use)) {
                return false;
            }
            Flags denied = ContainerTransferListener.deniedDirectTransfer(player, targetBlock.getLocation(), ContainerTransferListener.TAKE, true);
            if (denied != null) {
                lm.Flag_Deny.sendMessage(player, denied);
                return false;
            }
        }
        return true;
    }

    private boolean shouldDenySpawnEggInteract(Player player, Location targetLoc, CMIMaterial eggMat) {
        if (ResAdmin.isResAdmin(player)) {
            return false;
        }
        boolean isMonsterEgg = isMonsterSpawnEgg(eggMat);
        Flags eggFlag = isMonsterEgg ? Flags.monster_spawn_egg : Flags.animal_spawn_egg;
        FlagPermissions perms = FlagPermissions.getPerms(targetLoc, player);
        boolean canPlace = perms.playerHas(player, Flags.place, perms.playerHas(player, Flags.build, true));
        if (eggFlag.isGlobalyEnabled()) {
            if (!perms.playerHas(player, eggFlag, canPlace)) {
                lm.Flag_Deny.sendMessage(player, perms.has(eggFlag, FlagCombo.OnlyFalse) ? eggFlag : Flags.place);
                return true;
            }
            return false;
        }
        if (!canPlace) {
            lm.Flag_Deny.sendMessage(player, Flags.place);
            return true;
        }
        return false;
    }

    private static boolean isMonsterSpawnEgg(CMIMaterial eggMat) {
        if (eggMat == null) {
            return false;
        }
        String name = eggMat.name();
        if (!name.endsWith("_SPAWN_EGG")) {
            return false;
        }
        String entityName = name.substring(0, name.length() - "_SPAWN_EGG".length());
        try {
            org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(entityName);
            if (type.getEntityClass() != null
                    && org.bukkit.entity.Enemy.class.isAssignableFrom(type.getEntityClass())) {
                return true;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return false;
    }

    private void handlePlayerPhysicalInteract(PlayerInteractEvent event, Player player, Block block) {

        if (event.useInteractedBlock() == Result.DENY) {
            return;
        }
        CMIMaterial mat = CMIMaterial.get(block.getType());
        Flags flag = null;
        // Start getting flags
        switch (mat) {
        case FARMLAND:
            flag = Flags.trample;
            break;
        case TURTLE_EGG:
            flag = Flags.destroy;
            break;
        default:
            if (mat.containsCriteria(CMIMC.PRESSUREPLATE)) {
                flag = Flags.pressure;
            }
            break;
        }
        if (flag == null || !flag.isGlobalyEnabled()) {
            return;
        }
        if (flag != Flags.trample && ResAdmin.isResAdmin(player)) {
            return;
        }
        FlagPermissions perms;
        // Start checking flags
        switch (flag) {
        // Farmland
        case trample:
            perms = FlagPermissions.getPerms(block.getLocation(), player);
            if (perms.playerHas(player, flag, (perms.playerHas(player, Flags.build, true)))) {
                return;
            }
            break;
        // Turtle Egg
        case destroy:
            perms = FlagPermissions.getPerms(block.getLocation(), player);
            if (perms.playerHas(player, flag, true)) {
                return;
            }
            break;
        // Pressure Plate
        case pressure:
            perms = FlagPermissions.getPerms(block.getLocation(), player);
            if (perms.playerHas(player, flag, (perms.playerHas(player, Flags.use, true)))) {
                return;
            }
            break;
        default:
            return;
        }
        event.setCancelled(true);
    }

    private void handlePlayerUseSelectionTool(PlayerInteractEvent event, Player player, Block block) {

        if (plugin.getWorldEditTool() == SelectionManager.getTool())
            return;

        if (player.getGameMode() == GameMode.CREATIVE)
            event.setCancelled(true);

        ResidencePlayer rPlayer = plugin.getPlayerManager().getResidencePlayer(player);
        PermissionGroup group = rPlayer.getGroup();
        boolean resadmin = ResAdmin.isResAdmin(player);

        if (ResPerm.select.hasPermission(player) || ResPerm.create.hasPermission(player) && !ResPerm.select.hasSetPermission(player) || group
                .canCreateResidences() && !ResPerm.create.hasSetPermission(player) && !ResPerm.select.hasSetPermission(player) || resadmin) {

            Location loc = block.getLocation();

            @NotNull
            selectorOptions combo = plugin.getComboSelectionManager().getOrCreate(player);
            
            if (combo.isSmartToolSelection()) {
//                if (event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_BLOCK)
                    plugin.getSelectionManager().updateSelection(player, loc, true);
            } else {
                if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                    plugin.getSelectionManager().placeLoc1(player, loc, true);
                    lm.Select_PrimaryPoint.sendMessage(player, lm.General_CoordsTop.getMessage(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
                } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK && Utils.isMainHand(event)) {
                    plugin.getSelectionManager().placeLoc2(player, loc, true);
                    lm.Select_SecondaryPoint.sendMessage(player, lm.General_CoordsBottom.getMessage(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
                }
            }

            event.setCancelled(true);

            if (plugin.getSelectionManager().hasPlacedBoth(player)) {
                plugin.getSelectionManager().showSelectionInfoInActionBar(player);
                plugin.getSelectionManager().updateLocations(player);
            }
        }
    }

    private void handlePlayerUseInfoTool(PlayerInteractEvent event, Player player, Block block) {

        if (event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }
        if (Utils.isContainer(block.getType())) {
            return;
        }
        ClaimedResidence res = ClaimedResidence.getByLoc(block.getLocation());
        if (res != null) {
            plugin.getResidenceManager().printAreaInfo(res, player, false);
        } else {
            lm.Residence_NoResHere.sendMessage(player);
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST) // Do not use (ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {

        Player player = event.getPlayer();
        // disabling event on world
        if (plugin.isDisabledWorldListener(player) || player.hasMetadata("NPC")) {
            return;
        }
        Action action = event.getAction();
        boolean isPhysical = (action == Action.PHYSICAL);
        // Check held item blacklist (also on air clicks)
        if (!isPhysical && event.useItemInHand() != Result.DENY && Utils.isItemBlacklisted(event.getItem(), player)) {
            event.setCancelled(true);
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            if (action == Action.RIGHT_CLICK_AIR && event.useItemInHand() != Result.DENY) {
                CMIMaterial held = CMIMaterial.get(event.getItem());
                if (held.containsCriteria(CMIMC.BOAT) || held.containsCriteria(CMIMC.SPAWNEGG)) {
                    Block fluidTarget = player.getTargetBlockExact(5, org.bukkit.FluidCollisionMode.ALWAYS);
                    if (fluidTarget != null) {
                        if (held.containsCriteria(CMIMC.BOAT)) {
                            if (FlagPermissions.shouldDenyAndNotify(player, fluidTarget.getLocation(), Flags.vehicleplacing, Flags.build)
                                    || FlagPermissions.shouldDenyAndNotify(player, fluidTarget.getRelative(org.bukkit.block.BlockFace.UP).getLocation(), Flags.vehicleplacing, Flags.build)) {
                                event.setCancelled(true);
                            }
                        } else if (shouldDenySpawnEggInteract(player, fluidTarget.getLocation(), held)) {
                            event.setCancelled(true);
                        }
                    }
                }
            }
            return;
        }
        if (isPhysical) {
            handlePlayerPhysicalInteract(event, player, block);
            return;
        }
        if (action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK) {

            if (event.useItemInHand() != Result.DENY) {
                CMIMaterial held = CMIMaterial.get(event.getItem());

                if (held == SelectionManager.getTool()) {
                    handlePlayerUseSelectionTool(event, player, block);

                } else if (held == SelectionManager.getInfoTool()) {
                    handlePlayerUseInfoTool(event, player, block);

                }
                handlePlayerPlaceVehicle(event, player, block, held);
            }
            handlePlayerClickMarketSign(event, player, block);

            handlePlayerInteractBuild(event, player, block);

            handlePlayerClickBlockFlags(event, player, block);
        }
    }

    private void handlePlayerClickBlockFlags(PlayerInteractEvent event, Player player, Block block) {

        if (event.useInteractedBlock() == Result.DENY) {
            return;
        }
        if (ResAdmin.isResAdmin(player)) {
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && (Flags.explode.isGlobalyEnabled() || Flags.destroy.isGlobalyEnabled() || Flags.build.isGlobalyEnabled())
                && willExplodeOnUse(event, block)) {
            if (isExplosionDeniedForBlock(block)) {
                lm.Flag_Deny.sendMessage(player, Flags.explode.isGlobalyEnabled() ? Flags.explode : (Flags.destroy.isGlobalyEnabled() ? Flags.destroy : Flags.build));
                event.setCancelled(true);
                return;
            }
        }
        Material mat = block.getType();
        // Residence assigns Flags internally for Material
        Flags flag = FlagPermissions.getMaterialUseFlagList().get(mat);

        if (flag == null) {
            // Custom right-click block check; Flags.use
            if ((event.getAction() == Action.RIGHT_CLICK_BLOCK && plugin.getConfigManager().getCustomRightClick().contains(mat))
                    // Custom both-click block check; Flags.use
                    || plugin.getConfigManager().getCustomBothClick().contains(mat)) {
                flag = Flags.use;
                // Custom both-click container check; Flags.container
            } else if (plugin.getConfigManager().getCustomContainers().contains(mat)) {
                flag = Flags.container;
            }
        }
        // Not Vanilla Raid
        if (ConfigManager.RaidEnabled && flag != null) {
            ClaimedResidence res = ClaimedResidence.getByLoc(block.getLocation());
            if (res != null && res.getRaid().isUnderRaid()) {
                // Exempt interaction check for Raid attacker
                if (res.getRaid().isAttacker(player)) {
                    return;
                }
                // Restrict defender container use in raid
                if (!ConfigManager.RaidDefenderContainerUsage && flag == Flags.container && res.getRaid().isDefender(player)) {
                    lm.Raid_cantDo.sendMessage(player);
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (flag == null || !flag.isGlobalyEnabled()) {
            return;
        }
        if (flag == Flags.container && CMIMaterial.get(mat).containsCriteria(CMIMC.SHELF)) {
            for (Block shelfBlock : ContainerTransferListener.getConnectedShelfChain(block)) {
                FlagPermissions shelfPerms = FlagPermissions.getPerms(shelfBlock.getLocation(), player);
                boolean shelfUse = shelfPerms.playerHas(player, Flags.use, true);
                if (!shelfPerms.playerHas(player, Flags.container, shelfUse)
                        && !ResPerm.bypass_container.hasPermission(player, 10000L)) {
                    if (event.getAction() == Action.RIGHT_CLICK_BLOCK || canBothClickBlock(mat)) {
                        lm.Flag_Deny.sendMessage(player, Flags.container);
                        event.setCancelled(true);
                    }
                    return;
                }
            }
        }
        // Start AbstractBlockClickFlag Check
        FlagPermissions perms = FlagPermissions.getPerms(block.getLocation(), player);
        boolean hasUse = (flag == Flags.use) || perms.playerHas(player, Flags.use, true);

        if (perms.playerHas(player, flag, hasUse)) {
            if ((flag == Flags.container || mat == Material.LECTERN) && event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                Flags deniedDirectional = ContainerTransferListener.deniedNonGuiBlockTransfer(event, player, block);
                if (deniedDirectional != null) {
                    lm.Flag_Deny.sendMessage(player, deniedDirectional);
                    event.setCancelled(true);
                }
            }
            return;
        }
        switch (flag) {
        case button:
            if (ResPerm.bypass_button.hasPermission(player, 10000L)) {
                return;
            }
            break;
        case container:
            if (ResPerm.bypass_container.hasPermission(player, 10000L)) {
                return;
            }
            break;
        case door:
            if (ResPerm.bypass_door.hasPermission(player, 10000L)) {
                return;
            }
            break;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || canBothClickBlock(mat)) {
            lm.Flag_Deny.sendMessage(player, flag);
            event.setCancelled(true);
        }
        // End AbstractBlockClickFlag Check
    }

    private static boolean isExplosionDeniedAtLocation(Location loc) {
        FlagPermissions perms = FlagPermissions.getPerms(loc);
        boolean fallbackBuild = !Flags.build.isGlobalyEnabled() || perms.has(Flags.build, true);
        boolean allowDestroy = Flags.destroy.isGlobalyEnabled() ? perms.has(Flags.destroy, fallbackBuild) : fallbackBuild;
        boolean allowExplode = Flags.explode.isGlobalyEnabled() ? perms.has(Flags.explode, allowDestroy) : allowDestroy;
        return !allowExplode;
    }

    private boolean isExplosionDeniedForBlock(Block block) {
        if (isExplosionDeniedAtLocation(block.getLocation())) {
            return true;
        }
        if (CMIMaterial.get(block.getType()).containsCriteria(CMIMC.BED) && Version.isCurrentEqualOrHigher(Version.v1_13_0)) {
            if (block.getBlockData() instanceof org.bukkit.block.data.type.Bed) {
                org.bukkit.block.data.type.Bed bed = (org.bukkit.block.data.type.Bed) block.getBlockData();
                Block otherHalf = block.getRelative(bed.getPart() == org.bukkit.block.data.type.Bed.Part.FOOT
                        ? bed.getFacing()
                        : bed.getFacing().getOppositeFace());
                if (isExplosionDeniedAtLocation(otherHalf.getLocation())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasHeldItem(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getAmount() > 0;
    }

    private static boolean isSneakingWithItem(Player player) {
        if (!player.isSneaking())
            return false;
        if (hasHeldItem(player.getInventory().getItemInMainHand()))
            return true;
        return Version.isCurrentEqualOrHigher(Version.v1_9_0) && hasHeldItem(player.getInventory().getItemInOffHand());
    }

    private boolean willExplodeOnUse(PlayerInteractEvent event, Block block) {
        Player player = event.getPlayer();
        if (isSneakingWithItem(player))
            return false;
        CMIMaterial material = CMIMaterial.get(block.getType());
        World world = block.getWorld();
        if (material.containsCriteria(CMIMC.BED)) {
            return !world.isBedWorks();
        }
        if (material != CMIMaterial.RESPAWN_ANCHOR || !Version.isCurrentEqualOrHigher(Version.v1_16_0))
            return false;
        org.bukkit.block.data.type.RespawnAnchor anchor = (org.bukkit.block.data.type.RespawnAnchor) block.getBlockData();
        boolean chargingWithGlowstone = anchor.getCharges() < anchor.getMaximumCharges()
                && (CMIMaterial.get(event.getItem()) == CMIMaterial.GLOWSTONE
                        || (event.getHand() == EquipmentSlot.HAND
                                && CMIMaterial.get(player.getInventory().getItemInOffHand()) == CMIMaterial.GLOWSTONE));
        if (anchor.getCharges() == 0 || chargingWithGlowstone)
            return false;
        return !world.isRespawnAnchorWorks();
    }

    private boolean canBothClickBlock(Material mat) {
        switch (CMIMaterial.get(mat)) {
        case NOTE_BLOCK:
        case DRAGON_EGG:
            return true;
        default:
            return plugin.getConfigManager().getCustomBothClick().contains(mat)
                    || plugin.getConfigManager().getCustomContainers().contains(mat);
        }
    }

    private void handlePlayerPlaceVehicle(PlayerInteractEvent event, Player player, Block block, CMIMaterial held) {

        if (!Flags.vehicleplacing.isGlobalyEnabled()) {
            return;
        }
        if (event.useInteractedBlock() == Result.DENY) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Location loc = null;

        if (held.containsCriteria(CMIMC.BOAT)) {
            loc = block.getRelative(event.getBlockFace()).getLocation();
        } else if (held.containsCriteria(CMIMC.MINECART) && CMIMaterial.get(block.getType()).containsCriteria(CMIMC.RAIL)) {
            loc = block.getLocation();
        }
        if (loc == null) {
            return;
        }
        if (FlagPermissions.shouldDenyAndNotify(player, loc, Flags.vehicleplacing, Flags.build)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {

        Entity entity = event.getRightClicked();
        // disabling event on world
        if (plugin.isDisabledWorldListener(entity)) {
            return;
        }
        Player player = event.getPlayer();
        CMIMaterial held = null;
        Flags mainFlag = null;
        Flags subFlag = null;

        ItemStack item = Utils.getItemInUseHand(event);
        if (item != null) {
            // Check held item blacklist
            if (Utils.isItemBlacklisted(item, player)) {
                event.setCancelled(true);
                return;
            }
            held = CMIMaterial.get(item);
        }
        if (Utils.isCopperGolem(entity)) {
            if (ResidenceListener1_21.shouldDenyInteractCopperGolem(player, entity, item)) {
                event.setCancelled(true);
                return;
            }
        }
        if (held != null && held.containsCriteria(CMIMC.SPAWNEGG)) {
            if (shouldDenySpawnEggInteract(player, entity.getLocation(), held)) {
                event.setCancelled(true);
                return;
            }
        }
        if (Flags.nametag.isGlobalyEnabled() && entity instanceof LivingEntity && held == CMIMaterial.NAME_TAG) {
            mainFlag = Flags.nametag;
            if (Utils.isAnimal(entity)) {
                subFlag = Flags.animalkilling;
            } else if (Utils.isMonster(entity)) {
                subFlag = Flags.mobkilling;
            }

        } else if (Flags.dye.isGlobalyEnabled()
                && (entity instanceof Sheep || entity instanceof org.bukkit.entity.Wolf || entity instanceof org.bukkit.entity.Cat)
                && held != null && held.containsCriteria(CMIMC.DYE)) {
            mainFlag = Flags.dye;
            subFlag = Flags.animalkilling;

        } else if (Flags.brush.isGlobalyEnabled() && held == CMIMaterial.BRUSH
                && net.Zrips.CMILib.Entities.CMIEntityType.get(entity) == net.Zrips.CMILib.Entities.CMIEntityType.ARMADILLO) {
            mainFlag = Flags.brush;
            subFlag = Flags.animalkilling;

        } else if (Flags.animalkilling.isGlobalyEnabled()
                && (((entity instanceof org.bukkit.entity.Cow || entity instanceof org.bukkit.entity.Goat) && held == CMIMaterial.BUCKET)
                        || (entity instanceof org.bukkit.entity.MushroomCow && held == CMIMaterial.BOWL))) {
            mainFlag = Flags.animalkilling;

        } else if (Flags.commandblock.isGlobalyEnabled() && entity instanceof CommandMinecart) {
            mainFlag = Flags.commandblock;

        } else if (Flags.container.isGlobalyEnabled() && Utils.isContainerEntityWithoutGui(entity)) {
            mainFlag = Flags.container;
            subFlag = Flags.use;

        } else if (Flags.leash.isGlobalyEnabled() && entity instanceof LeashHitch) {
            mainFlag = Flags.leash;

        } else if (Flags.trade.isGlobalyEnabled() && Utils.isVillagerOrTrader(entity)) {
            mainFlag = Flags.trade;

        }
        if (mainFlag == null) {
            return;
        }
        if (FlagPermissions.shouldDenyAndNotify(player, entity, mainFlag, subFlag)) {
            event.setCancelled(true);
            return;
        }
        if (mainFlag == Flags.container) {
            int transfer = detectContainerEntityTransfer(entity, item);
            if (transfer != 0) {
                Flags denied = ContainerTransferListener.deniedDirectTransfer(player, entity.getLocation(), transfer, false);
                if (denied != null) {
                    lm.Flag_Deny.sendMessage(player, denied);
                    event.setCancelled(true);
                }
            }
        }
    }

    private static int detectContainerEntityTransfer(Entity entity, ItemStack held) {
        boolean hasHeld = hasHeldItem(held);
        if (entity instanceof org.bukkit.entity.ItemFrame) {
            ItemStack current = ((org.bukkit.entity.ItemFrame) entity).getItem();
            boolean frameEmpty = current == null || current.getType() == Material.AIR || current.getAmount() <= 0;
            return (frameEmpty && hasHeld) ? ContainerTransferListener.PUT : 0;
        }
        if (entity instanceof org.bukkit.entity.minecart.PoweredMinecart) {
            if (!hasHeld)
                return 0;
            CMIMaterial mat = CMIMaterial.get(held.getType());
            return (mat == CMIMaterial.COAL || mat == CMIMaterial.CHARCOAL) ? ContainerTransferListener.PUT : 0;
        }
        if (Version.isCurrentEqualOrHigher(Version.v1_19_0) && entity instanceof org.bukkit.entity.Allay) {
            org.bukkit.inventory.EntityEquipment eq = ((org.bukkit.entity.Allay) entity).getEquipment();
            ItemStack allayHeld = eq == null ? null : eq.getItemInMainHand();
            boolean allayHasItem = hasHeldItem(allayHeld);
            if (!allayHasItem && hasHeld)
                return ContainerTransferListener.PUT;
            if (allayHasItem && !hasHeld)
                return ContainerTransferListener.TAKE;
            return 0;
        }
        return 0;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerUnleashEntity(PlayerUnleashEntityEvent event) {

        Entity entity = event.getEntity();

        if (FlagPermissions.shouldIgnoreCheck(Flags.leash, entity)) {
            return;
        }
        if (FlagPermissions.shouldDenyAndNotify(event.getPlayer(), entity, Flags.leash, null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerShearEntity(PlayerShearEntityEvent event) {

        Player player = event.getPlayer();

        if (FlagPermissions.shouldIgnoreCheck(Flags.shear, player)) {
            return;
        }
        if (FlagPermissions.shouldDenyAndNotify(player, event.getEntity(), Flags.shear, null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityTryRideVehicle(VehicleEnterEvent event) {

        Entity entity = event.getEntered();
        Vehicle vehicle = event.getVehicle();

        if (entity instanceof Player) {
            if (FlagPermissions.shouldIgnoreCheck(Flags.riding, entity)) {
                return;
            }
            if (FlagPermissions.shouldDenyAndNotify((Player) entity, vehicle, Flags.riding, null)) {
                event.setCancelled(true);
            }
        } else {
            if (FlagPermissions.shouldIgnoreCheck(Flags.boarding, entity)) {
                return;
            }
            Player operator = null;
            if (vehicle instanceof io.papermc.paper.entity.Leashable && ((io.papermc.paper.entity.Leashable) vehicle).isLeashed()) {
                try {
                    Entity holder = ((io.papermc.paper.entity.Leashable) vehicle).getLeashHolder();
                    if (holder instanceof Player) {
                        operator = (Player) holder;
                    }
                } catch (IllegalStateException ignored) {
                }
            }
            if (operator == null) {
                for (Entity passenger : Utils.getPassengers(vehicle)) {
                    if (passenger instanceof Player) {
                        operator = (Player) passenger;
                        break;
                    }
                }
            }
            if (operator != null) {
                if (FlagPermissions.shouldDenyAndNotify(operator, entity.getLocation(), Flags.boarding, null)
                        || FlagPermissions.shouldDenyAndNotify(operator, vehicle.getLocation(), Flags.boarding, null)) {
                    event.setCancelled(true);
                }
            } else {
                if (FlagPermissions.has(entity.getLocation(), Flags.boarding, FlagCombo.OnlyFalse)
                        || FlagPermissions.has(vehicle.getLocation(), Flags.boarding, FlagCombo.OnlyFalse)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerOpenVehicleInventory(InventoryOpenEvent event) {

        Player player = (Player) event.getPlayer();

        if (FlagPermissions.shouldIgnoreCheck(Flags.container, player)) {
            return;
        }
        InventoryHolder holder = Utils.getHolderNoSnapshot(event.getInventory());

        if (!(holder instanceof Vehicle)) {
            return;
        }
        if (FlagPermissions.shouldDenyAndNotify(player, (Vehicle) holder, Flags.container, Flags.use)) {
            event.setCancelled(true);
        }
    }

    private boolean isCauldron(Block block) {
        switch (CMIMaterial.get(block.getType())) {
        case CAULDRON:
        case LAVA_CAULDRON:
        case POWDER_SNOW_CAULDRON:
        case WATER_CAULDRON:
            return true;
        default:
            return false;
        }
    }

    private boolean isCauldronBucketType(CMIMaterial mat) {
        switch (mat) {
        case LAVA_BUCKET:
            return Version.isCurrentEqualOrHigher(Version.v1_17_0);
        case POWDER_SNOW_BUCKET:
        case WATER_BUCKET:
            return true;
        default:
            return false;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        // disabling event on world
        if (plugin.isDisabledWorldListener(player)) {
            return;
        }
        if (ResAdmin.isResAdmin(player)) {
            return;
        }
        Block clickBlock = event.getBlockClicked();
        CMIMaterial cmat = CMIMaterial.get(event.getBucket());
        boolean canPlaceInside = false;
        // Can be placed inside blocks only when not sneaking
        if (!player.isSneaking()) {
            // Cauldron uses CauldronLevelChangeEvent for checks on 1.9+
            if (Version.isCurrentEqualOrHigher(Version.v1_9_0) && isCauldron(clickBlock) && isCauldronBucketType(cmat)) {
                return;
            }
            if (Version.isCurrentEqualOrHigher(Version.v1_13_0) && cmat != CMIMaterial.LAVA_BUCKET) {
                canPlaceInside = clickBlock.getBlockData() instanceof org.bukkit.block.data.Waterlogged;
            }
        }
        Location loc = event.getBlock().getLocation();

        ClaimedResidence res = plugin.getResidenceManager().getByLoc(loc);
        if (res != null) {
            if (plugin.getConfigManager().preventRentModify() && plugin.getConfigManager().enabledRentSystem()) {
                if (plugin.getRentManager().isRented(res)) {
                    lm.Rent_ModifyDeny.sendMessage(player);
                    event.setCancelled(true);
                    return;
                }
            }

            if ((res.getPermissions().playerHas(player, Flags.build, FlagCombo.OnlyFalse))
                    && plugin.getConfigManager().getNoPlaceWorlds().contains(loc.getWorld().getName())) {
                if (cmat == CMIMaterial.LAVA_BUCKET || cmat == CMIMaterial.WATER_BUCKET || cmat.containsCriteria(CMIMC.BUCKETANIMAL)) {
                    lm.Flag_Deny.sendMessage(player, Flags.build);
                    event.setCancelled(true);
                    return;
                }
            }
        }

        if (FlagPermissions.shouldDenyAndNotify(player, loc, Flags.build, null)) {
            event.setCancelled(true);
            return;
        }

        int level = plugin.getConfigManager().getPlaceLevel();
        if (res == null && plugin.getConfigManager().isNoLavaPlace() && loc.getBlockY() >= level - 1 && plugin.getConfigManager()
                .getNoPlaceWorlds().contains(loc.getWorld().getName())) {
            if (cmat == CMIMaterial.LAVA_BUCKET) {
                lm.General_CantPlaceLava.sendMessage(player, level);
                event.setCancelled(true);
                return;
            }
        }

        if (res == null && plugin.getConfigManager().isNoWaterPlace() && loc.getBlockY() >= level - 1 && plugin.getConfigManager()
                .getNoPlaceWorlds().contains(loc.getWorld().getName())) {
            if (cmat == CMIMaterial.WATER_BUCKET || cmat.containsCriteria(CMIMC.BUCKETANIMAL)) {
                lm.General_CantPlaceWater.sendMessage(player, level);
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerBucketFill(PlayerBucketFillEvent event) {
        Player player = event.getPlayer();
        // disabling event on world
        if (plugin.isDisabledWorldListener(player)) {
            return;
        }
        if (ResAdmin.isResAdmin(player)) {
            return;
        }
        // Cauldron uses CauldronLevelChangeEvent for checks on 1.9+
        if (Version.isCurrentEqualOrHigher(Version.v1_9_0) && isCauldron(event.getBlockClicked())) {
            return;
        }
        Location loc = event.getBlockClicked().getLocation();

        ClaimedResidence res = ClaimedResidence.getByLoc(loc);
        if (res != null && plugin.getConfigManager().preventRentModify() && plugin.getConfigManager().enabledRentSystem() && plugin.getRentManager().isRented(res)) {
            lm.Rent_ModifyDeny.sendMessage(player);
            event.setCancelled(true);
            return;
        }
        if (FlagPermissions.shouldDenyAndNotify(player, loc, Flags.destroy, Flags.build)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {

        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getPlayer()))
            return;
        Player player = event.getPlayer();

        if (player.hasMetadata("NPC"))
            return;

        Location loc = event.getTo();
        ClaimedResidence resTo = plugin.getResidenceManager().getByLoc(loc);
        ClaimedResidence resFrom = plugin.getResidenceManager().getByLoc(player.getLocation());

        boolean allowed = ResAdmin.isResAdmin(player) || allowedTeleport(player, event, resTo);

        if (!allowed) {
            event.setCancelled(true);
            return;
        }

        if (handleNewLocation(player, loc, false) && !Objects.equals(resFrom, resTo)) {
            ResidenceEvent.callAsync(new ResidenceChangedEvent(resFrom, resTo, player));
        }
    }

    private boolean allowedTeleport(Player player, PlayerTeleportEvent event, ClaimedResidence resTo) {

        if (resTo == null)
            return true;

        TeleportCause cause = event.getCause();
        boolean consumableTeleport = Utils.isChorusTeleport(cause);
        if (cause == TeleportCause.COMMAND
                || cause == TeleportCause.NETHER_PORTAL
                || cause == TeleportCause.END_PORTAL
                || cause == TeleportCause.END_GATEWAY
                || cause == TeleportCause.DISMOUNT
                || cause == TeleportCause.EXIT_BED
                || cause == TeleportCause.PLUGIN
                || consumableTeleport) {
            if (Flags.move.isGlobalyEnabled()
                    && resTo.getPermissions().playerHas(player, Flags.move, FlagCombo.OnlyFalse)
                    && !resTo.isOwner(player)
                    && !ResPerm.bypass_tp.hasPermission(player, 10000L)
                    && !ResPerm.admin_move.hasPermission(player, 10000L)) {
                lm.Residence_MoveDeny.sendMessage(player, resTo.getName());
                return false;
            }
        } else if (cause == TeleportCause.ENDER_PEARL
                && ((Flags.enderpearl.isGlobalyEnabled() && resTo.getPermissions().playerHas(player, Flags.enderpearl, FlagCombo.OnlyFalse))
                        || (Flags.move.isGlobalyEnabled() && resTo.getPermissions().playerHas(player, Flags.move, FlagCombo.OnlyFalse)))) {
            lm.Residence_FlagDeny.sendMessage(player, Flags.enderpearl, resTo.getName());
            return false;
        }
        if ((cause == TeleportCause.PLUGIN || cause == TeleportCause.COMMAND) && plugin.getConfigManager().isBlockAnyTeleportation() && !resTo.isOwner(player)
                && resTo.getPermissions().playerHas(player, Flags.tp, FlagCombo.OnlyFalse) && !ResPerm.admin_tp.hasPermission(player)) {
            lm.General_TeleportDeny.sendMessage(player, resTo.getName());
            return false;
        }

        if (consumableTeleport && !resTo.isOwner(player) && resTo.getPermissions().playerHas(player, Flags.chorustp, FlagCombo.OnlyFalse) && !ResPerm.admin_tp.hasPermission(
                player)) {
            lm.Residence_FlagDeny.sendMessage(player, Flags.chorustp, resTo.getName());
            return false;
        }

        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDeath(final PlayerDeathEvent event) {
        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getEntity()))
            return;
        Player player = event.getEntity();
        if (player == null)
            return;
        if (player.hasMetadata("NPC"))
            return;
        Location loc = player.getLocation();
        ClaimedResidence res = plugin.getResidenceManager().getByLoc(loc);
        if (res == null)
            return;

        if (res.getPermissions().has(Flags.keepinv, false)) {
            event.setKeepInventory(true);
            if (Version.isCurrentEqualOrHigher(Version.v1_14_R1))
                event.getDrops().clear();
        }

        if (res.getPermissions().has(Flags.keepexp, false)) {
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }

        if (res.getPermissions().has(Flags.respawn, false) && Version.isSpigot())
            player.getScheduler().runDelayed(plugin, task -> {
                try {
                    player.spigot().respawn();
                } catch (Throwable e) {
                }
            }, null, 20L);
    }

    public static Location getSafeLocation(Location loc) {

        int curY = loc.getBlockY();

        for (int i = 0; i <= curY; i++) {
            Block block = loc.clone().add(0, -i, 0).getBlock();
            if (!block.isEmpty() && block.getLocation().clone().add(0, 1, 0).getBlock().isEmpty() && block.getLocation().clone().add(0, 2, 0).getBlock().isEmpty())
                return loc.clone().add(0, -i + 1, 0);
        }

        for (int i = 0; i <= loc.getWorld().getMaxHeight() - curY; i++) {
            Block block = loc.clone().add(0, i, 0).getBlock();
            if (!block.isEmpty() && block.getLocation().clone().add(0, 1, 0).getBlock().isEmpty() && block.getLocation().clone().add(0, 2, 0).getBlock().isEmpty())
                return loc.clone().add(0, i + 1, 0);
        }

        return null;
    }

    private Location getFlyTeleportLocation(Player player, ClaimedResidence oldRes) {
        Location loc = getSafeLocation(player.getLocation());

        if (loc != null)
            return loc;

        if (oldRes != null) {
            if (Flags.tp.isGlobalyEnabled() && !oldRes.getPermissions().playerHas(player, Flags.tp, FlagCombo.OnlyFalse) || ResPerm.admin_tp.hasPermission(player, 10000L))
                loc = oldRes.getTeleportLocation(player, false);
        }

        if (loc != null)
            return loc;

        // get defined land location in case no safe landing spot are found
        loc = plugin.getConfigManager().getFlyLandLocation();

        if (loc != null)
            return loc;

        // get main world spawn location in case valid location is not found
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private boolean canFlyWithAnotherPlugin(Player player) {
        return (Bukkit.getPluginManager().isPluginEnabled("CMI") && player.hasPermission("cmi.command.fly"))
                || (Bukkit.getPluginManager().isPluginEnabled("Essentials") && player.hasPermission("essentials.fly"));
    }

    private void fly(Player player, boolean state, ClaimedResidence oldRes) {
        fly(player, state, oldRes, true);
    }

    private void fly(Player player, boolean state, ClaimedResidence oldRes, boolean checkGameMode) {
        List<?> flyState = Arrays.asList(state, oldRes);
        player.setMetadata(ResidenceFlyState, new FixedMetadataValue(plugin, flyState));
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE && checkGameMode)
            return;
        if (ResPerm.bypass_fly.hasSetPermission(player))
            return;
        if (!state) {

            // Lets not disable fly mode if player has access to fly command from another
            // plugin
            if (canFlyWithAnotherPlugin(player))
                return;
            boolean land = player.isFlying();

            player.setFlying(false);
            player.setAllowFlight(false);

            if (land) {
                player.getScheduler().run(plugin, task -> {
                    Location loc = getFlyTeleportLocation(player, oldRes);
                    player.closeInventory();
                    Teleporting.teleport(player, loc);
                }, null);
            }
            player.setFlying(false);
            player.setAllowFlight(false);
        } else {

            player.setAllowFlight(true);
            player.getScheduler().runDelayed(plugin, task -> {
                ClaimedResidence res = plugin.getResidenceManager().getByLoc(player.getLocation());

                if (res == null || !res.getPermissions().playerHas(player, Flags.fly, FlagCombo.OnlyTrue) && player.isOnline()) {
                    if (canFlyWithAnotherPlugin(player))
                        return;

                    player.setFlying(false);
                    player.setAllowFlight(false);
                } else if (res != null && res.getPermissions().playerHas(player, Flags.fly, FlagCombo.OnlyTrue)) {
                    // Secondary set of flight mode in case another plugin overrides previous set
                    player.setAllowFlight(true);
                }
            }, null, 20L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidenceChange(ResidenceChangedEvent event) {

        ClaimedResidence newRes = event.getTo();
        ClaimedResidence oldRes = event.getFrom();

        Player player = event.getPlayer();
        if (player == null)
            return;

        player.getScheduler().run(plugin, task -> {
            checkSpecialFlags(player, newRes, oldRes);
        }, null);
    }

    private void checkSpecialFlags(Player player, ClaimedResidence newRes, ClaimedResidence oldRes) {

        if (player == null || !player.isOnline())
            return;

        if (newRes == null && oldRes != null) {
            if (Flags.night.isGlobalyEnabled() && oldRes.getPermissions().has(Flags.night, FlagCombo.OnlyTrue)
                    || Flags.day.isGlobalyEnabled() && oldRes.getPermissions().has(Flags.day, FlagCombo.OnlyTrue))
                player.resetPlayerTime();

            if (Flags.wspeed1.isGlobalyEnabled() && oldRes.getPermissions().has(Flags.wspeed1, FlagCombo.OnlyTrue) || Flags.wspeed2.isGlobalyEnabled() && oldRes.getPermissions().has(Flags.wspeed2,
                    FlagCombo.OnlyTrue)) {
                player.setWalkSpeed(0.2F);
            }

            if (Flags.sun.isGlobalyEnabled() && oldRes.getPermissions().has(Flags.sun, FlagCombo.OnlyTrue)
                    || Flags.rain.isGlobalyEnabled() && oldRes.getPermissions().has(Flags.rain, FlagCombo.OnlyTrue))
                player.resetPlayerWeather();

            if (Flags.fly.isGlobalyEnabled() && oldRes.getPermissions().playerHas(player, Flags.fly, FlagCombo.OnlyTrue)) {
                fly(player, false, oldRes);
            }

            if (Flags.glow.isGlobalyEnabled() && Version.isCurrentEqualOrHigher(Version.v1_9_R1) && oldRes.getPermissions().has(Flags.glow, FlagCombo.OnlyTrue))
                player.setGlowing(false);

            return;
        }

        if (newRes != null && oldRes != null && !newRes.equals(oldRes)) {
            if (Flags.glow.isGlobalyEnabled() && Version.isCurrentEqualOrHigher(Version.v1_9_R1)) {
                if (newRes.getPermissions().has(Flags.glow, FlagCombo.OnlyTrue))
                    player.setGlowing(true);
                else if (oldRes.getPermissions().has(Flags.glow, FlagCombo.OnlyTrue) && !newRes.getPermissions().has(Flags.glow, FlagCombo.OnlyTrue))
                    player.setGlowing(false);
            }

            if (Flags.fly.isGlobalyEnabled()) {
                if (newRes.getPermissions().playerHas(player, Flags.fly, FlagCombo.OnlyTrue))
                    fly(player, true, oldRes);
                else if (oldRes.getPermissions().playerHas(player, Flags.fly, FlagCombo.OnlyTrue) && !newRes.getPermissions().playerHas(player, Flags.fly, FlagCombo.OnlyTrue))
                    fly(player, false, oldRes);
            }

            boolean updated = false;
            if (Flags.day.isGlobalyEnabled()) {
                if (newRes.getPermissions().has(Flags.day, FlagCombo.OnlyTrue)) {
                    updated = true;
                    player.setPlayerTime(6000L, false);
                } else if (oldRes.getPermissions().has(Flags.day, FlagCombo.OnlyTrue) && !newRes.getPermissions().has(Flags.day, FlagCombo.OnlyTrue)) {
                    player.resetPlayerTime();
                }
            }

            if (Flags.night.isGlobalyEnabled()) {
                if (newRes.getPermissions().has(Flags.night, FlagCombo.OnlyTrue)) {
                    player.setPlayerTime(14000L, false);
                } else if (!updated && oldRes.getPermissions().has(Flags.night, FlagCombo.OnlyTrue) && !newRes.getPermissions().has(Flags.night, FlagCombo.OnlyTrue)) {
                    player.resetPlayerTime();
                }
            }

            if (Flags.wspeed1.isGlobalyEnabled()) {
                if (newRes.getPermissions().has(Flags.wspeed1, FlagCombo.OnlyTrue))
                    player.setWalkSpeed(plugin.getConfigManager().getWalkSpeed1().floatValue());
                else if (oldRes.getPermissions().has(Flags.wspeed1, FlagCombo.OnlyTrue)
                        && !newRes.getPermissions().has(Flags.wspeed1, FlagCombo.OnlyTrue)
                        && !newRes.getPermissions().has(Flags.wspeed2, FlagCombo.OnlyTrue))
                    player.setWalkSpeed(0.2F);
            }

            if (Flags.wspeed2.isGlobalyEnabled()) {
                if (newRes.getPermissions().has(Flags.wspeed2, FlagCombo.OnlyTrue)) {
                    player.setWalkSpeed(plugin.getConfigManager().getWalkSpeed2().floatValue());
                } else if (oldRes.getPermissions().has(Flags.wspeed2, FlagCombo.OnlyTrue)
                        && !newRes.getPermissions().has(Flags.wspeed1, FlagCombo.OnlyTrue)
                        && !newRes.getPermissions().has(Flags.wspeed2, FlagCombo.OnlyTrue))
                    player.setWalkSpeed(0.2F);
            }

            if (Flags.sun.isGlobalyEnabled()) {
                if (newRes.getPermissions().has(Flags.sun, FlagCombo.OnlyTrue)) {
                    player.setPlayerWeather(WeatherType.CLEAR);
                } else if (oldRes.getPermissions().has(Flags.sun, FlagCombo.OnlyTrue) && !newRes.getPermissions().has(Flags.sun, FlagCombo.OnlyTrue))
                    player.resetPlayerWeather();
            }

            if (Flags.rain.isGlobalyEnabled()) {
                if (newRes.getPermissions().has(Flags.rain, FlagCombo.OnlyTrue)) {
                    player.setPlayerWeather(WeatherType.DOWNFALL);
                } else if (oldRes.getPermissions().has(Flags.rain, FlagCombo.OnlyTrue) && !newRes.getPermissions().has(Flags.rain, FlagCombo.OnlyTrue))
                    player.resetPlayerWeather();
            }
            return;
        }

        if (newRes != null && oldRes == null) {

            if (Flags.glow.isGlobalyEnabled() && Version.isCurrentEqualOrHigher(Version.v1_9_R1) && newRes.getPermissions().has(Flags.glow, FlagCombo.OnlyTrue)) {
                player.setGlowing(true);
            }

            if (Flags.fly.isGlobalyEnabled() && newRes.getPermissions().playerHas(player, Flags.fly, FlagCombo.OnlyTrue)) {
                fly(player, true, oldRes);
            }

            if (Flags.day.isGlobalyEnabled() && newRes.getPermissions().has(Flags.day, FlagCombo.OnlyTrue))
                player.setPlayerTime(6000L, false);

            if (Flags.night.isGlobalyEnabled() && newRes.getPermissions().has(Flags.night, FlagCombo.OnlyTrue))
                player.setPlayerTime(14000L, false);

            if (Flags.wspeed1.isGlobalyEnabled() && newRes.getPermissions().has(Flags.wspeed1, FlagCombo.OnlyTrue))
                player.setWalkSpeed(plugin.getConfigManager().getWalkSpeed1().floatValue());

            if (Flags.wspeed2.isGlobalyEnabled() && newRes.getPermissions().has(Flags.wspeed2, FlagCombo.OnlyTrue))
                player.setWalkSpeed(plugin.getConfigManager().getWalkSpeed2().floatValue());

            if (Flags.sun.isGlobalyEnabled() && newRes.getPermissions().has(Flags.sun, FlagCombo.OnlyTrue))
                player.setPlayerWeather(WeatherType.CLEAR);

            if (Flags.rain.isGlobalyEnabled() && newRes.getPermissions().has(Flags.rain, FlagCombo.OnlyTrue))
                player.setPlayerWeather(WeatherType.DOWNFALL);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getPlayer()))
            return;
        Player player = event.getPlayer();
        if (player == null)
            return;

        if (player.hasMetadata("NPC"))
            return;

        Location locfrom = event.getFrom();
        Location locto = event.getTo();
        if (locfrom.getBlockX() == locto.getBlockX() && locfrom.getBlockY() == locto.getBlockY() && locfrom.getBlockZ() == locto.getBlockZ())
            return;

//		long last = playerTempData.get(player).getLastUpdate();
//		if (System.currentTimeMillis() - last < plugin.getConfigManager().getMinMoveUpdateInterval())
//			return;
//
//		playerTempData.get(player).setLastUpdate(System.currentTimeMillis());

        boolean handled = handleNewLocation(player, locto, true);

        if (!handled)
            event.setCancelled(true);

        if (Teleporting.getTeleportDelayMap().isEmpty())
            return;

        if (plugin.getConfigManager().getTeleportDelay() <= 0 || !Teleporting.isUnderTeleportDelay(player.getUniqueId()))
            return;

        Teleporting.cancelTeleportDelay(player.getUniqueId());

        lm.General_TeleportCanceled.sendMessage(player);
        if (plugin.getConfigManager().isTeleportTitleMessage())
            CMITitleMessage.send(player, "", "");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMoveInVehicle(VehicleMoveEvent event) {

        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getVehicle()))
            return;

        List<Entity> ent = Utils.getPassengers(event.getVehicle());

        if (ent.isEmpty())
            return;

        for (Entity one : ent) {

            if (!(one instanceof Player))
                continue;

            Player player = (Player) one;

            if (player == null)
                continue;

            if (player.hasMetadata("NPC"))
                continue;

            Location locfrom = event.getFrom();
            Location locto = event.getTo();
            if (locfrom.getBlockX() == locto.getBlockX() && locfrom.getBlockY() == locto.getBlockY() && locfrom.getBlockZ() == locto.getBlockZ())
                continue;

            long last = playerTempData.get(player).getLastCheck();
            if (System.currentTimeMillis() - last < plugin.getConfigManager().getMinMoveUpdateInterval())
                continue;

            boolean handled = handleNewLocation(player, locto, true);
            if (!handled) {
                Teleporting.teleport(event.getVehicle(), event.getFrom());
            }

            if (Teleporting.getTeleportDelayMap().isEmpty())
                continue;

            if (plugin.getConfigManager().getTeleportDelay() <= 0 || !Teleporting.isUnderTeleportDelay(player.getUniqueId()))
                continue;

            Teleporting.cancelTeleportDelay(player.getUniqueId());
            lm.General_TeleportCanceled.sendMessage(player);
            if (plugin.getConfigManager().isTeleportTitleMessage())
                CMITitleMessage.send(player, "", "");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void PlayerToggleFlightEvent(PlayerToggleFlightEvent event) {

        Player player = event.getPlayer();
        ClaimedResidence res = plugin.getResidenceManager().getByLoc(player.getLocation());

        if (res == null)
            return;

        if (!Flags.nofly.isGlobalyEnabled() || !event.isFlying() || !res.getPermissions().playerHas(player, Flags.nofly, FlagCombo.OnlyTrue) || ResAdmin.isResAdmin(player)
                || res.isOwner(player) || ResPerm.bypass_nofly.hasPermission(player, 10000L))
            return;

        lm.Residence_FlagDeny.sendMessage(player, Flags.nofly, res.getName());
        event.setCancelled(true);
    }

    private boolean checkNoFly(Player player, ClaimedResidence res, Location loc) {

        Location location = player.getLocation().clone();

        int from = location.getBlockY();
        int to = CMIWorld.getMinHeight(loc.getWorld());
        boolean teleported = false;

        for (int i = from; i > to; i--) {
            location.setY(i);
            Block block = location.getBlock();
            if (!isEmptyBlock(block)) {
                break;
            }
        }

        location.add(0, 1, 0);

        int distance = (int) (player.getLocation().getY() - location.getY());

        if (distance > 4) {
            Location lastLoc = player.getLocation().clone();
            player.closeInventory();

            ClaimedResidence current = plugin.getResidenceManager().getByLoc(lastLoc);
            if (current != null && Flags.tp.isGlobalyEnabled() && current.getPermissions().playerHas(player, Flags.tp, FlagCombo.OnlyFalse) && !ResPerm.admin_tp.hasPermission(player, 10000L)) {
                // Should have a 1 tick delay to avoid player teleportation on move event
                player.getScheduler().run(plugin, task -> current.kickFromResidence(player), null);
                teleported = true;
            }

            player.setFlying(false);

            lm.Residence_FlagDeny.sendMessage(player, Flags.nofly, res.getName());

            return teleported;
        }

        lm.Residence_FlagDeny.sendMessage(player, Flags.nofly, res.getName());

        player.closeInventory();
        player.setFlying(false);

        return true;
    }

    private void bounceAnimation(Player player, ClaimedResidence res) {
        if (!VisualizerConfig.isBounceAnimation())
            return;

        Visualizer v = new Visualizer(player);
        v.setErrorAreas(res);
        v.setOnce(true);
        plugin.getSelectionManager().showBounds(player, v);
    }

    private void informOnMoveDeny(Player player, ClaimedResidence res) {

        switch (plugin.getConfigManager().getGeneralMessageType()) {
        case ActionBar:
        case TitleBar:
            FlagPermissions perms = res.getPermissions();
            if (perms.has(Flags.title, FlagCombo.TrueOrNone))
                CMIActionBar.send(player, lm.Residence_MoveDeny.getMessage(res.getName()));
            break;
        case ChatBox:
            lm.Residence_MoveDeny.sendMessage(player, res.getName());
            break;
        default:
            break;
        }
    }

    public boolean handleNewLocation(final Player player, Location loc, boolean move) {

        ClaimedResidence res = plugin.getResidenceManager().getByLoc(loc);

        UUID uuid = player.getUniqueId();

        playerTempData tempData = playerTempData.get(uuid);

        ClaimedResidence resOld = tempData.getCurrentResidence();

        boolean changedResidence = !Objects.equals(resOld, res);

        if (res == null)
            playerPersistentData.get(uuid).setLastOutsideLoc(loc);

        if (!changedResidence) {
            // In case we are inside same residence, we can assume we can move there
            if (res != null)
                tempData.setLastInsideLoc(loc);
            return true;
        }

        if (res == null) {
            tempData.setCurrentResidence(player, res);
            // New ResidenceChangeEvent
            ResidenceEvent.callAsync(new ResidenceChangedEvent(resOld, null, player));
            return true;
        }

        boolean cantMove = Flags.move.isGlobalyEnabled() &&
                res.getPermissions().playerHas(player, Flags.move, FlagCombo.OnlyFalse) &&
                !ResAdmin.isResAdmin(player) &&
                !res.isOwner(player) &&
                !ResPerm.admin_move.hasPermission(player, 10000L);

        if (!cantMove)
            tempData.setCurrentResidence(player, res);

        boolean teleported = false;
        if (!cantMove && Flags.nofly.isGlobalyEnabled() && player.isFlying() && res.getPermissions().playerHas(player, Flags.nofly, FlagCombo.OnlyTrue) && !ResAdmin.isResAdmin(player)
                && !res.isOwner(player) && !ResPerm.bypass_nofly.hasPermission(player, 10000L)) {

            teleported = checkNoFly(player, res, loc);
        }

        if (move && cantMove) {

            if (res.getRaid().isUnderRaid() && (res.getRaid().isAttacker(player.getUniqueId()) || res.getRaid().isDefender(player.getUniqueId())))
                return true;

            Location lastLoc = tempData.getLastValidLocation(player);

            if (lastLoc == null)
                lastLoc = player.getLocation();

            bounceAnimation(player, res);
            ClaimedResidence preRes = plugin.getResidenceManager().getByLoc(lastLoc);

            if (preRes != null && preRes.getPermissions().playerHas(player, Flags.tp, FlagCombo.OnlyFalse) && !ResPerm.admin_tp.hasPermission(player, 10000L)) {
                res.kickFromResidence(player);
                player.closeInventory();
                informOnMoveDeny(player, res);
                return false;
            }

            StuckInfo info = updateStuckTeleport(player, loc);
            player.closeInventory();

            if (player.getVehicle() != null)
                player.leaveVehicle();

            if (info != null && info.getTimesTeleported() > 12) {
                res.kickFromResidence(player);
                return false;
            }

            if (!teleported) {
                Teleporting.teleport(player, lastLoc).thenAccept(success -> {
                    if (!success)
                        res.kickFromResidence(player);
                });
            }

            String name = res.getName();
            Bukkit.getAsyncScheduler().runNow(plugin, task -> lm.Residence_MoveDeny.sendMessage(player, name));

            return false;
        }

        if (!cantMove)
            tempData.setLastInsideLoc(loc);

        if (move) {
            ResidenceEvent.callAsync(new ResidenceChangedEvent(resOld, res, player));
        }

        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidenceChangeMessagePrint(ResidenceChangedEvent event) {

        ClaimedResidence from = event.getFrom();
        ClaimedResidence to = event.getTo();
        String message = null;

        ClaimedResidence res = from == null ? to : from;

        if (from == null && to != null) {
            message = to.getEnterMessage();
            res = to;
        }

        if (from != null && to == null) {
            message = from.getLeaveMessage();
            res = from;
        }

        if (from != null && to != null) {
            message = to.getEnterMessage();
            res = to;
        }

        Player player = event.getPlayer();
        if (player.hasMetadata("NPC"))
            return;
        if (message != null && !message.isEmpty()) {

            message = lm.Limits_EnterLeavePrefix.getMessage() + message;

            Long time = playerTempData.get(player).getLastEnterLeaveInformTime();
            if (time == null || time + 100L < System.currentTimeMillis()) {

                if (res.getPermissions().has(Flags.title, FlagCombo.TrueOrNone)) {
                    switch (plugin.getConfigManager().getGeneralMessageType()) {
                    case ActionBar:
                        CMIActionBar.send(player, (new StringBuilder()).append(CMIChatColor.YELLOW).append(insertMessages(player, res, message))
                                .toString());
                        break;
                    case ChatBox:
                        lm.showMessage(player, CMIChatColor.YELLOW + this.insertMessages(player, res, message));
                        break;
                    case TitleBar:
                        String title = CMIChatColor.YELLOW + insertMessages(player, res, message);
                        String subtitle = "";
                        if (title.contains("\\n")) {
                            subtitle = CMIChatColor.YELLOW + title.split("\\\\n", 2)[1];
                            title = title.split("\\\\n", 2)[0];
                        }
                        CMITitleMessage.send(player, title, subtitle);
                        break;
                    default:
                        break;
                    }
                }
                playerTempData.get(player).setLastEnterLeaveInformTime(System.currentTimeMillis());
            }
        }

        if (to != null && VisualizerConfig.isEnterAnimation() && to.isTopArea() && (from == null || from.getTopParent() != to) && res.getPermissions().has(Flags.visualizer, FlagCombo.TrueOrNone))
            to.showBounds(player, true);

        if (from == null || res == null) {
            return;
        }

        if (res != from.getParent() && plugin.getConfigManager().isExtraEnterMessage() && !res.isOwner(player) && (plugin.getRentManager().isForRent(from) || plugin
                .getTransactionManager().isForSale(from))) {
            if (plugin.getRentManager().isForRent(from) && !plugin.getRentManager().isRented(from)) {
                RentableLand rentable = plugin.getRentManager().getRentableLand(from);
                if (rentable != null)
                    CMIActionBar.send(player, lm.Residence_CanBeRented.getMessage(from.getName(), rentable.cost, rentable.days));
            } else if (plugin.getTransactionManager().isForSale(from) && !res.isOwner(player)) {
                int sale = plugin.getTransactionManager().getSaleAmount(from);
                CMIActionBar.send(player, lm.Residence_CanBeBought.getMessage(from.getName(), sale));
            }
        }
    }

    private StuckInfo updateStuckTeleport(Player player, Location loc) {

        if (loc.getY() >= player.getLocation().getY())
            return null;

        StuckInfo info = playerTempData.get(player.getUniqueId()).getStuckTeleportCounter();
        info.updateLastTp();

        return info;
    }

    public String insertMessages(Player player, ClaimedResidence res, String message) {
        try {
            message = message.replace("%playerDisplay", player.getDisplayName());
            message = message.replace("%player", player.getName());
            message = message.replace("%owner", res.getPermissions().getOwner());
            message = message.replace("%residence", res.getName());
            message = message.replace("%zone", res.getResidenceName());
        } catch (Exception ex) {
            return "";
        }
        return message;
    }

    @SuppressWarnings("deprecation")
    public void doHeals() {
        if (!Flags.healing.isGlobalyEnabled())
            return;
        try {
            for (Player player : Bukkit.getServer().getOnlinePlayers()) {

                ClaimedResidence res = getCurrentResidence(player.getUniqueId());

                if (res == null)
                    continue;

                if (!res.getPermissions().has(Flags.healing, false))
                    continue;

                Damageable damage = player;
                double health = damage.getHealth();
                if (health < damage.getMaxHealth() && !player.isDead()) {
                    player.setHealth(health + 1);
                }
            }
        } catch (Exception ex) {
        }
    }

    public void feed() {
        if (!Flags.feed.isGlobalyEnabled())
            return;
        try {
            for (Player player : Bukkit.getServer().getOnlinePlayers()) {

                ClaimedResidence res = getCurrentResidence(player.getUniqueId());

                if (res == null)
                    continue;

                if (!res.getPermissions().has(Flags.feed, false))
                    continue;

                int food = player.getFoodLevel();
                if (food < 20 && !player.isDead()) {
                    player.setFoodLevel(food + 1);
                }
            }
        } catch (Exception ex) {
        }
    }

    public void badEffects() {
        if (!Flags.safezone.isGlobalyEnabled())
            return;
        try {
            for (Player player : Bukkit.getServer().getOnlinePlayers()) {
                ClaimedResidence res = getCurrentResidence(player.getUniqueId());
                if (res == null)
                    continue;
                if (!res.getPermissions().has(Flags.safezone, FlagCombo.OnlyTrue))
                    continue;
                if (player.getActivePotionEffects().isEmpty())
                    continue;
                for (PotionEffect one : player.getActivePotionEffects()) {
                    PotionEffectType type = one.getType();
                    if (PotionUtils.isHarmfulEffect(type)) {
                        player.removePotionEffect(type);
                    }
                }
            }
        } catch (Exception ex) {
        }
    }

    public void DespawnMobs() {
        if (!Flags.nomobs.isGlobalyEnabled())
            return;

        try {

            Set<ClaimedResidence> residences = new HashSet<ClaimedResidence>();

            for (Player player : Bukkit.getServer().getOnlinePlayers()) {
                ClaimedResidence res = getCurrentResidence(player.getUniqueId());
                if (res == null)
                    continue;
                if (!res.getPermissions().has(Flags.nomobs, false)) {
                    for (ClaimedResidence sub : res.getSubzonesMap().values()) {
                        if (!sub.getPermissions().has(Flags.nomobs, false)) {
                            continue;
                        }
                        residences.add(sub);
                    }
                    continue;
                }
                residences.add(res);
            }

            int chunkRadius = 3;
            int range = 3 * 16;
            for (ClaimedResidence res : residences) {
                Set<Entity> entities = new HashSet<Entity>();

                World world = Bukkit.getWorld(res.getWorld());

                if (world == null)
                    continue;

                if (Version.isCurrentEqualOrHigher(Version.v1_13_R1)) {
                    ResidenceListener1_13.checkBoundingBox(res, range, world);
                    continue;
                }

                for (CuboidArea area : res.getAreaMap().values()) {
                    for (ChunkRef chunk : area.getChunks()) {

                        // Checking if chunk is near a player.
                        // In case residence is extremely big it will check all chunks which can cause
                        // performance issues
                        boolean near = false;
                        for (Player player : res.getPlayersInResidence()) {
                            int x = player.getLocation().getChunk().getX();
                            int z = player.getLocation().getChunk().getZ();

                            if (CMINumber.abs(x - chunk.getX()) > chunkRadius)
                                continue;

                            if (CMINumber.abs(z - chunk.getZ()) > chunkRadius)
                                continue;
                            near = true;
                            break;
                        }
                        if (!near)
                            continue;
                        entities.addAll(Arrays.asList(world.getChunkAt(chunk.getX(), chunk.getZ()).getEntities()));
                    }
                }

                processEntities(entities, res);
            }
        } catch (Exception ex) {
        }
    }

    public static void processEntities(Set<Entity> entities, ClaimedResidence res) {
        for (Entity ent : entities) {
            if (!Utils.isMonster(ent)) {
                continue;
            }
            if (Residence.getInstance().getConfigManager().getAutoMobRemovalIgnoreNamedMobs()) {
                String entName = ent.getCustomName();
                if (entName != null && !entName.isEmpty()) {
                    continue;
                }
            }
            Location loc = ent.getLocation();
            if (!res.containsLoc(loc)) {
                continue;
            }
            ClaimedResidence subRes = res.getSubzoneByLoc(loc);
            // Skip only if the subzone explicitly sets noMobs: false;
            // otherwise, follow the parent residence's setting
            if (subRes != null && subRes.getPermissions().has(Flags.nomobs, FlagCombo.OnlyFalse)) {
                continue;
            }
            ent.remove();
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode newGameMode = event.getNewGameMode();
        if (newGameMode == GameMode.SURVIVAL || newGameMode == GameMode.ADVENTURE) {
            List<MetadataValue> metaList = player.getMetadata(ResidenceFlyState);
            for (MetadataValue meta : metaList) {
                if (meta.getOwningPlugin().equals(plugin)) {
                    List<?> flyState = (List) meta.value();
                    fly(player, (boolean) flyState.get(0), (ClaimedResidence) flyState.get(1), false);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        // disabling event on world
        if (plugin.isDisabledWorldListener(event.getPlayer()))
            return;
        String pname = event.getPlayer().getName();
        if (!plugin.getConfigManager().chatEnabled() || playerPersistentData.get(event.getPlayer()).isChatEnabled())
            return;

        ChatChannel channel = plugin.getChatManager().getPlayerChannel(event.getPlayer().getUniqueId());
        if (channel != null) {
            channel.chat(pname, event.getMessage());
        }
        event.setCancelled(true);
    }

    @Deprecated
    public void tooglePlayerResidenceChat(Player player, String residence) {
        ChatManager.tooglePlayerResidenceChat(player, residence);
    }

    @Deprecated
    public void removePlayerResidenceChat(String pname) {
        ChatManager.removePlayerResidenceChat(Bukkit.getPlayer(pname));
    }

    @Deprecated
    public void removePlayerResidenceChat(Player player) {
        ChatManager.removePlayerResidenceChat(player);
    }

    @Deprecated
    public void removePlayerResidenceChat(UUID uuid) {
        ChatManager.removePlayerResidenceChat(uuid);
    }

    public ClaimedResidence getCurrentResidence(UUID uuid) {
        return playerTempData.getCurrentResidence(uuid);
    }
}
