package com.quickproducts.qtgreward;

import com.quickproducts.qtgreward.commands.AdminCommand;
import com.quickproducts.qtgreward.commands.TgCommand;
import com.quickproducts.qtgreward.config.ConfigManager;
import com.quickproducts.qtgreward.database.DatabaseManager;
import com.quickproducts.qtgreward.listeners.PlayerListener;
import com.quickproducts.qtgreward.rewards.RewardManager;
import com.quickproducts.qtgreward.services.LinkService;
import com.quickproducts.qtgreward.services.NotificationService;
import com.quickproducts.qtgreward.services.SubscriptionService;
import com.quickproducts.qtgreward.telegram.TelegramBotService;
import com.quickproducts.qtgreward.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class QuickTgRewardPlugin extends JavaPlugin {

    private static QuickTgRewardPlugin instance;

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private TelegramBotService telegramBotService;
    private LinkService linkService;
    private RewardManager rewardManager;
    private SubscriptionService subscriptionService;
    private NotificationService notificationService;
    private MessageUtil messageUtil;

    @Override
    public void onEnable() {
        instance = this;
        long start = System.currentTimeMillis();

        try {
            saveDefaultConfigs();
            configManager = new ConfigManager(this);
            configManager.loadAll();

            messageUtil = new MessageUtil(this);
            databaseManager = new DatabaseManager(this);
            databaseManager.init();

            notificationService = new NotificationService(this);
            subscriptionService = new SubscriptionService(this);
            linkService = new LinkService(this);
            rewardManager = new RewardManager(this);

            telegramBotService = new TelegramBotService(this);
            if (configManager.getTelegramConfig().getBoolean("bot.enabled", true)) {
                telegramBotService.start();
            }

            registerCommands();
            registerListeners();

            getLogger().info("QuickTgReward v" + getDescription().getVersion() + " enabled in " +
                    (System.currentTimeMillis() - start) + "ms");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable QuickTgReward", e);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (telegramBotService != null) {
            telegramBotService.stop();
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        getLogger().info("QuickTgReward disabled.");
    }

    private void saveDefaultConfigs() {
        saveResource("config.yml", false);
        saveResource("messages.yml", false);
        saveResource("telegram.yml", false);
        saveResource("rewards.yml", false);
        saveResource("gui.yml", false);
        saveResource("database.yml", false);
    }

    private void registerCommands() {
        TgCommand tgCommand = new TgCommand(this);
        getCommand("tg").setExecutor(tgCommand);
        getCommand("tg").setTabCompleter(tgCommand);

        AdminCommand adminCommand = new AdminCommand(this);
        getCommand("qtgreward").setExecutor(adminCommand);
        getCommand("qtgreward").setTabCompleter(adminCommand);
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
    }

    public void reload() {
        configManager.loadAll();
        rewardManager.reload();
        messageUtil.reload();
        if (telegramBotService != null) {
            telegramBotService.reload();
        }
    }

    public static QuickTgRewardPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public TelegramBotService getTelegramBotService() {
        return telegramBotService;
    }

    public LinkService getLinkService() {
        return linkService;
    }

    public RewardManager getRewardManager() {
        return rewardManager;
    }

    public SubscriptionService getSubscriptionService() {
        return subscriptionService;
    }

    public NotificationService getNotificationService() {
        return notificationService;
    }

    public MessageUtil getMessageUtil() {
        return messageUtil;
    }
}
