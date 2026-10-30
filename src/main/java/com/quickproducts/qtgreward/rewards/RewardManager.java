package com.quickproducts.qtgreward.rewards;

import com.quickproducts.qtgreward.QuickTgRewardPlugin;
import com.quickproducts.qtgreward.database.DatabaseManager;
import com.quickproducts.qtgreward.utils.MessageUtil;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public class RewardManager {

    private final QuickTgRewardPlugin plugin;
    private final Map<String, RewardDefinition> rewards = new LinkedHashMap<>();

    public RewardManager(QuickTgRewardPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        rewards.clear();
        ConfigurationSection section = plugin.getConfigManager().getRewards().getConfigurationSection("rewards");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection r = section.getConfigurationSection(key);
            if (r == null || !r.getBoolean("enabled", true)) continue;
            rewards.put(key, new RewardDefinition(
                    key,
                    r.getString("display-name", key),
                    r.getLong("cooldown", plugin.getConfigManager().getConfig().getLong("rewards.default-cooldown", 604800)),
                    r.getBoolean("one-time", false),
                    r.getBoolean("require-subscription", true),
                    r.getString("messages.success", ""),
                    r.getString("messages.telegram", ""),
                    r.getList("actions")
            ));
        }
        plugin.getLogger().info("Loaded " + rewards.size() + " rewards");
    }

    public Collection<RewardDefinition> getRewards() {
        return rewards.values();
    }

    public Optional<RewardDefinition> getReward(String id) {
        return Optional.ofNullable(rewards.get(id));
    }

    public CompletableFuture<ClaimResult> claim(Player player, String rewardId) {
        if (!plugin.getConfigManager().getConfig().getBoolean("rewards.enabled", true)) {
            return CompletableFuture.completedFuture(ClaimResult.DISABLED);
        }

        RewardDefinition reward = rewards.get(rewardId);
        if (reward == null) {
            // Try first available
            reward = rewards.values().stream().findFirst().orElse(null);
            if (reward == null) return CompletableFuture.completedFuture(ClaimResult.NO_REWARDS);
        }

        final RewardDefinition finalReward = reward;
        UUID uuid = player.getUniqueId();

        return plugin.getDatabaseManager().getPlayer(uuid).thenCompose(opt -> {
            if (opt.isEmpty() || !opt.get().isLinked()) {
                if (plugin.getConfigManager().getConfig().getBoolean("rewards.require-linked-account", true)) {
                    return CompletableFuture.completedFuture(ClaimResult.NOT_LINKED);
                }
            }

            DatabaseManager.PlayerData data = opt.orElse(new DatabaseManager.PlayerData(
                    uuid, player.getName(), null, null, null, 0, 0, System.currentTimeMillis()));

            if (finalReward.oneTime()) {
                return plugin.getDatabaseManager().hasClaimed(uuid, finalReward.id()).thenCompose(claimed -> {
                    if (claimed) return CompletableFuture.completedFuture(ClaimResult.ALREADY_CLAIMED);
                    return doClaim(player, data, finalReward);
                });
            }

            long now = System.currentTimeMillis();
            long cooldownMs = finalReward.cooldown() * 1000L;
            if (data.lastRewardAt() > 0 && now - data.lastRewardAt() < cooldownMs) {
                long remaining = (cooldownMs - (now - data.lastRewardAt())) / 1000;
                ClaimResult r = ClaimResult.COOLDOWN;
                r.remainingSeconds = remaining;
                return CompletableFuture.completedFuture(r);
            }

            if (finalReward.requireSubscription() && data.isLinked()) {
                return plugin.getSubscriptionService().isSubscribed(data.telegramId()).thenCompose(sub -> {
                    if (!sub) return CompletableFuture.completedFuture(ClaimResult.NOT_SUBSCRIBED);
                    return doClaim(player, data, finalReward);
                });
            }

            return doClaim(player, data, finalReward);
        });
    }

    private CompletableFuture<ClaimResult> doClaim(Player player, DatabaseManager.PlayerData data, RewardDefinition reward) {
        // Record first to prevent double claim
        return plugin.getDatabaseManager().recordClaim(player.getUniqueId(), reward.id())
                .thenCompose(v -> {
                    DatabaseManager.PlayerData updated = new DatabaseManager.PlayerData(
                            data.uuid(), data.name(), data.telegramId(), data.telegramUsername(),
                            data.linkedAt(), data.rewardsCount() + 1, System.currentTimeMillis(), data.createdAt()
                    );
                    return plugin.getDatabaseManager().savePlayer(updated);
                })
                .thenApply(v -> {
                    // Execute on main thread
                    Bukkit.getScheduler().runTask(plugin, () -> executeActions(player, reward.actions()));
                    plugin.getNotificationService().playSound(player, "reward");
                    return ClaimResult.SUCCESS;
                });
    }

    @SuppressWarnings("unchecked")
    private void executeActions(Player player, List<?> actions) {
        if (actions == null) return;
        for (Object obj : actions) {
            if (!(obj instanceof Map)) continue;
            Map<String, Object> action = (Map<String, Object>) obj;
            String type = String.valueOf(action.getOrDefault("type", "")).toUpperCase();
            try {
                switch (type) {
                    case "COMMAND" -> {
                        List<String> cmds = (List<String>) action.get("commands");
                        boolean console = Boolean.TRUE.equals(action.get("run-as-console"));
                        if (cmds != null) {
                            for (String cmd : cmds) {
                                String parsed = cmd.replace("{player}", player.getName())
                                        .replace("{uuid}", player.getUniqueId().toString());
                                if (console) {
                                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
                                } else {
                                    player.performCommand(parsed);
                                }
                            }
                        }
                    }
                    case "ITEM" -> {
                        String mat = String.valueOf(action.getOrDefault("material", "STONE"));
                        int amount = ((Number) action.getOrDefault("amount", 1)).intValue();
                        Material material = Material.matchMaterial(mat);
                        if (material != null) {
                            ItemStack item = new ItemStack(material, amount);
                            if (action.containsKey("name")) {
                                ItemMeta meta = item.getItemMeta();
                                if (meta != null) {
                                    meta.displayName(MiniMessage.miniMessage().deserialize(String.valueOf(action.get("name"))));
                                    item.setItemMeta(meta);
                                }
                            }
                            player.getInventory().addItem(item);
                        }
                    }
                    case "EXP" -> {
                        int amount = ((Number) action.getOrDefault("amount", 0)).intValue();
                        player.giveExp(amount);
                    }
                    case "MONEY" -> {
                        // Vault soft depend
                        double amount = ((Number) action.getOrDefault("amount", 0)).doubleValue();
                        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
                            try {
                                net.milkbowl.vault.economy.Economy econ = Bukkit.getServicesManager()
                                        .getRegistration(net.milkbowl.vault.economy.Economy.class).getProvider();
                                econ.depositPlayer(player, amount);
                            } catch (Exception e) {
                                plugin.getLogger().log(Level.WARNING, "Vault money failed", e);
                            }
                        }
                    }
                    case "PERMISSION" -> {
                        String perm = String.valueOf(action.get("permission"));
                        Object duration = action.get("duration");
                        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
                            try {
                                net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
                                api.getUserManager().modifyUser(player.getUniqueId(), user -> {
                                    var node = net.luckperms.api.node.Node.builder(perm);
                                    if (duration instanceof Number n && n.longValue() > 0) {
                                        node.expiry(n.longValue());
                                    }
                                    user.data().add(node.build());
                                });
                            } catch (Exception e) {
                                plugin.getLogger().log(Level.WARNING, "LuckPerms failed", e);
                            }
                        }
                    }
                    case "RANDOM" -> {
                        List<Map<String, Object>> options = (List<Map<String, Object>>) action.get("options");
                        if (options != null && !options.isEmpty()) {
                            int totalWeight = options.stream()
                                    .mapToInt(o -> ((Number) o.getOrDefault("weight", 1)).intValue()).sum();
                            int roll = ThreadLocalRandom.current().nextInt(totalWeight);
                            int current = 0;
                            for (Map<String, Object> opt : options) {
                                current += ((Number) opt.getOrDefault("weight", 1)).intValue();
                                if (roll < current) {
                                    executeActions(player, (List<?>) opt.get("actions"));
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Action execution failed: " + type, e);
            }
        }
    }

    public long getRemainingCooldown(DatabaseManager.PlayerData data, RewardDefinition reward) {
        if (reward.oneTime()) return 0;
        long elapsed = System.currentTimeMillis() - data.lastRewardAt();
        long cooldownMs = reward.cooldown() * 1000L;
        if (elapsed >= cooldownMs) return 0;
        return (cooldownMs - elapsed) / 1000;
    }

    public record RewardDefinition(
            String id,
            String displayName,
            long cooldown,
            boolean oneTime,
            boolean requireSubscription,
            String successMessage,
            String telegramMessage,
            List<?> actions
    ) {}

    public static class ClaimResult {
        public static final ClaimResult SUCCESS = new ClaimResult("SUCCESS");
        public static final ClaimResult DISABLED = new ClaimResult("DISABLED");
        public static final ClaimResult NOT_LINKED = new ClaimResult("NOT_LINKED");
        public static final ClaimResult NOT_SUBSCRIBED = new ClaimResult("NOT_SUBSCRIBED");
        public static final ClaimResult COOLDOWN = new ClaimResult("COOLDOWN");
        public static final ClaimResult ALREADY_CLAIMED = new ClaimResult("ALREADY_CLAIMED");
        public static final ClaimResult NO_REWARDS = new ClaimResult("NO_REWARDS");
        public static final ClaimResult ERROR = new ClaimResult("ERROR");

        public final String name;
        public long remainingSeconds;

        private ClaimResult(String name) {
            this.name = name;
        }
    }
}
