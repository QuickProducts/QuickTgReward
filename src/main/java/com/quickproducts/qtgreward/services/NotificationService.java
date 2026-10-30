package com.quickproducts.qtgreward.services;

import com.quickproducts.qtgreward.QuickTgRewardPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.UUID;

public class NotificationService {

    private final QuickTgRewardPlugin plugin;

    public NotificationService(QuickTgRewardPlugin plugin) {
        this.plugin = plugin;
    }

    public void playSound(Player player, String key) {
        if (!plugin.getConfigManager().getConfig().getBoolean("notifications.sounds", true)) return;
        String soundName = plugin.getConfigManager().getConfig().getString("sounds." + key, "ENTITY_PLAYER_LEVELUP");
        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException ignored) {
        }
    }

    public void notifyPlayer(Player player, String messagePath) {
        if (!plugin.getConfigManager().getConfig().getBoolean("notifications.minecraft", true)) return;
        plugin.getMessageUtil().send(player, messagePath);
    }

    public void notifyOnline(UUID uuid, String messagePath) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getMessageUtil().send(player, messagePath);
                playSound(player, "success");
            });
        }
    }

    public void notifyOnlineRaw(UUID uuid, String miniMessage) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.getMessageUtil().sendRaw(player, miniMessage);
                playSound(player, "link");
            });
        }
    }
}
