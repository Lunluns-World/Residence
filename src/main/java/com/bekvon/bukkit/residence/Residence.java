package com.bekvon.bukkit.residence;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.bekvon.bukkit.residence.Placeholders.Placeholder;
import com.bekvon.bukkit.residence.Placeholders.PlaceholderAPIHook;
import com.bekvon.bukkit.residence.api.ChatInterface;
import com.bekvon.bukkit.residence.api.MarketBuyInterface;
import com.bekvon.bukkit.residence.api.MarketRentInterface;
import com.bekvon.bukkit.residence.api.ResidenceApi;
import com.bekvon.bukkit.residence.api.ResidenceInterface;
import com.bekvon.bukkit.residence.api.ResidencePlayerInterface;
import com.bekvon.bukkit.residence.bigDoors.BigDoorsManager;
import com.bekvon.bukkit.residence.chat.ChatManager;
import com.bekvon.bukkit.residence.commands.padd;
import com.bekvon.bukkit.residence.containers.Flags;
import com.bekvon.bukkit.residence.containers.MinimizeFlags;
import com.bekvon.bukkit.residence.containers.MinimizeMessages;
import com.bekvon.bukkit.residence.containers.ResAdmin;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.economy.BlackHoleEconomy;
import com.bekvon.bukkit.residence.economy.CMIEconomy;
import com.bekvon.bukkit.residence.economy.EconomyInterface;
import com.bekvon.bukkit.residence.economy.EssentialsEcoAdapter;
import com.bekvon.bukkit.residence.economy.TransactionManager;
import com.bekvon.bukkit.residence.economy.rent.RentManager;
import com.bekvon.bukkit.residence.crossserver.CrossServerListener;
import com.bekvon.bukkit.residence.crossserver.CrossServerManager;
import com.bekvon.bukkit.residence.crossserver.RedisSyncBus;
import com.bekvon.bukkit.residence.auction.AuctionManager;
import com.bekvon.bukkit.residence.auction.AuctionProtectionListener;
import com.bekvon.bukkit.residence.gui.AuctionGui;
import com.bekvon.bukkit.residence.gui.FlagUtil;
import com.bekvon.bukkit.residence.gui.MarketGui;
import com.bekvon.bukkit.residence.gui.ResidenceGuiMetaManager;
import com.bekvon.bukkit.residence.gui.ResidenceListGui;
import com.bekvon.bukkit.residence.utils.PlayerActivityImporter;
import com.bekvon.bukkit.residence.storage.DatabaseStorageManager;
import com.bekvon.bukkit.residence.storage.StorageType;
import com.bekvon.bukkit.residence.itemlist.WorldItemManager;
import com.bekvon.bukkit.residence.listeners.ContainerTransferListener;
import com.bekvon.bukkit.residence.listeners.CrackShotListener;
import com.bekvon.bukkit.residence.listeners.ResidenceBlockListener;
import com.bekvon.bukkit.residence.listeners.ResidenceEntityListener;
import com.bekvon.bukkit.residence.listeners.ResidenceLWCListener;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_08;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_09;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_10;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_12;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_13;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_14;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_15;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_16_5_Paper;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_17;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_19;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_20;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_21;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_21_8_Paper;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_21_8_Spigot;
import com.bekvon.bukkit.residence.listeners.ResidenceListener1_21_9_Paper;
import com.bekvon.bukkit.residence.listeners.ResidenceListener26_1_2_Paper;
import com.bekvon.bukkit.residence.listeners.ResidenceListener26_2;
import com.bekvon.bukkit.residence.listeners.ResidenceListener26_2_Paper;
import com.bekvon.bukkit.residence.listeners.ResidenceListener26_3_Paper;
import com.bekvon.bukkit.residence.listeners.ResidencePlayerListener;
import com.bekvon.bukkit.residence.listenersCache.DenyMessageCache_Legacy;
import com.bekvon.bukkit.residence.permissions.PermissionManager;
import com.bekvon.bukkit.residence.persistance.YMLSaveHelper;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.LeaseManager;
import com.bekvon.bukkit.residence.protection.PermissionListManager;
import com.bekvon.bukkit.residence.protection.PlayerManager;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import com.bekvon.bukkit.residence.protection.WorldFlagManager;
import com.bekvon.bukkit.residence.raid.ResidenceRaidListener;
import com.bekvon.bukkit.residence.selection.KingdomsUtil;
import com.bekvon.bukkit.residence.selection.ComboSelection;
import com.bekvon.bukkit.residence.selection.Schematics7Manager;
import com.bekvon.bukkit.residence.selection.SelectionManager;
import com.bekvon.bukkit.residence.selection.WESchematicManager;
import com.bekvon.bukkit.residence.selection.WorldEdit7SelectionManager;
import com.bekvon.bukkit.residence.selection.WorldGuard7Util;
import com.bekvon.bukkit.residence.selection.WorldGuardInterface;
import com.bekvon.bukkit.residence.shopStuff.ShopListener;
import com.bekvon.bukkit.residence.shopStuff.ShopSignUtil;
import com.bekvon.bukkit.residence.signsStuff.SignUtil;
import com.bekvon.bukkit.residence.slimeFun.SlimefunManager;
import com.bekvon.bukkit.residence.text.Language;
import com.bekvon.bukkit.residence.text.help.HelpEntry;
import com.bekvon.bukkit.residence.text.help.InformationPager;
import com.bekvon.bukkit.residence.utils.FileCleanUp;
import com.bekvon.bukkit.residence.utils.RandomTp;
import com.bekvon.bukkit.residence.utils.SafeLocationCache;
import com.bekvon.bukkit.residence.utils.Sorting;
import com.bekvon.bukkit.residence.utils.TabComplete;
import com.bekvon.bukkit.residence.vaultinterface.ResidenceVaultAdapter;
import com.bekvon.bukkit.residence.webmap.WebMapManager;
import com.earth2me.essentials.Essentials;
import com.residence.mcstats.Metrics;
import com.residence.zip.ZipLibrary;

import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.Items.CMIMaterial;
import net.Zrips.CMILib.Logs.CMIDebug;
import net.Zrips.CMILib.Util.CMIVersionChecker;
import net.Zrips.CMILib.Version.Version;
import net.Zrips.CMILib.Version.Schedulers.CMIScheduler;
import net.Zrips.CMILib.Version.Schedulers.CMITask;

/**
 * 
 * @author Gary Smoak - bekvon
 * 
 */

public class Residence extends JavaPlugin {

    private static Residence instance;

    private boolean fullyLoaded = false;

    private String ResidenceVersion;
    private List<String> authlist;
    private ResidenceManager rmanager;
    private SelectionManager smanager;
    private PermissionManager gmanager;
    private ConfigManager configManager;

    private SignUtil signmanager;

    protected ResidencePlayerListener plistener;

    private ResidenceCommandListener commandManager;

    private TransactionManager tmanager;
    private PermissionListManager pmanager;
    private LeaseManager leasemanager;
    private WorldItemManager imanager;
    private WorldFlagManager wmanager;
    private RentManager rentmanager;
    private ChatManager chatmanager;
    private Server server;
    private LocaleManager localeManager;
    private Language newLanguageManager;
    private PlayerManager PlayerManager;
    private FlagUtil FlagUtilManager;
    private ShopSignUtil ShopSignUtilManager;
//    private TownManager townManager;
    private RandomTp RandomTpManager;

    private WebMapManager webMapManager;

    private Sorting SortingManager;
    private ComboSelection ComboSelectionManager;
    private WESchematicManager SchematicManager;
    private InformationPager InformationPagerManager;
    private WorldGuardInterface worldGuardUtil;
    private int wepVersion = 6;
    private KingdomsUtil kingdomsUtil;

    private CommandFiller cmdFiller;

    private ZipLibrary zip;

