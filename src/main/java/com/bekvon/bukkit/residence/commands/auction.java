package com.bekvon.bukkit.residence.commands;

import java.sql.SQLException;
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
import com.bekvon.bukkit.residence.auction.AuctionDatabase.BidEntry;
import com.bekvon.bukkit.residence.auction.AuctionDatabase.PayoutRecord;
import com.bekvon.bukkit.residence.auction.AuctionManager;
import com.bekvon.bukkit.residence.auction.core.AuctionRecord;
import com.bekvon.bukkit.residence.auction.core.ClearMode;
import com.bekvon.bukkit.residence.containers.CommandAnnotation;
import com.bekvon.bukkit.residence.containers.cmd;
import com.bekvon.bukkit.residence.containers.lm;
import com.bekvon.bukkit.residence.gui.AuctionGui;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;

import net.Zrips.CMILib.Colors.CMIChatColor;
import net.Zrips.CMILib.FileHandler.ConfigReader;

/**
 * 領地拍賣指令（/res auction 與 /resadmin auction）。
 */
public class auction implements cmd {

    @Override
    @CommandAnnotation(simple = true, priority = 2650, regVar = { 0, 1, 2, 3, 4, 5, 6 }, consoleVar = { 1, 2, 3, 4, 5, 6 })
    public Boolean perform(Residence plugin, CommandSender sender, String[] args, boolean resadmin) {
        AuctionManager mgr = plugin.getAuctionManager();
        if (mgr == null || !mgr.isEnabled()) {
            lm.Auction_Disabled.sendMessage(sender);
            return true;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                return false;
            }
            plugin.getAuctionGui().openBrowser((Player) sender, AuctionGui.AuctionFilter.ALL, AuctionGui.AuctionSort.ENDING_SOON, resadmin, 1);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
        case "gui":
        case "list":
        case "browse": {
            if (!(sender instanceof Player)) {
                return false;
            }
            plugin.getAuctionGui().openBrowser((Player) sender, AuctionGui.AuctionFilter.ALL, AuctionGui.AuctionSort.ENDING_SOON, resadmin, 1);
            return true;
        }
        case "ack":
        case "notify":
        case "notifications": {
            if (!(sender instanceof Player)) {
                return false;
            }
            Player p = (Player) sender;
            plugin.getAuctionGui().openNotificationsGui(p, () ->
                    plugin.getAuctionGui().openBrowser(p, AuctionGui.AuctionFilter.ALL, AuctionGui.AuctionSort.ENDING_SOON, resadmin, 1));
            return true;
        }
        case "create":
        case "sell": {
            if (!(sender instanceof Player)) {
                return false;
            }
            Player p = (Player) sender;
            ClaimedResidence res = args.length >= 2
                    ? plugin.getResidenceManager().getByName(args[1])
                    : plugin.getResidenceManager().getByLoc(p.getLocation());
            if (res == null) {
                lm.Invalid_Residence.sendMessage(p);
                return true;
            }
            plugin.getAuctionGui().openTermsAgreementGui(p, res.getTopParent(), resadmin, () ->
                    plugin.getAuctionGui().openBrowser(p, AuctionGui.AuctionFilter.ALL, AuctionGui.AuctionSort.ENDING_SOON, resadmin, 1));
            return true;
        }
        case "start": {
            if (!resadmin) {
                lm.General_AdminOnly.sendMessage(sender);
                return true;
            }
            if (!(sender instanceof Player) || args.length < 3) {
                return false;
            }
            Player p = (Player) sender;
            ClaimedResidence res = plugin.getResidenceManager().getByName(args[1]);
            if (res == null) {
                lm.Invalid_Residence.sendMessage(sender);
                return true;
            }
            Long startPrice = parseLong(args[2]);
            if (startPrice == null) {
                lm.Invalid_Amount.sendMessage(sender);
                return true;
            }
            long minInc = args.length >= 4 && parseLong(args[3]) != null
                    ? parseLong(args[3])
                    : plugin.getConfigManager().getAuctionPolicy().getDefaultMinIncrement();
            long hours = args.length >= 5 && parseLong(args[4]) != null
                    ? parseLong(args[4])
                    : 72L;
            ClearMode mode = args.length >= 6 ? ClearMode.fromString(args[5]) : ClearMode.NONE;
            mgr.createAuction(p, res.getTopParent(), startPrice, minInc, hours * 60L, "", mode, true,
                    outcome -> sender.sendMessage(CMIChatColor.translate(outcome.message)));
            return true;
        }
        case "bid": {
            if (!(sender instanceof Player) || args.length < 3) {
                return false;
            }
            Player p = (Player) sender;
            Long auctionId = parseLong(args[1]);
            Long amount = parseLong(args[2]);
            if (auctionId == null || amount == null || amount <= 0L) {
                lm.Invalid_Amount.sendMessage(sender);
                return true;
            }
            AuctionRecord cached = mgr.getCachedActiveAuction(auctionId);
            if (cached == null) {
                lm.Auction_NotFound.sendMessage(sender, auctionId);
                return true;
            }
            // 指令喊價同樣先開啟二次確認畫面，確保玩家閱讀清權限與交付條款
            plugin.getAuctionGui().openBidConfirmGui(p, cached, amount, resadmin, () ->
                    plugin.getAuctionGui().openDetailGui(p, auctionId, resadmin, null));
            return true;
        }
        case "cancel": {
            if (args.length < 2) {
                return false;
            }
            Long auctionId = parseLong(args[1]);
            if (auctionId == null) {
                lm.Invalid_Amount.sendMessage(sender);
                return true;
            }
            String reason = args.length >= 3 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";
            mgr.cancelAuction(sender, auctionId, resadmin, reason,
                    outcome -> sender.sendMessage(CMIChatColor.translate(outcome.message)));
            return true;
        }
        case "setinc":
        case "increment": {
            if (args.length < 3) {
                return false;
            }
            Long auctionId = parseLong(args[1]);
            Long inc = parseLong(args[2]);
            if (auctionId == null || inc == null || inc < 1L) {
                lm.Invalid_Amount.sendMessage(sender);
                return true;
            }
            mgr.updateMinIncrement(sender, auctionId, resadmin, inc,
                    outcome -> sender.sendMessage(CMIChatColor.translate(outcome.message)));
            return true;
        }
        case "info": {
            if (args.length < 2) {
                return false;
            }
            Long auctionId = parseLong(args[1]);
            if (auctionId == null) {
                lm.Invalid_Amount.sendMessage(sender);
                return true;
            }
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try {
                    AuctionRecord rec = mgr.getDatabase().loadAuctionById(auctionId);
                    if (rec == null) {
                        sendSync(plugin, sender, lm.Auction_NotFound.getMessage(auctionId));
                        return;
                    }
                    String srvDisplay = plugin.getConfigManager().getCrossServerDisplayName(rec.getOriginServer());
                    StringBuilder sb = new StringBuilder();
                    sb.append(lm.Auction_Info_Header.getMessage(rec.getId(), rec.getResName())).append("\n");
                    sb.append(lm.Gui_Auction_Card_Type.getMessage(mgr.formatTypeLabel(rec.getType()))).append(" &8| &7狀態：&f").append(rec.getStatus().name()).append("\n");
                    sb.append(lm.Gui_Auction_Card_Server.getMessage(srvDisplay, rec.getWorldName())).append("\n");
                    sb.append(rec.hasBids()
                            ? lm.Gui_Auction_Card_CurrentBid.getMessage(mgr.formatMoney(rec.getCurrentBid()), rec.getHighestBidderName())
                            : lm.Gui_Auction_Card_StartPrice.getMessage(mgr.formatMoney(rec.getStartPrice()))).append("\n");
                    sb.append(lm.Gui_Auction_Card_MinIncrement.getMessage(mgr.formatMoney(rec.getMinIncrement()), rec.getBidCount())).append("\n");
                    sb.append(lm.Gui_Auction_Card_ClearMode.getMessage(mgr.formatClearModeLabel(rec.getClearMode()))).append("\n");
                    sb.append(lm.Gui_Auction_Card_Remaining.getMessage(mgr.formatDurationShort(rec.getEndsAt() - System.currentTimeMillis())));
                    sendSync(plugin, sender, sb.toString());
                } catch (SQLException ex) {
                    sendSync(plugin, sender, lm.Auction_DbError.getMessage());
                }
            });
            return true;
        }
        case "bids": {
            if (args.length < 2) {
                return false;
            }
            Long auctionId = parseLong(args[1]);
            if (auctionId == null) {
                lm.Invalid_Amount.sendMessage(sender);
                return true;
            }
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try {
                    List<BidEntry> bids = mgr.loadBidHistory(auctionId, 20);
                    SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss");
                    StringBuilder sb = new StringBuilder();
                    sb.append(lm.Auction_Bids_Header.getMessage(auctionId, bids.size()));
                    for (BidEntry b : bids) {
                        sb.append("\n&8[").append(sdf.format(new Date(b.createdAt))).append("] &e")
                                .append(b.bidderName).append(" &7-> &6").append(mgr.formatMoney(b.amount));
                    }
                    sendSync(plugin, sender, sb.toString());
                } catch (SQLException ex) {
                    sendSync(plugin, sender, lm.Auction_DbError.getMessage());
                }
            });
            return true;
        }
        case "payouts": {
            if (!resadmin) {
                lm.General_AdminOnly.sendMessage(sender);
                return true;
            }
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try {
                    List<PayoutRecord> list = mgr.getDatabase().loadRecentPayouts(20);
                    StringBuilder sb = new StringBuilder();
                    sb.append(lm.Auction_Payouts_Header.getMessage(list.size()));
                    for (PayoutRecord p : list) {
                        sb.append("\n&8#").append(p.id)
                                .append(" &7[A#").append(p.auctionId).append(" ")
                                .append(p.payoutType).append("] &f").append(p.recipientName)
                                .append(" &6").append(mgr.formatMoney(p.amount))
                                .append(" &7(").append(p.status).append(")");
                    }
                    sendSync(plugin, sender, sb.toString());
                } catch (SQLException ex) {
                    sendSync(plugin, sender, lm.Auction_DbError.getMessage());
                }
            });
            return true;
        }
        default:
            return false;
        }
    }

    private static void sendSync(Residence plugin, CommandSender sender, String message) {
        String colored = CMIChatColor.translate(message);
        if (sender instanceof Player) {
            ((Player) sender).getScheduler().run(plugin, t -> sender.sendMessage(colored), null);
        } else {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> sender.sendMessage(colored));
        }
    }

    private static Long parseLong(String raw) {
        try {
            return Long.parseLong(raw.replace(",", "").trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public void getLocale() {
        ConfigReader c = Residence.getInstance().getLocaleManager().getLocaleConfig();
        c.get("Description", "Open Residence Auction GUI or manage residence auctions.");
        c.get("Info", Arrays.asList(
                "&eUsage: &6/res auction &7- Open Auction Browser GUI",
                "&eUsage: &6/res auction create [residence] &7- Open terms & create auction",
                "&eUsage: &6/res auction bid <id> <amount> &7- Place a bid (with confirmation)",
                "&eUsage: &6/res auction cancel <id> &7- Cancel your auction (if no bids)",
                "&eUsage: &6/res auction setinc <id> <amount> &7- Update minimum bid increment",
                "&eUsage: &6/res auction ack &7- View & acknowledge auction notifications",
                "&eAdmin: &6/resadmin auction start <res> <startPrice> [minInc] [hours] [NONE|ITEMS_ONLY|ALL]",
                "&eAdmin: &6/resadmin auction payouts &7- Inspect Outbox payout logs"));
        LocaleManager.addTabCompleteMain(this, "gui%%create%%bid%%cancel%%setinc%%info%%bids%%ack%%start%%payouts");
    }
}
