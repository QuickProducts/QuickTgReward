package com.quickproducts.qtgreward.commands;

import com.quickproducts.qtgreward.QuickTgRewardPlugin;
import com.quickproducts.qtgreward.database.DatabaseManager;
import com.quickproducts.qtgreward.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class AdminCommand implements CommandExecutor, TabCompleter {

    private final QuickTgRewardPlugin plugin;

    public AdminCommand(QuickTgRewardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("qtgreward.admin")) {
            plugin.getMessageUtil().send(sender, "admin.no-permission");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!sender.hasPermission("qtgreward.admin.reload")) {
                    plugin.getMessageUtil().send(sender, "admin.no-permission");
                    return true;
                }
                try {
                    plugin.reload();
                    plugin.getMessageUtil().send(sender, "admin.reload-success");
                } catch (Exception e) {
                    plugin.getMessageUtil().send(sender, "admin.reload-error", Map.of("error", e.getMessage()));
                }
            }
            case "status" -> {
                boolean bot = plugin.getTelegramBotService() != null && plugin.getTelegramBotService().isRunning();
                plugin.getMessageUtil().send(sender, bot ? "admin.status-online" : "admin.status-offline");
            }
            case "give" -> {
                if (!sender.hasPermission("qtgreward.admin.give") || args.length < 3) {
                    plugin.getMessageUtil().send(sender, "errors.invalid-args");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    plugin.getMessageUtil().send(sender, "admin.player-not-found");
                    return true;
                }
                plugin.getRewardManager().claim(target, args[2]).thenAccept(r -> {
                    if (r == com.quickproducts.qtgreward.rewards.RewardManager.ClaimResult.SUCCESS) {
                        plugin.getMessageUtil().send(sender, "admin.give-success",
                                Map.of("player", target.getName(), "reward", args[2]));
                    } else {
                        plugin.getMessageUtil().send(sender, "reward.error");
                    }
                });
            }
            case "reset" -> {
                if (!sender.hasPermission("qtgreward.admin.reset") || args.length < 2) {
                    plugin.getMessageUtil().send(sender, "errors.invalid-args");
                    return true;
                }
                OfflinePlayer off = Bukkit.getOfflinePlayer(args[1]);
                plugin.getDatabaseManager().resetPlayerRewards(off.getUniqueId()).thenAccept(v ->
                        plugin.getMessageUtil().send(sender, "admin.reset-success", Map.of("player", args[1])));
            }
            case "unlink" -> {
                if (!sender.hasPermission("qtgreward.admin.unlink") || args.length < 2) {
                    plugin.getMessageUtil().send(sender, "errors.invalid-args");
                    return true;
                }
                OfflinePlayer off = Bukkit.getOfflinePlayer(args[1]);
                plugin.getLinkService().unlink(off.getUniqueId()).thenAccept(v ->
                        plugin.getMessageUtil().send(sender, "admin.unlink-success", Map.of("player", args[1])));
            }
            case "check" -> {
                if (!sender.hasPermission("qtgreward.admin.check") || args.length < 2) {
                    plugin.getMessageUtil().send(sender, "errors.invalid-args");
                    return true;
                }
                OfflinePlayer off = Bukkit.getOfflinePlayer(args[1]);
                plugin.getDatabaseManager().getPlayer(off.getUniqueId()).thenAccept(opt -> {
                    plugin.getMessageUtil().send(sender, "admin.check-header", Map.of("player", args[1]));
                    if (opt.isEmpty()) {
                        sender.sendMessage("No data.");
                        return;
                    }
                    DatabaseManager.PlayerData d = opt.get();
                    sender.sendMessage("UUID: " + d.uuid());
                    sender.sendMessage("Linked: " + d.isLinked());
                    if (d.isLinked()) {
                        sender.sendMessage("Telegram ID: " + d.telegramId());
                        sender.sendMessage("Username: " + d.telegramUsername());
                    }
                    sender.sendMessage("Rewards: " + d.rewardsCount());
                    sender.sendMessage("Last reward: " + d.lastRewardAt());
                });
            }
            case "help" -> sendHelp(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§bQuickTgReward Admin");
        sender.sendMessage("§7/qtgreward reload §8— §fперезагрузить конфиг");
        sender.sendMessage("§7/qtgreward status §8— §fстатус бота");
        sender.sendMessage("§7/qtgreward give <player> <reward> §8— §fвыдать награду");
        sender.sendMessage("§7/qtgreward reset <player> §8— §fсбросить награды");
        sender.sendMessage("§7/qtgreward unlink <player> §8— §fотвязать");
        sender.sendMessage("§7/qtgreward check <player> §8— §fпроверить");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("qtgreward.admin")) return List.of();
        if (args.length == 1) {
            return Arrays.asList("reload", "help", "status", "give", "reset", "unlink", "check")
                    .stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && List.of("give", "reset", "unlink", "check").contains(args[0].toLowerCase())) {
            return null; // player names
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return plugin.getRewardManager().getRewards().stream()
                    .map(r -> r.id()).filter(s -> s.startsWith(args[2].toLowerCase())).toList();
        }
        return List.of();
    }
}
