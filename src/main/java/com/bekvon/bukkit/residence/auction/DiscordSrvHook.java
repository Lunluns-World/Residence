package com.bekvon.bukkit.residence.auction;

import java.awt.Color;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Level;

import org.bukkit.Bukkit;

import com.bekvon.bukkit.residence.Residence;

import net.Zrips.CMILib.Colors.CMIChatColor;

/**
 * 透過純反射整合 DiscordSRV（選配依賴）。
 * 哪一台分流執行上架／喊價／取消／中止／結標，就由該分流的 DiscordSRV 機器人發送 Embed 通知。
 */
public class DiscordSrvHook {

    private final Residence plugin;
    private volatile boolean loggedUnavailable = false;

    public DiscordSrvHook(Residence plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        return plugin != null
                && plugin.getConfigManager().isAuctionDiscordEnabled()
                && Bukkit.getPluginManager().isPluginEnabled("DiscordSRV");
    }

    /**
     * 非同步發送拍賣事件 Embed 到設定的 DiscordSRV 頻道。
     */
    public void sendEmbedAsync(String title, String description, Color color, Map<String, String> fields) {
        if (!isAvailable()) {
            return;
        }
        String channelKey = plugin.getConfigManager().getAuctionDiscordChannel();
        if (channelKey == null || channelKey.trim().isEmpty()) {
            return;
        }
        String serverDisplay = stripColors(plugin.getConfigManager().getCrossServerDisplayName(plugin.getStorageServerName()));
        String cleanTitle = stripColors(title);
        String cleanDesc = stripColors(description);

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
                Class<?> srvClass = Class.forName("github.scarsz.discordsrv.DiscordSRV");
                Object srv = srvClass.getMethod("getPlugin").invoke(null);
                if (srv == null) {
                    return;
                }
                Object channel = resolveChannel(srv, srvClass, channelKey.trim());
                if (channel == null) {
                    if (!loggedUnavailable) {
                        loggedUnavailable = true;
                        plugin.getLogger().warning("[Auction] 找不到 DiscordSRV 頻道 '" + channelKey + "'，請確認 DiscordSRV Channels 設定或頻道 ID。");
                    }
                    return;
                }

                Object embed = buildEmbed(cleanTitle, cleanDesc, color, fields, serverDisplay);
                if (embed != null && trySendEmbed(channel, embed)) {
                    return;
                }
                // 若 JDA Embed 版本不符則退回純文字訊息
                StringBuilder plain = new StringBuilder();
                plain.append("**").append(cleanTitle).append("**");
                if (cleanDesc != null && !cleanDesc.isEmpty()) {
                    plain.append("\n").append(cleanDesc);
                }
                if (fields != null) {
                    for (Map.Entry<String, String> f : fields.entrySet()) {
                        plain.append("\n• ").append(stripColors(f.getKey())).append(": ").append(stripColors(f.getValue()));
                    }
                }
                Method sendMessage = channel.getClass().getMethod("sendMessage", CharSequence.class);
                Object action = sendMessage.invoke(channel, plain.toString());
                if (action != null) {
                    action.getClass().getMethod("queue").invoke(action);
                }
            } catch (Throwable ex) {
                plugin.getLogger().log(Level.FINE, "[Auction] 發送 DiscordSRV 通知失敗: " + ex.getMessage());
            }
        });
    }

    private Object resolveChannel(Object srv, Class<?> srvClass, String channelKey) {
        try {
            // 先嘗試依頻道名稱查找（DiscordSRV config Channels）
            Method byName = srvClass.getMethod("getDestinationTextChannelForGameChannelName", String.class);
            Object ch = byName.invoke(srv, channelKey);
            if (ch != null) {
                return ch;
            }
        } catch (Throwable ignored) {
        }
        try {
            // 若使用者直接填寫數字頻道 ID，透過 JDA 查找
            if (channelKey.matches("\\d{15,22}")) {
                Method getJda = srvClass.getMethod("getJda");
                Object jda = getJda.invoke(srv);
                if (jda != null) {
                    Method getTextChannelById = jda.getClass().getMethod("getTextChannelById", String.class);
                    Object ch = getTextChannelById.invoke(jda, channelKey);
                    if (ch != null) {
                        return ch;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Method main = srvClass.getMethod("getMainTextChannel");
            return main.invoke(srv);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object buildEmbed(String title, String description, Color color, Map<String, String> fields, String footerText) {
        String[] embedBuilderClasses = new String[] {
                "github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder",
                "net.dv8tion.jda.api.EmbedBuilder"
        };
        for (String clsName : embedBuilderClasses) {
            try {
                Class<?> ebClass = Class.forName(clsName);
                Object eb = ebClass.getDeclaredConstructor().newInstance();
                if (title != null && !title.isEmpty()) {
                    ebClass.getMethod("setTitle", String.class).invoke(eb, title);
                }
                if (description != null && !description.isEmpty()) {
                    ebClass.getMethod("setDescription", CharSequence.class).invoke(eb, description);
                }
                if (color != null) {
                    ebClass.getMethod("setColor", Color.class).invoke(eb, color);
                }
                if (fields != null) {
                    Method addField = ebClass.getMethod("addField", String.class, String.class, boolean.class);
                    for (Map.Entry<String, String> e : fields.entrySet()) {
                        addField.invoke(eb, stripColors(e.getKey()), stripColors(e.getValue()), true);
                    }
                }
                if (footerText != null && !footerText.isEmpty()) {
                    ebClass.getMethod("setFooter", String.class).invoke(eb, "分流：" + footerText);
                }
                ebClass.getMethod("setTimestamp", java.time.temporal.TemporalAccessor.class).invoke(eb, Instant.now());
                return ebClass.getMethod("build").invoke(eb);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private boolean trySendEmbed(Object channel, Object embed) {
        for (Method m : channel.getClass().getMethods()) {
            if (("sendMessageEmbeds".equals(m.getName()) || "sendMessage".equals(m.getName()))
                    && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(embed)) {
                try {
                    Object action = m.invoke(channel, embed);
                    if (action != null) {
                        action.getClass().getMethod("queue").invoke(action);
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // JDA v5 sendMessageEmbeds(MessageEmbed, MessageEmbed...)
        for (Method m : channel.getClass().getMethods()) {
            if ("sendMessageEmbeds".equals(m.getName()) && m.isVarArgs() && m.getParameterCount() == 2) {
                Class<?> embedType = m.getParameterTypes()[0];
                if (embedType.isInstance(embed)) {
                    try {
                        Object emptyVarArgs = java.lang.reflect.Array.newInstance(embedType, 0);
                        Object action = m.invoke(channel, embed, emptyVarArgs);
                        if (action != null) {
                            action.getClass().getMethod("queue").invoke(action);
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    private static String stripColors(String text) {
        if (text == null) {
            return "";
        }
        String stripped = CMIChatColor.stripColor(CMIChatColor.translate(text));
        return stripped == null ? text : stripped;
    }
}
