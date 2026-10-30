package com.quickproducts.qtgreward.listeners;

import com.quickproducts.qtgreward.QuickTgRewardPlugin;
import com.quickproducts.qtgreward.database.DatabaseManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class PlayerListener implements Listener {

    private final QuickTgRewardPlugin plugin;

    public PlayerListener(QuickTgRewardPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Ensure player record exists / update name
        plugin.getDatabaseManager().getPlayer(event.getPlayer().getUniqueId()).thenAccept(opt -> {
            if (opt.isEmpty()) {
                DatabaseManager.PlayerData data = new DatabaseManager.PlayerData(
                        event.getPlayer().getUniqueId(),
                        event.getPlayer().getName(),
                        null, null, null, 0, 0, System.currentTimeMillis()
                );
                plugin.getDatabaseManager().savePlayer(data);
            } else if (!opt.get().name().equals(event.getPlayer().getName())) {
                DatabaseManager.PlayerData d = opt.get();
                DatabaseManager.PlayerData updated = new DatabaseManager.PlayerData(
                        d.uuid(), event.getPlayer().getName(), d.telegramId(), d.telegramUsername(),
                        d.linkedAt(), d.rewardsCount(), d.lastRewardAt(), d.createdAt()
                );
                plugin.getDatabaseManager().savePlayer(updated);
            }
        });
    }
}
