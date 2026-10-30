package com.quickproducts.qtgreward.utils;

import com.quickproducts.qtgreward.QuickTgRewardPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public class MessageUtil {

    private final QuickTgRewardPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private FileConfiguration messages;

    public MessageUtil(QuickTgRewardPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.messages = plugin.getConfigManager().getMessages();
    }

    public Component parse(String text) {
        if (text == null) return Component.empty();
        return miniMessage.deserialize(text);
    }

    public Component parse(String text, Map<String, String> placeholders) {
        if (text == null) return Component.empty();
        String result = text;
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                result = result.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
            }
        }
        return miniMessage.deserialize(result);
    }

    public String getRaw(String path) {
        return messages.getString(path, path);
    }

    public String getRaw(String path, Map<String, String> placeholders) {
        String text = getRaw(path);
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                text = text.replace("{" + e.getKey() + "}", e.getValue() != null ? e.getValue() : "");
            }
        }
        return text;
    }

    public List<String> getList(String path) {
        return messages.getStringList(path);
    }

    public void send(CommandSender sender, String path) {
        send(sender, path, null);
    }

    public void send(CommandSender sender, String path, Map<String, String> placeholders) {
        String prefix = plugin.getConfigManager().getPrefix() + " ";
        String raw = getRaw(path, placeholders);
        if (raw.contains("\n")) {
            for (String line : raw.split("\n")) {
                sender.sendMessage(parse(prefix + line));
            }
        } else {
            sender.sendMessage(parse(prefix + raw));
        }
    }

    public void sendRaw(CommandSender sender, String miniMessageText) {
        sender.sendMessage(parse(miniMessageText));
    }

    public void sendRaw(CommandSender sender, Component component) {
        sender.sendMessage(component);
    }

    public void sendActionBar(Player player, String path) {
        if (!plugin.getConfigManager().getConfig().getBoolean("notifications.actionbar", true)) return;
        player.sendActionBar(parse(getRaw(path)));
    }

    public Component getPrefix() {
        return parse(plugin.getConfigManager().getPrefix());
    }

    public static String formatTime(long seconds) {
        if (seconds <= 0) return "сейчас";
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long mins = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("д ");
        if (hours > 0) sb.append(hours).append("ч ");
        if (mins > 0) sb.append(mins).append("м ");
        if (secs > 0 || sb.isEmpty()) sb.append(secs).append("с");
        return sb.toString().trim();
    }
}
