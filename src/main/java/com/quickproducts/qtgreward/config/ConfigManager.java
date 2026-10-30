package com.quickproducts.qtgreward.config;

import com.quickproducts.qtgreward.QuickTgRewardPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

public class ConfigManager {

    private final QuickTgRewardPlugin plugin;
    private FileConfiguration config;
    private FileConfiguration messages;
    private FileConfiguration telegram;
    private FileConfiguration rewards;
    private FileConfiguration gui;
    private FileConfiguration database;

    public ConfigManager(QuickTgRewardPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadAll() {
        config = load("config.yml");
        messages = load("messages.yml");
        telegram = load("telegram.yml");
        rewards = load("rewards.yml");
        gui = load("gui.yml");
        database = load("database.yml");
    }

    private FileConfiguration load(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        try (InputStream is = plugin.getResource(name)) {
            if (is != null) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(is, StandardCharsets.UTF_8));
                cfg.setDefaults(def);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not load defaults for " + name, e);
        }
        return cfg;
    }

    public void save(String name, FileConfiguration cfg) {
        try {
            cfg.save(new File(plugin.getDataFolder(), name));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + name, e);
        }
    }

    public FileConfiguration getConfig() {
        return config;
    }

    public FileConfiguration getMessages() {
        return messages;
    }

    public FileConfiguration getTelegramConfig() {
        return telegram;
    }

    public FileConfiguration getRewards() {
        return rewards;
    }

    public FileConfiguration getGui() {
        return gui;
    }

    public FileConfiguration getDatabase() {
        return database;
    }

    public String getPrefix() {
        return config.getString("plugin.prefix", "<gradient:#2563EB:#38BDF8>QuickTgReward</gradient>");
    }

    public boolean isDebug() {
        return config.getBoolean("plugin.debug", false);
    }
}