    private boolean firstenable = true;
    private EconomyInterface economy;
    public File dataFolder;
    private CMITask leaseBukkitId = null;
    private CMITask rentBukkitId = null;
    private CMITask healBukkitId = null;
    private CMITask feedBukkitId = null;
    private CMITask effectRemoveBukkitId = null;
    private CMITask despawnMobsBukkitId = null;
    private CMITask autosaveBukkitId = null;

    private boolean SlimeFun = false;
    private boolean BigDoors = false;
    private boolean lwc = false;
    Metrics metrics = null;

    protected boolean initsuccess = false;
    public Map<UUID, ClaimedResidence> deleteConfirm = new HashMap<UUID, ClaimedResidence>();
    public Map<UUID, ClaimedResidence> unrentConfirm = new HashMap<UUID, ClaimedResidence>();
    public Map<UUID, Long> rtMap = new HashMap<UUID, Long>();
    public Map<UUID, SafeLocationCache> teleportMap = new HashMap<UUID, SafeLocationCache>();

    private Plugin wep = null;
    private Plugin wg = null;
    private CMIMaterial wepid;

    private UUID ServerLandUUID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private String ServerLandName = "Server_Land";

    private Placeholder Placeholder;
    private boolean PlaceholderAPIEnabled = false;

    public Map<UUID, SafeLocationCache> getTeleportMap() {
        return teleportMap;
    }

    public Map<UUID, Long> getRandomTeleportMap() {
        return rtMap;
    }

    // API
    private ResidenceApi API = new ResidenceApi();
    private MarketBuyInterface MarketBuyAPI = null;
    private MarketRentInterface MarketRentAPI = null;
    private ResidencePlayerInterface PlayerAPI = null;
    private ResidenceInterface ResidenceAPI = null;
    private ChatInterface ChatAPI = null;

    private DatabaseStorageManager databaseStorageManager = null;
    private String storageServerName;
    private RedisSyncBus redisSyncBus = null;
    private CrossServerManager crossServerManager = null;
    private ResidenceGuiMetaManager guiMetaManager = null;
    private com.bekvon.bukkit.residence.storage.PlayerLimitManager playerLimitManager = null;
    private com.bekvon.bukkit.residence.storage.CleanupReclaimManager cleanupReclaimManager = null;
    private AuctionManager auctionManager = null;
    private com.bekvon.bukkit.residence.audit.AuditManager auditManager = null;
    private ResidenceListGui residenceListGui = null;
    private MarketGui marketGui = null;
    private AuctionGui auctionGui = null;
    private PlayerActivityImporter playerActivityImporter = null;

    public DatabaseStorageManager getDatabaseStorageManager() {
        return databaseStorageManager;
    }

    public void setDatabaseStorageManager(DatabaseStorageManager databaseStorageManager) {
        this.databaseStorageManager = databaseStorageManager;
    }

    public RedisSyncBus getRedisSyncBus() {
        return redisSyncBus;
    }

    public void initRedisSyncBus() {
        if (redisSyncBus != null) {
            redisSyncBus.stop();
        }
        ConfigManager cfg = getConfigManager();
        redisSyncBus = new RedisSyncBus(
                getLogger(),
                cfg.isRedisEnabled(),
                cfg.getRedisHost(),
                cfg.getRedisPort(),
                cfg.getRedisPassword(),
                cfg.getRedisDatabase(),
                cfg.getRedisChannelPrefix(),
                cfg.getRedisTimeoutMs(),
                getStorageServerName());
        redisSyncBus.start();
    }

    public AuctionManager getAuctionManager() {
        if (auctionManager == null)
            auctionManager = new AuctionManager(this);
        return auctionManager;
    }

    public com.bekvon.bukkit.residence.audit.AuditManager getAuditManager() {
        if (auditManager == null)
            auditManager = new com.bekvon.bukkit.residence.audit.AuditManager(this);
        return auditManager;
    }

    /**
     * 供外部整合插件（例如 Compat-Residence）查詢領地是否處於進行中拍賣凍結狀態。
     */
    public boolean isResidenceInAuction(ClaimedResidence res) {
        return auctionManager != null && auctionManager.isResidenceInAuction(res);
    }

    public AuctionGui getAuctionGui() {
        if (auctionGui == null)
            auctionGui = new AuctionGui(this);
        return auctionGui;
    }

    public PlayerActivityImporter getPlayerActivityImporter() {
        if (playerActivityImporter == null)
            playerActivityImporter = new PlayerActivityImporter(this);
        return playerActivityImporter;
    }

    public CrossServerManager getCrossServerManager() {
        if (crossServerManager == null)
            crossServerManager = new CrossServerManager(this);
        return crossServerManager;
    }

    public ResidenceGuiMetaManager getGuiMetaManager() {
        if (guiMetaManager == null)
            guiMetaManager = new ResidenceGuiMetaManager(this);
        return guiMetaManager;
    }

    public ResidenceListGui getResidenceListGui() {
        if (residenceListGui == null)
            residenceListGui = new ResidenceListGui(this);
        return residenceListGui;
    }

    public MarketGui getMarketGui() {
        if (marketGui == null)
            marketGui = new MarketGui(this);
        return marketGui;
    }

    public ResidencePlayerInterface getPlayerManagerAPI() {
        if (PlayerAPI == null)
            PlayerAPI = PlayerManager;
        return PlayerAPI;
    }

    public ResidenceInterface getResidenceManagerAPI() {
        if (ResidenceAPI == null)
            ResidenceAPI = getResidenceManager();
        return ResidenceAPI;
    }

    public Placeholder getPlaceholderAPIManager() {
        if (Placeholder == null)
            Placeholder = new Placeholder(this);
        return Placeholder;
    }

    public boolean isPlaceholderAPIEnabled() {
        return PlaceholderAPIEnabled;
    }

    public MarketRentInterface getMarketRentManagerAPI() {
        if (MarketRentAPI == null)
            MarketRentAPI = getRentManager();
        return MarketRentAPI;
    }

    public MarketBuyInterface getMarketBuyManagerAPI() {
        if (MarketBuyAPI == null)
            MarketBuyAPI = getTransactionManager();
        return MarketBuyAPI;

    }

    public ChatInterface getResidenceChatAPI() {
        if (ChatAPI == null)
            ChatAPI = getChatManager();
        return ChatAPI;
    }

    public ResidenceCommandListener getCommandManager() {
        if (commandManager == null)
            commandManager = new ResidenceCommandListener(this);
        return commandManager;
    }

    public ResidenceApi getAPI() {
        return API;
    }
    // API end

    private Runnable doHeals = () -> getPlayerListener().doHeals();

    private Runnable doFeed = () -> getPlayerListener().feed();

    private Runnable removeBadEffects = () -> getPlayerListener().badEffects();

    private Runnable DespawnMobs = () -> getPlayerListener().DespawnMobs();

    private Runnable rentExpire = () -> {
        getRentManager().checkCurrentRents();
        if (getConfigManager().showIntervalMessages()) {
            lm.consoleMessage("- Rent Expirations checked!");
        }
    };

    private Runnable leaseExpire = () -> {
        getLeaseManager().doExpirations();
        if (getConfigManager().showIntervalMessages()) {
            lm.consoleMessage("- Lease Expirations checked!");
        }
    };

    private Runnable autoSave = () -> {
        if (!initsuccess)
            return;

        CMIScheduler.runTaskAsynchronously(this, () -> {
            try {
                saveYml();
            } catch (Throwable e) {
                Logger.getLogger("Minecraft").log(Level.SEVERE, getPrefix() + " SEVERE SAVE ERROR", e);
                e.printStackTrace();
            }
        });
    };

    public void reloadPlugin() {
        this.onDisable();
        this.reloadConfig();
        this.onEnable();
    }

    @Override
    public void onDisable() {
        if (autosaveBukkitId != null)
            autosaveBukkitId.cancel();
        if (healBukkitId != null)
            healBukkitId.cancel();
        if (feedBukkitId != null)
            feedBukkitId.cancel();
        if (effectRemoveBukkitId != null)
            effectRemoveBukkitId.cancel();
        if (despawnMobsBukkitId != null)
            despawnMobsBukkitId.cancel();

        this.getPermissionManager().stopCacheClearScheduler();

        this.getSelectionManager().onDisable();

        this.getShopSignUtilManager().forceSaveIfPending();

        if (this.metrics != null)
            metrics.shutdown();

        if (getConfigManager().useLeases() && leaseBukkitId != null) {
            leaseBukkitId.cancel();
        }
        if (getConfigManager().enabledRentSystem() && rentBukkitId != null) {
            rentBukkitId.cancel();
        }

        if (auctionManager != null) {
            auctionManager.stop();
        }
        if (auditManager != null) {
            auditManager.stop();
        }
        if (crossServerManager != null) {
            crossServerManager.flushOnlinePlayerActivityNow();
            crossServerManager.stop();
        }
        if (redisSyncBus != null) {
            redisSyncBus.stop();
        }

        if (initsuccess) {
            try {
                saveYml();
                if (guiMetaManager != null && (databaseStorageManager == null || !databaseStorageManager.isInitialized())) {
                    guiMetaManager.saveYaml();
                }
                if (playerLimitManager != null && (databaseStorageManager == null || !databaseStorageManager.isInitialized())) {
                    playerLimitManager.saveYaml();
                }
                if (cleanupReclaimManager != null && (databaseStorageManager == null || !databaseStorageManager.isInitialized())) {
                    cleanupReclaimManager.saveYaml();
                }
                if (zip != null)
                    zip.backup();
            } catch (Exception ex) {
                lm.consoleMessage("SEVERE SAVE ERROR");
                ex.printStackTrace();
            } finally {
                if (databaseStorageManager != null && databaseStorageManager.isInitialized()) {
                    try {
                        databaseStorageManager.clearResidenceReservations(getStorageServerName());
                    } catch (Exception ex) {
                        getLogger().log(Level.WARNING, "Failed to clear quota reservations on disable", ex);
                    }
                }
            }

            getPlayerManager().onPluginStop();

            lm.consoleMessage("Disabled!");
        }
        fullyLoaded = false;
    }

    @Override
    public void onEnable() {
        try {
            instance = this;

            initsuccess = false;
            server = this.getServer();
            dataFolder = this.getDataFolder();

            ResidenceVersion = this.getDescription().getVersion();
            authlist = this.getDescription().getAuthors();

            getCommandFiller().fillCommands();

            if (!dataFolder.isDirectory())
                dataFolder.mkdirs();

            if (!new File(dataFolder, "flags.yml").isFile()) {
                this.writeDefaultFromJar("flags.yml");
            }
            if (!new File(dataFolder, "groups.yml").isFile()) {
                this.writeDefaultFromJar("groups.yml");
            }

            this.getCommand("res").setExecutor(getCommandManager());
            this.getCommand("resadmin").setExecutor(getCommandManager());
            this.getCommand("residence").setExecutor(getCommandManager());

            this.getCommand("rc").setExecutor(getCommandManager());
            this.getCommand("resreload").setExecutor(getCommandManager());
            this.getCommand("resload").setExecutor(getCommandManager());

            TabComplete tab = new TabComplete();
            this.getCommand("res").setTabCompleter(tab);
            this.getCommand("resadmin").setTabCompleter(tab);
            this.getCommand("residence").setTabCompleter(tab);

            String multiworld = getConfigManager().getMultiworldPlugin();
            if (multiworld != null) {
                Plugin plugin = server.getPluginManager().getPlugin(multiworld);
                if (plugin != null && !plugin.isEnabled()) {
                    lm.consoleMessage("- Enabling multiworld plugin: " + multiworld);
                    server.getPluginManager().enablePlugin(plugin);
                }
            }

            getPlayerManager().load();

            getConfigManager().UpdateFlagFile();

            getFlagUtilManager().load();

            this.getPermissionManager().startCacheClearScheduler();

            getItemManager().load();
            getWorldFlags().load();

//	    townManager = new TownManager(this);

            InformationPagerManager = new InformationPager(this);

            zip = new ZipLibrary(this);

            Plugin lwcp = Bukkit.getPluginManager().getPlugin("LWC");
            try {
                if (lwcp != null) {
                    try {
                        ResidenceLWCListener.register(this);
                        lm.consoleMessage("LWC hooked.");
                        lwc = true;
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }

            SlimeFun = Bukkit.getPluginManager().getPlugin("Slimefun") != null;

            if (SlimeFun) {
                try {
                    SlimefunManager.register(this);
                } catch (Throwable e) {
                    SlimeFun = false;
                    e.printStackTrace();
                }
            }

            BigDoors = Bukkit.getPluginManager().getPlugin("BigDoors") != null;

            if (BigDoors) {
                try {
                    BigDoorsManager.register(this);
                } catch (Throwable e) {
                    BigDoors = false;
                    e.printStackTrace();
                }
            }

            this.getConfigManager().copyOverTranslations();

            LocaleManager.parseHelpEntries();

            economy = null;

            String vaultEconomyName = null;
            if (this.getPermissionManager().getPermissionsPlugin() instanceof ResidenceVaultAdapter) {
                ResidenceVaultAdapter vault = (ResidenceVaultAdapter) this.getPermissionManager().getPermissionsPlugin();
                if (vault.economyOK()) {
                }
            }
            Plugin p = getServer().getPluginManager().getPlugin("Vault");
            if (p != null) {
                ResidenceVaultAdapter vault = new ResidenceVaultAdapter(getServer());
                if (vault.economyOK()) {
                    vaultEconomyName = vault.getEconomyName();
                }
            }

            if (this.getConfig().getBoolean("Global.EnableEconomy", false)) {
                lm.consoleMessage("Scanning for economy systems...");
                switch (this.getConfigManager().getEconomyType()) {
                case CMIEconomy:
                    this.loadCMIEconomy();
                    break;
                case Essentials:
                    this.loadEssentialsEconomy();
                    break;
                case Auto:
                    if (economy == null && (vaultEconomyName == null || vaultEconomyName.equals("CMIEconomy"))) {
                        this.loadCMIEconomy();
                    }

                    if (economy == null && (vaultEconomyName == null || vaultEconomyName.equals("EssentialsX Economy") || vaultEconomyName.equals("EssentialsEconomy"))) {
                        this.loadEssentialsEconomy();
                    }

                    if (this.getPermissionManager().getPermissionsPlugin() instanceof ResidenceVaultAdapter) {
                        ResidenceVaultAdapter vault = (ResidenceVaultAdapter) this.getPermissionManager().getPermissionsPlugin();
                        if (vault.economyOK()) {
                            economy = vault;
                            lm.consoleMessage("Found Vault using economy system: &5" + vault.getEconomyName());
                        }
                    }
                    if (economy == null) {
                        this.loadVaultEconomy();
                    }
                    break;
                case Vault:
                    if (this.getPermissionManager().getPermissionsPlugin() instanceof ResidenceVaultAdapter) {
                        ResidenceVaultAdapter vault = (ResidenceVaultAdapter) this.getPermissionManager().getPermissionsPlugin();
                        if (vault.economyOK()) {
                            economy = vault;
                            lm.consoleMessage("Found Vault using economy system: &5" + vault.getEconomyName());
                        }
                    }
                    if (economy == null) {
                        this.loadVaultEconomy();
                    }
                    break;
                default:
                    break;
                }

                if (economy == null) {
                    lm.consoleMessage("Unable to find an economy system...");
                    economy = new BlackHoleEconomy();
                }
            }

            pmanager = new PermissionListManager(this);

            getLocaleManager().LoadLang(getConfigManager().getLanguage());

            getLM().LanguageReload();

            if (firstenable) {
                if (!this.isEnabled()) {
                    return;
                }

                File f = new File(getDataFolder(), "flags.yml");
                YamlConfiguration conf = YamlConfiguration.loadConfiguration(f);
                for (String oneFlag : conf.getStringList("Global.GroupedFlags." + padd.groupedFlag)) {
                    Flags flag = Flags.getFlag(oneFlag);
                    if (flag != null) {
                        flag.addGroup(padd.groupedFlag);
                    }
                    FlagPermissions.addFlagToFlagGroup(padd.groupedFlag, oneFlag);
                }

            }

            initDatabaseStorage();

            try {
                this.loadYml();
            } catch (Exception e) {
                this.getLogger().log(Level.SEVERE, "Unable to load save file", e);
                throw e;
            }

            initRedisSyncBus();
            getGuiMetaManager().load();
            getPlayerLimitManager().load();
            getCleanupReclaimManager().load();
            if (getCrossServerManager().isEnabled()) {
                databaseStorageManager.syncLocalServerIndex(getStorageServerName(), getCrossServerManager().buildLocalEntriesSnapshot());
                databaseStorageManager.clearResidenceReservations(getStorageServerName());
            }
            getCrossServerManager().start();
            getAuctionManager().start();
            getAuditManager().start();

            getSignUtil().LoadSigns();

            if (getConfigManager().isUseResidenceFileClean()) {
                if (databaseStorageManager != null && databaseStorageManager.isInitialized()) {
                    getCrossServerManager().seedLocalPlayerActivityNow();
                } else {
                    getCrossServerManager().seedLocalPlayerActivityAsync();
                }
                (new FileCleanUp(this)).cleanOldResidence();
            } else {
                getCrossServerManager().seedLocalPlayerActivityAsync();
            }
            if (getConfigManager().isAutoCleanRestoreOnReturn() && databaseStorageManager != null && databaseStorageManager.isInitialized()) {
                getCleanupReclaimManager().reconcileCrossServerReturnsAsync();
            }

            if (firstenable) {
                if (!this.isEnabled()) {
                    return;
                }
                FlagPermissions.initValidFlags();

                if (smanager == null)
                    setWorldEdit();
                setWorldGuard();

                setKingdoms();

                PluginManager pm = getServer().getPluginManager();

                if (Version.isCurrentEqualOrHigher(Version.v1_8_0)) {
                    pm.registerEvents(new ResidenceListener1_08(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_9_0)) {
                    pm.registerEvents(new ResidenceListener1_09(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_10_0)) {
                    pm.registerEvents(new ResidenceListener1_10(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_12_0)) {
                    pm.registerEvents(new ResidenceListener1_12(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_13_0)) {
                    pm.registerEvents(new ResidenceListener1_13(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_14_0)) {
                    pm.registerEvents(new ResidenceListener1_14(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_15_0)) {
                    pm.registerEvents(new ResidenceListener1_15(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_16_5) && Version.isPaperBranch()) {
                    pm.registerEvents(new ResidenceListener1_16_5_Paper(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_17_0)) {
                    pm.registerEvents(new ResidenceListener1_17(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_19_0)) {
                    pm.registerEvents(new ResidenceListener1_19(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_20_0)) {
                    pm.registerEvents(new ResidenceListener1_20(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_21_0)) {
                    pm.registerEvents(new ResidenceListener1_21(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_21_8)) {
                    if (Version.isPaperBranch()) {
                        pm.registerEvents(new ResidenceListener1_21_8_Paper(this), this);
                    } else {
                        pm.registerEvents(new ResidenceListener1_21_8_Spigot(this), this);
                    }
                }
                if (Version.isCurrentEqualOrHigher(Version.v1_21_9) && Version.isPaperBranch()) {
                    pm.registerEvents(new ResidenceListener1_21_9_Paper(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v26_1_2) && Version.isPaperBranch()) {
                    pm.registerEvents(new ResidenceListener26_1_2_Paper(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v26_2_0)) {
                    if (Version.isPaperBranch()) {
                        pm.registerEvents(new ResidenceListener26_2_Paper(this), this);
                    }
                    pm.registerEvents(new ResidenceListener26_2(this), this);
                }
                if (Version.isCurrentEqualOrHigher(Version.v26_3_0) && Version.isPaperBranch()) {
                    pm.registerEvents(new ResidenceListener26_3_Paper(this), this);
                }
                pm.registerEvents(new ResidenceBlockListener(this), this);
                pm.registerEvents(new ContainerTransferListener(this), this);
                pm.registerEvents(getPlayerListener(), this);
                pm.registerEvents(new ResidenceEntityListener(this), this);
                pm.registerEvents(new ShopListener(this), this);
                pm.registerEvents(new ResidenceRaidListener(), this);
                pm.registerEvents(getResidenceListGui(), this);
                pm.registerEvents(getMarketGui(), this);
                pm.registerEvents(getAuctionGui(), this);
                pm.registerEvents(new AuctionProtectionListener(this), this);
                pm.registerEvents(new CrossServerListener(this), this);
                pm.registerEvents(getAuditManager(), this);

                if (Version.isCurrentLower(Version.v1_16_0)) {
                    pm.registerEvents(new DenyMessageCache_Legacy(), this);
                }
                firstenable = false;
            } else {
                getPlayerListener().reload();
            }

            if (setupPlaceHolderAPI()) {
                lm.consoleMessage("PlaceholderAPI was found - Enabling capabilities.");
                PlaceholderAPIEnabled = true;
            }

            if (getServer().getPluginManager().getPlugin("CrackShot") != null)
                getServer().getPluginManager().registerEvents(new CrackShotListener(this), this);

            this.getWebMapManager().activate();

            int autosaveInt = getConfigManager().getAutoSaveInterval();
            if (autosaveInt < 1) {
                autosaveInt = 1;
            }
            autosaveInt = autosaveInt * 60 * 20;
            autosaveBukkitId = CMIScheduler.scheduleSyncRepeatingTask(this, autoSave, autosaveInt, autosaveInt);

            if (getConfigManager().getHealInterval() > 0)
                healBukkitId = CMIScheduler.scheduleSyncRepeatingTask(this, doHeals, 20, getConfigManager().getHealInterval() * 20);
            if (getConfigManager().getFeedInterval() > 0)
                feedBukkitId = CMIScheduler.scheduleSyncRepeatingTask(this, doFeed, 20, getConfigManager().getFeedInterval() * 20);
            if (getConfigManager().getSafeZoneInterval() > 0)
                effectRemoveBukkitId = CMIScheduler.scheduleSyncRepeatingTask(this, removeBadEffects, 20, getConfigManager().getSafeZoneInterval() * 20);

            if (getConfigManager().AutoMobRemoval())
                despawnMobsBukkitId = CMIScheduler.scheduleSyncRepeatingTask(this, DespawnMobs, 20 * getConfigManager().AutoMobRemovalInterval(), 20
                        * getConfigManager().AutoMobRemovalInterval());

            if (getConfigManager().useLeases()) {
                int leaseInterval = getConfigManager().getLeaseCheckInterval();
                if (leaseInterval < 1) {
                    leaseInterval = 1;
                }
                leaseInterval = leaseInterval * 60 * 20;
                leaseBukkitId = CMIScheduler.scheduleSyncRepeatingTask(this, leaseExpire, leaseInterval, leaseInterval);
            }
            if (getConfigManager().enabledRentSystem()) {
                int rentint = getConfigManager().getRentCheckInterval();
                if (rentint < 1) {
                    rentint = 1;
                }
                rentint = rentint * 60 * 20;
                rentBukkitId = CMIScheduler.scheduleSyncRepeatingTask(this, rentExpire, rentint, rentint);
            }
            for (Player player : Bukkit.getServer().getOnlinePlayers()) {
                if (getPermissionManager().isResidenceAdmin(player)) {
                    ResAdmin.turnResAdminOn(player);
                }
            }

            metrics = new Metrics(this, 27340);

            lm.consoleMessage("Enabled! Version " + this.getDescription().getVersion() + " by Zrips");
            initsuccess = true;

        } catch (Exception ex) {
            initsuccess = false;
            getServer().getPluginManager().disablePlugin(this);
            lm.consoleMessage("- FAILED INITIALIZATION! DISABLED! ERROR:");
            Logger.getLogger(Residence.class.getName()).log(Level.SEVERE, null, ex);
            Bukkit.getServer().shutdown();
        }

        getShopSignUtilManager().LoadShopVotes();
        getShopSignUtilManager().LoadSigns();
        getShopSignUtilManager().boardUpdate();

        CMIVersionChecker.VersionCheck(null, 11480, this.getDescription());
        fullyLoaded = true;
    }

    private boolean setupPlaceHolderAPI() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI"))
            return false;
        return new PlaceholderAPIHook(this).register();
    }

    public SignUtil getSignUtil() {
        if (signmanager == null)
            signmanager = new SignUtil(this);
        return signmanager;
    }

    private void setWorldEdit() {
        try {
            Plugin plugin = server.getPluginManager().getPlugin("WorldEdit");
            if (plugin != null) {
                if (plugin.getDescription().getVersion().startsWith("6.")) {
                    smanager = new SelectionManager(server, this);
                    lm.consoleMessage("Found WorldEdit " + plugin.getDescription().getVersion() + " but version 6.x is not supported!");
                    return;
                }

                this.wep = plugin;

                if (SelectionManager.isWorldEditIntegration())
                    smanager = new WorldEdit7SelectionManager(server, this);
                if (wep != null)
                    SchematicManager = new Schematics7Manager(this);

                if (smanager == null)
                    smanager = new SelectionManager(server, this);
                if (this.getWorldEdit().getConfig().isInt("wand-item"))
                    wepid = CMIMaterial.get(this.getWorldEdit().getConfig().getInt("wand-item"));
                else
                    wepid = CMIMaterial.get((String) this.getWorldEdit().getConfig().get("wand-item"));

                lm.consoleMessage("Found WorldEdit " + this.getWorldEdit().getDescription().getVersion());
            } else {
                smanager = new SelectionManager(server, this);
            }
        } catch (Exception | Error e) {
            e.printStackTrace();
        }
    }

    private boolean kingdomsPresent = false;

    private void setKingdoms() {
        if (Bukkit.getPluginManager().getPlugin("Kingdoms") != null) {
            try {
                Class.forName("org.kingdoms.constants.land.location.SimpleChunkLocation");
                kingdomsPresent = true;
            } catch (Throwable e) {
                lm.consoleMessage("Failed to recognize Kingdoms plugin. Compatability disabled");
            }
        }
    }

    public boolean isKingdomsPresent() {
        return kingdomsPresent;
    }

    private void setWorldGuard() {
        Plugin wgplugin = server.getPluginManager().getPlugin("WorldGuard");
        if (wgplugin != null) {
            wg = wgplugin;
            lm.consoleMessage("Found WorldGuard " + wg.getDescription().getVersion());
        }
    }

    public Residence getPlugin() {
        return this;
    }

    public File getDataLocation() {
        return dataFolder;
    }

    public ShopSignUtil getShopSignUtilManager() {
        if (ShopSignUtilManager == null)
            ShopSignUtilManager = new ShopSignUtil(this);
        return ShopSignUtilManager;
    }

    public CommandFiller getCommandFiller() {
        if (cmdFiller == null) {
            cmdFiller = new CommandFiller();
        }
        return cmdFiller;
    }

    public ResidenceManager getResidenceManager() {
        if (rmanager == null)
            rmanager = new ResidenceManager(this);
        return rmanager;
    }

    public SelectionManager getSelectionManager() {
        if (smanager == null)
            setWorldEdit();
        return smanager;
    }

    public FlagUtil getFlagUtilManager() {
        if (FlagUtilManager == null)
            FlagUtilManager = new FlagUtil(this);
        return FlagUtilManager;
    }

    public PermissionManager getPermissionManager() {
        if (gmanager == null)
            gmanager = new PermissionManager(this);
        return gmanager;
    }

    public PermissionListManager getPermissionListManager() {
        return pmanager;
    }

    public WebMapManager getWebMapManager() {
        if (webMapManager == null)
            webMapManager = new WebMapManager();
        return webMapManager;
    }

    public WESchematicManager getSchematicManager() {
        return SchematicManager;
    }


    public ComboSelection getComboSelectionManager() {
        if (ComboSelectionManager == null) {
            ComboSelectionManager = new ComboSelection(this);
        }
        return ComboSelectionManager;
    }

    public Sorting getSortingManager() {
        if (SortingManager == null)
            SortingManager = new Sorting();
        return SortingManager;
    }

    public RandomTp getRandomTpManager() {
        if (RandomTpManager == null)
            RandomTpManager = new RandomTp(this);
        return RandomTpManager;
    }

    public EconomyInterface getEconomyManager() {
        return economy;
    }

    public Server getServ() {
        return server;
    }

    public LeaseManager getLeaseManager() {
        if (leasemanager == null)
            leasemanager = new LeaseManager(this);
        return leasemanager;
    }

    public PlayerManager getPlayerManager() {
        if (PlayerManager == null)
            PlayerManager = new PlayerManager(this);
        return PlayerManager;
    }

    public HelpEntry getHelpPages() {
        return LocaleManager.getHelpPages();
    }

    public ConfigManager getConfigManager() {
        if (configManager == null)
            configManager = new ConfigManager(this);
        return configManager;
    }

    public TransactionManager getTransactionManager() {
        if (tmanager == null)
            tmanager = new TransactionManager(this);
        return tmanager;
    }

    public WorldItemManager getItemManager() {
        if (imanager == null)
            imanager = new WorldItemManager(this);
        return imanager;
    }

    public WorldFlagManager getWorldFlags() {
        if (wmanager == null)
            wmanager = new WorldFlagManager(this);
        return wmanager;
    }

    public RentManager getRentManager() {
        if (rentmanager == null)
            rentmanager = new RentManager(this);
        return rentmanager;
    }

    public LocaleManager getLocaleManager() {
        if (localeManager == null)
            localeManager = new LocaleManager(this);
        return localeManager;
    }

    public Language getLM() {
        if (newLanguageManager == null) {
            newLanguageManager = new Language(this);
            newLanguageManager.LanguageReload();
        }
        return newLanguageManager;
    }

    public ResidencePlayerListener getPlayerListener() {
        if (plistener == null)
            plistener = new ResidencePlayerListener(this);
        return plistener;
    }

    public ChatManager getChatManager() {
        if (chatmanager == null)
            chatmanager = new ChatManager();
        return chatmanager;
    }

    public String getResidenceVersion() {
        return ResidenceVersion;
    }

    public List<String> getAuthors() {
        return authlist;
    }

    @Deprecated
    public FlagPermissions getPermsByLoc(Location loc) {
        return FlagPermissions.getPerms(loc);
    }

    @Deprecated
    public FlagPermissions getPermsByLocForPlayer(Location loc, Player player) {
        return FlagPermissions.getPerms(loc, player);
    }

    private void loadEssentialsEconomy() {
        Plugin p = getServer().getPluginManager().getPlugin("Essentials");
        if (p != null) {
            economy = new EssentialsEcoAdapter((Essentials) p);
            lm.consoleMessage("Successfully linked with &5" + economy.getName());
        }
    }

    private void loadCMIEconomy() {
        Plugin p = getServer().getPluginManager().getPlugin("CMI");
        if (p != null) {
            economy = new CMIEconomy();
            lm.consoleMessage("Successfully linked with &5" + economy.getName());
        }
    }

    private void loadVaultEconomy() {
        Plugin p = getServer().getPluginManager().getPlugin("Vault");
        if (p != null) {
            ResidenceVaultAdapter vault = new ResidenceVaultAdapter(getServer());
            if (vault.economyOK()) {
                lm.consoleMessage("Found Vault using economy: &5" + vault.getEconomyName());
                economy = vault;

            } else {
                lm.consoleMessage("Found Vault, but Vault reported no usable economy system...");
            }
        } else {
            lm.consoleMessage("Vault NOT found!");
        }
    }

    public com.bekvon.bukkit.residence.storage.PlayerLimitManager getPlayerLimitManager() {
        if (playerLimitManager == null)
            playerLimitManager = new com.bekvon.bukkit.residence.storage.PlayerLimitManager(this);
        return playerLimitManager;
    }

    public com.bekvon.bukkit.residence.storage.CleanupReclaimManager getCleanupReclaimManager() {
        if (cleanupReclaimManager == null)
            cleanupReclaimManager = new com.bekvon.bukkit.residence.storage.CleanupReclaimManager(this);
        return cleanupReclaimManager;
    }

    public void reloadDatabaseAndCrossServer() {
        if (auctionManager != null) {
            auctionManager.stop();
        }
        if (auditManager != null) {
            auditManager.stop();
        }
        if (crossServerManager != null) {
            crossServerManager.flushOnlinePlayerActivityNow();
            crossServerManager.stop();
        }
        if (redisSyncBus != null) {
            redisSyncBus.stop();
        }
        initRedisSyncBus();
        if (guiMetaManager != null) {
            guiMetaManager.load();
        }
        if (playerLimitManager != null) {
            playerLimitManager.load();
        }
        if (cleanupReclaimManager != null) {
            cleanupReclaimManager.load();
        }
        getCrossServerManager().start();
        getAuctionManager().start();
    }

    public String getStorageServerName() {
        return storageServerName == null ? getConfigManager().getCrossServerName() : storageServerName;
    }

    private void initDatabaseStorage() {
        storageServerName = getConfigManager().getCrossServerName();
        StorageType storageType = getConfigManager().getStorageType();
        if (!storageType.isDatabase()) {
            databaseStorageManager = null;
            return;
        }
        databaseStorageManager = new DatabaseStorageManager(
                storageType,
                dataFolder,
                getConfigManager().getStorageSqliteFile(),
                getConfigManager().getStorageHost(),
                getConfigManager().getStoragePort(),
                getConfigManager().getStorageDatabase(),
                getConfigManager().getStorageUsername(),
                getConfigManager().getStoragePassword(),
                getConfigManager().getStorageTablePrefix(),
                getConfigManager().getStorageParameters());
        try {
            databaseStorageManager.initialize();
            databaseStorageManager.clearResidenceReservations(storageServerName);
            lm.consoleMessage("Initialized " + storageType.name() + " database storage.");
        } catch (Exception ex) {
            databaseStorageManager = null;
            throw new IllegalStateException("Failed to initialize " + storageType.name() + " database storage", ex);
        }
    }

    public static void writeSaveRecord(File destination, Map<String, Object> records) throws IOException {
        saveRecord(destination, records);
    }

    private static void moveAtomicallyOrReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void saveRecord(File destination, Map<String, Object> records) throws IOException {
        Path file = destination.toPath();
        Path directory = file.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, destination.getName() + ".", ".tmp");
        try {
            YMLSaveHelper yml = new YMLSaveHelper(temporary.toFile());
            yml.getRoot().putAll(records);
            yml.save();

            if (Files.isRegularFile(file)) {
                Path backupDirectory = Files.createDirectories(directory.resolve("Backup"));
                Path backup = backupDirectory.resolve(file.getFileName());
                Path backupTemporary = Files.createTempFile(backupDirectory, destination.getName() + ".", ".tmp");
                try {
                    Files.copy(file, backupTemporary, StandardCopyOption.REPLACE_EXISTING);
                    moveAtomicallyOrReplace(backupTemporary, backup);
                } finally {
                    Files.deleteIfExists(backupTemporary);
                }
            }
            moveAtomicallyOrReplace(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void saveResidenceDataNow() {
        try {
            saveYml();
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Failed to persist residence data after cleanup", ex);
        }
    }

    private synchronized void saveYml() throws IOException {
        boolean useDb = databaseStorageManager != null && databaseStorageManager.isInitialized();
        boolean writeYaml = !useDb || getConfigManager().isStorageSyncWithYamlBackup();
        String serverName = getStorageServerName();

        File saveFolder = new File(dataFolder, "Save");
        File worldFolder = new File(saveFolder, "Worlds");
        Map<String, Object> savedWorlds = getResidenceManager().save();
        Map<String, String> worldDocuments = new LinkedHashMap<>();
        Map<File, Map<String, Object>> yamlDocuments = new LinkedHashMap<>();
        for (Entry<String, Object> entry : savedWorlds.entrySet()) {
            String world = entry.getKey();
            File destination = new File(worldFolder, "res_" + getSaveWorldName(world) + ".yml");
            if (entry.getValue() instanceof Map && ((Map<?, ?>) entry.getValue()).isEmpty() && !destination.isFile() && !useDb)
                continue;

            Map<String, Object> records = new LinkedHashMap<>();
            Map<?, ?> messages = getResidenceManager().getMessageCatch(world);
            if (messages != null)
                records.put("Messages", messages);
            Map<?, ?> flags = getResidenceManager().getFlagsCatch(world);
            if (flags != null)
                records.put("Flags", flags);
            records.put("Residences", entry.getValue());

            if (useDb)
                worldDocuments.put(world, YMLSaveHelper.dumpToString(records));
            if (writeYaml)
                yamlDocuments.put(destination, records);
        }

        Map<String, Object> economyData = Collections.singletonMap("Economy", getTransactionManager().save());
        Map<String, Object> leasesData = Collections.singletonMap("Leases", getLeaseManager().save());
        Map<String, Object> permListsData = Collections.singletonMap("PermissionLists", pmanager.save());
        Map<String, Object> rentData = Collections.singletonMap("RentSystem", getRentManager().save());

        if (useDb) {
            try {
                Map<String, String> auxiliaryDocuments = new LinkedHashMap<>();
                auxiliaryDocuments.put("forsale", YMLSaveHelper.dumpToString(economyData));
                auxiliaryDocuments.put("leases", YMLSaveHelper.dumpToString(leasesData));
                auxiliaryDocuments.put("permlists", YMLSaveHelper.dumpToString(permListsData));
                auxiliaryDocuments.put("rent", YMLSaveHelper.dumpToString(rentData));
                Map<String, Map<String, String>> documents = new LinkedHashMap<>();
                documents.put("world", worldDocuments);
                documents.put("aux", auxiliaryDocuments);
                databaseStorageManager.saveDocuments(serverName, documents);
                if (crossServerManager != null && crossServerManager.isEnabled()) {
                    crossServerManager.syncLocalIndexNow();
                }
            } catch (Exception ex) {
                throw new IOException("Failed saving auxiliary data to database", ex);
            }
        }
        if (writeYaml) {
            for (Entry<File, Map<String, Object>> entry : yamlDocuments.entrySet())
                saveRecord(entry.getKey(), entry.getValue());
            saveRecord(new File(saveFolder, "forsale.yml"), economyData);
            saveRecord(new File(saveFolder, "leases.yml"), leasesData);
            saveRecord(new File(saveFolder, "permlists.yml"), permListsData);
            saveRecord(new File(saveFolder, "rent.yml"), rentData);
        }

        if (getConfigManager().showIntervalMessages()) {
            System.out.println("[Residence] - Saved Residences...");
        }
    }

    public final static String saveFilePrefix = "res_";

    /**
     * Replaces path separators in a world name for use in save file names.
     *
     * @param worldName world name
     * @return file-safe world name
     */
    public static String getSaveWorldName(String worldName) {
        return worldName.replace('/', '_');
    }

    private void loadFlags(String worldName, YMLSaveHelper yml) {
        if (!yml.getRoot().containsKey("Flags"))
            return;

        HashMap<Integer, MinimizeFlags> c = getResidenceManager().getCacheFlags().get(worldName);
        if (c == null)
            c = new HashMap<Integer, MinimizeFlags>();
        Map<Integer, Object> ms = (Map<Integer, Object>) yml.getRoot().get("Flags");
        if (ms == null)
            return;

        for (Entry<Integer, Object> one : ms.entrySet()) {
            try {
                HashMap<String, Boolean> msgs = (HashMap<String, Boolean>) one.getValue();
                c.put(one.getKey(), new MinimizeFlags(one.getKey(), msgs));
            } catch (Exception e) {

            }
        }
        getResidenceManager().getCacheFlags().put(worldName, c);
    }

    private void loadMessages(String worldName, YMLSaveHelper yml) {
        if (!yml.getRoot().containsKey("Messages"))
            return;

        HashMap<Integer, MinimizeMessages> c = getResidenceManager().getCacheMessages().get(worldName);
        if (c == null)
            c = new HashMap<Integer, MinimizeMessages>();
        Map<Integer, Object> ms = (Map<Integer, Object>) yml.getRoot().get("Messages");
        if (ms == null)
            return;

        for (Entry<Integer, Object> one : ms.entrySet()) {
            try {
                Map<String, String> msgs = (Map<String, String>) one.getValue();
                c.put(one.getKey(), new MinimizeMessages(one.getKey(), msgs.get("EnterMessage"), msgs.get("LeaveMessage")));
            } catch (Exception e) {

            }
        }
        getResidenceManager().getCacheMessages().put(worldName, c);
    }

    private void loadMessagesAndFlags(String worldName, YMLSaveHelper yml) {
        loadMessages(worldName, yml);
        loadFlags(worldName, yml);
    }

    public YMLSaveHelper loadYmlWithBackup(File primaryFile) throws Exception {
        File backupFile = new File(new File(primaryFile.getParentFile(), "Backup"), primaryFile.getName());
        Exception primaryError = null;

        if (primaryFile.isFile() && primaryFile.length() > 0) {
            try {
                YMLSaveHelper yml = new YMLSaveHelper(primaryFile);
                yml.load();
                if (yml.getRoot() != null)
                    return yml;
            } catch (Exception ex) {
                primaryError = ex;
            }
        }

        if (backupFile.isFile() && backupFile.length() > 0) {
            try {
                YMLSaveHelper backupYml = new YMLSaveHelper(backupFile);
                backupYml.load();
                if (backupYml.getRoot() != null) {
                    getLogger().warning("Primary save file " + primaryFile.getName() + " was missing or corrupted; recovered from Backup/" + backupFile.getName());
                    return backupYml;
                }
            } catch (Exception backupEx) {
                if (primaryError != null)
                    primaryError.addSuppressed(backupEx);
                else
                    primaryError = backupEx;
            }
        }

        if (primaryError != null)
            throw primaryError;
        return null;
    }

    private void loadMessagesAndFlags(String worldName, Map<String, Object> root) {
        if (root == null)
            return;
        if (root.containsKey("Messages")) {
            HashMap<Integer, MinimizeMessages> c = getResidenceManager().getCacheMessages().get(worldName);
            if (c == null)
                c = new HashMap<Integer, MinimizeMessages>();
            Map<Integer, Object> ms = (Map<Integer, Object>) root.get("Messages");
            if (ms != null) {
                for (Entry<Integer, Object> one : ms.entrySet()) {
                    try {
                        Map<String, String> msgs = (Map<String, String>) one.getValue();
                        c.put(one.getKey(), new MinimizeMessages(one.getKey(), msgs.get("EnterMessage"), msgs.get("LeaveMessage")));
                    } catch (Exception e) {
                    }
                }
                getResidenceManager().getCacheMessages().put(worldName, c);
            }
        }
        if (root.containsKey("Flags")) {
            HashMap<Integer, MinimizeFlags> c = getResidenceManager().getCacheFlags().get(worldName);
            if (c == null)
                c = new HashMap<Integer, MinimizeFlags>();
            Map<Integer, Object> ms = (Map<Integer, Object>) root.get("Flags");
            if (ms != null) {
                for (Entry<Integer, Object> one : ms.entrySet()) {
                    try {
                        HashMap<String, Boolean> msgs = (HashMap<String, Boolean>) one.getValue();
                        c.put(one.getKey(), new MinimizeFlags(one.getKey(), msgs));
                    } catch (Exception e) {
                    }
                }
                getResidenceManager().getCacheFlags().put(worldName, c);
            }
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean loadFromDatabase() throws Exception {
        String serverName = getStorageServerName();
        Map<String, Map<String, String>> documents = databaseStorageManager.loadDocuments(serverName);
        if (documents.isEmpty())
            return false;
        Map<String, String> worldPayloads = documents.getOrDefault("world", Collections.emptyMap());
        Map<String, String> auxiliary = documents.getOrDefault("aux", Collections.emptyMap());

        HashMap<String, Object> worlds = new HashMap<>();
        for (Entry<String, String> entry : worldPayloads.entrySet()) {
            String worldName = entry.getKey();
            Map<String, Object> root = YMLSaveHelper.loadFromString(entry.getValue());
            if (root == null)
                continue;
            loadMessagesAndFlags(worldName, root);
            worlds.put(worldName, root.get("Residences"));
        }

        getResidenceManager().load(worlds);
        Map<String, ClaimedResidence> resList = getResidenceManager().getResidences();
        for (Entry<String, ClaimedResidence> one : resList.entrySet()) {
            this.getResidenceManager().addShops(one.getValue());
        }

        String forsale = auxiliary.get("forsale");
        if (forsale != null) {
            Map<String, Object> root = YMLSaveHelper.loadFromString(forsale);
            if (root != null)
                getTransactionManager().load((Map) root.get("Economy"));
        }
        String leases = auxiliary.get("leases");
        if (leases != null) {
            Map<String, Object> root = YMLSaveHelper.loadFromString(leases);
            if (root != null)
                getLeaseManager().load((Map) root.get("Leases"));
        }
        String permlists = auxiliary.get("permlists");
        if (permlists != null) {
            Map<String, Object> root = YMLSaveHelper.loadFromString(permlists);
            if (root != null)
                pmanager = getPermissionListManager().load((Map) root.get("PermissionLists"));
        }
        String rent = auxiliary.get("rent");
        if (rent != null) {
            Map<String, Object> root = YMLSaveHelper.loadFromString(rent);
            if (root != null)
                getRentManager().load((Map) root.get("RentSystem"));
        }

        lm.consoleMessage("Loaded " + worlds.size() + " world(s) from " + databaseStorageManager.getStorageType().name() + " database.");
        return true;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected boolean loadYml() throws Exception {
        if (databaseStorageManager != null && databaseStorageManager.isInitialized()) {
            if (loadFromDatabase()) {
                return true;
            }
        }
        File saveFolder = new File(dataFolder, "Save");
        try {
            File worldFolder = new File(saveFolder, "Worlds");
            if (!saveFolder.isDirectory()) {
                saveFolder.mkdir();
                if (!saveFolder.isDirectory()) {
                    this.getLogger().warning("Save directory does not exist...");
                    this.getLogger().warning("Please restart server");
                    return true;
                }
            }
            long time;
            YMLSaveHelper yml;
            File loadFile;
            HashMap<String, Object> worlds = new HashMap<>();

            for (String worldName : this.getResidenceManager().getWorldNames()) {
                loadFile = new File(worldFolder, saveFilePrefix + getSaveWorldName(worldName) + ".yml");
                File backupFile = new File(new File(worldFolder, "Backup"), loadFile.getName());
                if (!loadFile.isFile() && !backupFile.isFile())
                    continue;

                time = System.currentTimeMillis();

                if (!isDisabledWorld(worldName) && !this.getConfigManager().CleanerStartupLog)
                    lm.consoleMessage("Loading save data for world " + worldName + "...");

                yml = loadYmlWithBackup(loadFile);
                if (yml == null || yml.getRoot() == null)
                    continue;

                loadMessagesAndFlags(worldName, yml);

                worlds.put(worldName, yml.getRoot().get("Residences"));

                int pass = (int) (System.currentTimeMillis() - time);
                String pastTime = pass > 1000 ? String.format("%.2f", (pass / 1000F)) + " sec" : pass + " ms";

                if (!isDisabledWorld(worldName) && !this.getConfigManager().CleanerStartupLog)
                    lm.consoleMessage("Loaded " + worldName + " data. (" + pastTime + ")");
            }

            getResidenceManager().load(worlds);

            // Getting shop residences
            Map<String, ClaimedResidence> resList = getResidenceManager().getResidences();
            for (Entry<String, ClaimedResidence> one : resList.entrySet()) {
                this.getResidenceManager().addShops(one.getValue());
            }

            loadFile = new File(saveFolder, "forsale.yml");
            yml = loadYmlWithBackup(loadFile);
            if (yml != null && yml.getRoot() != null) {
                getTransactionManager().load((Map) yml.getRoot().get("Economy"));
            }
            loadFile = new File(saveFolder, "leases.yml");
            yml = loadYmlWithBackup(loadFile);
            if (yml != null && yml.getRoot() != null) {
                getLeaseManager().load((Map) yml.getRoot().get("Leases"));
            }
            loadFile = new File(saveFolder, "permlists.yml");
            yml = loadYmlWithBackup(loadFile);
            if (yml != null && yml.getRoot() != null) {
                pmanager = getPermissionListManager().load((Map) yml.getRoot().get("PermissionLists"));
            }
            loadFile = new File(saveFolder, "rent.yml");
            yml = loadYmlWithBackup(loadFile);
            if (yml != null && yml.getRoot() != null) {
                getRentManager().load((Map) yml.getRoot().get("RentSystem"));
            }

            return true;
        } catch (Exception ex) {
            Logger.getLogger(Residence.class.getName()).log(Level.SEVERE, null, ex);
            throw ex;
        }
    }

    private void writeDefaultFromJar(String fileName) {
        if (this.writeDefaultFileFromJar(new File(this.getDataFolder(), fileName), fileName, true)) {
            lm.consoleMessage("Wrote default " + fileName + "...");
        }
    }

    private boolean writeDefaultFileFromJar(File writeName, String jarPath, boolean backupOld) {
        try {
            File fileBackup = new File(this.getDataFolder(), "backup-" + writeName);
            File jarloc = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile();
            if (jarloc.isFile()) {
                JarFile jar = new JarFile(jarloc);
                try {
                    JarEntry entry = jar.getJarEntry(jarPath);
                    if (entry != null && !entry.isDirectory()) {
                        InputStream in = jar.getInputStream(entry);
                        InputStreamReader isr = new InputStreamReader(in, StandardCharsets.UTF_8);
                        if (writeName.isFile()) {
                            if (backupOld) {
                                if (fileBackup.isFile()) {
                                    fileBackup.delete();
                                }
                                writeName.renameTo(fileBackup);
                            } else {
                                writeName.delete();
                            }
                        }
                        FileOutputStream out = new FileOutputStream(writeName);
                        OutputStreamWriter osw = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                        try {
                            char[] tempbytes = new char[512];
                            int readbytes = isr.read(tempbytes, 0, 512);
                            while (readbytes > -1) {
                                osw.write(tempbytes, 0, readbytes);
                                readbytes = isr.read(tempbytes, 0, 512);
                            }
                        } catch (Throwable e) {
                            e.printStackTrace();
                        } finally {
                            osw.close();
                            isr.close();
                            out.close();
                        }
                        return true;
                    }
                } catch (Throwable ex) {
                    ex.printStackTrace();
                } finally {
                    jar.close();
                }
            }
            return false;
        } catch (Exception ex) {
            System.out.println("[Residence] Failed to write file: " + writeName);
            return false;
        }
    }

    public String getServerLandName() {
        if (ServerLandName == null)
            ServerLandName = this.getLM().getMessage(lm.server_land);
        return ServerLandName;
    }

    public UUID getServerUUID() {
        return ServerLandUUID;
    }

    public boolean isDisabledWorld(World world) {
        return isDisabledWorld(world.getName());
    }

    public boolean isDisabledWorld(String worldName) {
        if (getConfigManager().DisabledWorld) {
            if (!getConfigManager().EnabledWorldsList.isEmpty()) {
                return !getConfigManager().EnabledWorldsList.contains(worldName);
            }
            return getConfigManager().DisabledWorldsList.contains(worldName);
        }
        return false;
    }

    public boolean isDisabledWorldListener(Location loc) {
        if (loc != null && getConfigManager().DisableListeners) {
            return isDisabledWorldListener(loc.getWorld().getName());
        }
        return false;
    }

    public boolean isDisabledWorldListener(Block block) {
        if (block != null && getConfigManager().DisableListeners) {
            return isDisabledWorldListener(block.getWorld().getName());
        }
        return false;
    }

    public boolean isDisabledWorldListener(Entity entity) {
        if (entity != null && getConfigManager().DisableListeners) {
            return isDisabledWorldListener(entity.getWorld().getName());
        }
        return false;
    }

    public boolean isDisabledWorldListener(World world) {
        if (world != null && getConfigManager().DisableListeners) {
            return isDisabledWorldListener(world.getName());
        }
        return false;
    }

    private boolean isDisabledWorldListener(String worldName) {
        if (!getConfigManager().EnabledWorldsList.isEmpty()) {
            return !getConfigManager().EnabledWorldsList.contains(worldName);
        }
        return getConfigManager().DisabledWorldsList.contains(worldName);
    }

    public boolean isDisabledWorldCommand(World world) {
        if (getConfigManager().DisableCommands) {
            return isDisabledWorldCommand(world.getName());
        }
        return false;
    }

    private boolean isDisabledWorldCommand(String worldName) {

        if (!getConfigManager().EnabledWorldsList.isEmpty()) {
            return !getConfigManager().EnabledWorldsList.contains(worldName);
        }

        return getConfigManager().DisabledWorldsList.contains(worldName);
    }

    public InformationPager getInfoPageManager() {
        return InformationPagerManager;
    }

    public Plugin getWorldEdit() {
        return wep;
    }

    public Plugin getWorldGuard() {
        return wg;
    }

    public CMIMaterial getWorldEditTool() {
        if (wepid == null)
            wepid = CMIMaterial.NONE;
        return wepid;
    }

    public WorldGuardInterface getWorldGuardUtil() {
        if (worldGuardUtil == null) {

            int version = 6;
            try {
                version = Integer.parseInt(wg.getDescription().getVersion().substring(0, 1));
            } catch (Exception | Error e) {
            }
            if (version >= 7) {
                wepVersion = version;
                worldGuardUtil = new WorldGuard7Util(this);
            }
        }
        return worldGuardUtil;
    }

    public KingdomsUtil getKingdomsUtil() {
        if (kingdomsUtil == null)
            kingdomsUtil = new KingdomsUtil(this);
        return kingdomsUtil;
    }

    public static Residence getInstance() {
        return instance;
    }

    public String getPrefix() {
        return CMIChatColor.GREEN + "[" + CMIChatColor.GOLD + "Residence" + CMIChatColor.GREEN + "]" + CMIChatColor.GRAY;
    }

    public int getWorldGuardVersion() {
        return wepVersion;
    }

    public boolean isSlimefunPresent() {
        return SlimeFun;
    }

    public boolean isLwcPresent() {
        return lwc;
    }

    public boolean isFullyLoaded() {
        return fullyLoaded;
    }

    @Deprecated
    public boolean isResAdminOn(CommandSender sender) {
        return ResAdmin.isResAdmin(sender);
    }

    @Deprecated
    public boolean isResAdminOn(Player player) {
        return ResAdmin.isResAdmin(player);
    }
}
