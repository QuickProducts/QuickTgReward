package com.quickproducts.qtgreward.commands;

import com.quickproducts.qtgreward.QuickTgRewardPlugin;
import com.quickproducts.qtgreward.database.DatabaseManager;
import com.quickproducts.qtgreward.rewards.RewardManager;
import com.quickproducts.qtgreward.utils.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TgCommand implements CommandExecutor, TabCompleter {

    private final QuickTgRewardPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public TgCommand(QuickTgRewardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!player.hasPermission("qtgreward.use")) {
            plugin.getMessageUtil().send(player, "admin.no-permission");
            return true;
        }

        if (args.length == 0) {
            showMenu(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "link" -> handleLink(player);
            case "unlink" -> handleUnlink(player, args);
            case "reward" -> handleReward(player);
            case "status" -> handleStatus(player);
            case "help" -> handleHelp(player);
            default -> showMenu(player);
        }
        return true;
    }

    private void showMenu(Player player) {
        plugin.getDatabaseManager().getPlayer(player.getUniqueId()).thenAccept(opt -> {
            DatabaseManager.PlayerData data = opt.orElse(null);
            boolean linked = data != null && data.isLinked();
            String status = linked
                    ? plugin.getMessageUtil().getRaw("menu.linked-status", Map.of("telegram", data.telegramUsername() != null ? data.telegramUsername() : String.valueOf(data.telegramId())))
                    : plugin.getMessageUtil().getRaw("menu.not-linked-status");
            String rewardStatus = plugin.getMessageUtil().getRaw("menu.reward-need-link");
            String desc = linked
                    ? plugin.getMessageUtil().getRaw("menu.description-linked")
                    : plugin.getMessageUtil().getRaw("menu.description-not-linked");

            if (linked) {
                var rewards = plugin.getRewardManager().getRewards();
                if (!rewards.isEmpty()) {
                    long rem = plugin.getRewardManager().getRemainingCooldown(data, rewards.iterator().next());
                    if (rem > 0) {
                        rewardStatus = plugin.getMessageUtil().getRaw("menu.reward-cooldown", Map.of("time", MessageUtil.formatTime(rem)));
                    } else {
                        rewardStatus = plugin.getMessageUtil().getRaw("menu.reward-available");
                    }
                }
            }

            String header = plugin.getMessageUtil().getRaw("menu.header", Map.of(
                    "status", status,
                    "reward_status", rewardStatus,
                    "description", desc
            ));

            Component menu = mm.deserialize(header);
            String botUser = plugin.getConfigManager().getTelegramConfig().getString("bot.username", "bot");
            String botLink = "https://t.me/" + botUser;

            Component linkBtn = mm.deserialize(plugin.getMessageUtil().getRaw("buttons.link"))
                    .clickEvent(ClickEvent.runCommand("/tg link"))
                    .hoverEvent(HoverEvent.showText(mm.deserialize(plugin.getMessageUtil().getRaw("buttons.hover-link"))));
            Component tgBtn = mm.deserialize(plugin.getMessageUtil().getRaw("buttons.open-telegram"))
                    .clickEvent(ClickEvent.openUrl(botLink))
                    .hoverEvent(HoverEvent.showText(mm.deserialize(plugin.getMessageUtil().getRaw("buttons.hover-telegram"))));

            Component buttons = Component.empty()
                    .append(linkBtn)
                    .append(Component.space())
                    .append(tgBtn);

            if (linked) {
                Component claimBtn = mm.deserialize(plugin.getMessageUtil().getRaw("buttons.claim"))
                        .clickEvent(ClickEvent.runCommand("/tg reward"))
                        .hoverEvent(HoverEvent.showText(mm.deserialize(plugin.getMessageUtil().getRaw("buttons.hover-claim"))));
                Component statusBtn = mm.deserialize(plugin.getMessageUtil().getRaw("buttons.status"))
                        .clickEvent(ClickEvent.runCommand("/tg status"))
                        .hoverEvent(HoverEvent.showText(mm.deserialize(plugin.getMessageUtil().getRaw("buttons.hover-status"))));
                buttons = Component.empty()
                        .append(claimBtn).append(Component.space())
                        .append(statusBtn).append(Component.space())
                        .append(tgBtn);
            }

            Component finalMsg = menu.append(Component.newline()).append(buttons);
            player.sendMessage(finalMsg);
        });
    }

    private void handleLink(Player player) {
        if (!player.hasPermission("qtgreward.link")) {
            plugin.getMessageUtil().send(player, "admin.no-permission");
            return;
        }
        plugin.getLinkService().generateCode(player).thenAccept(code -> {
            if (code == null) {
                plugin.getMessageUtil().send(player, "link.disabled");
            } else if ("RATE_LIMIT".equals(code)) {
                int sec = plugin.getConfigManager().getConfig().getInt("linking.rate-limit-seconds", 30);
                plugin.getMessageUtil().send(player, "link.rate-limited", Map.of("seconds", String.valueOf(sec)));
            } else if ("ALREADY_LINKED".equals(code)) {
                plugin.getMessageUtil().send(player, "link.already-linked");
            } else {
                int mins = plugin.getConfigManager().getConfig().getInt("linking.code-expiration", 300) / 60;
                plugin.getMessageUtil().send(player, "link.generated", Map.of("code", code, "minutes", String.valueOf(mins)));
                plugin.getNotificationService().playSound(player, "link");
            }
        });
    }

    private void handleUnlink(Player player, String[] args) {
        if (!player.hasPermission("qtgreward.unlink")) {
            plugin.getMessageUtil().send(player, "admin.no-permission");
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            plugin.getMessageUtil().send(player, "unlink.confirm");
            return;
        }
        plugin.getDatabaseManager().getPlayer(player.getUniqueId()).thenAccept(opt -> {
            if (opt.isEmpty() || !opt.get().isLinked()) {
                plugin.getMessageUtil().send(player, "unlink.not-linked");
                return;
            }
            plugin.getLinkService().unlink(player.getUniqueId()).thenAccept(v -> {
                plugin.getMessageUtil().send(player, "unlink.success");
                plugin.getNotificationService().playSound(player, "error");
            });
        });
    }

    private void handleReward(Player player) {
        if (!player.hasPermission("qtgreward.reward")) {
            plugin.getMessageUtil().send(player, "admin.no-permission");
            return;
        }
        plugin.getRewardManager().claim(player, "weekly").thenAccept(result -> {
            switch (result.name) {
                case "SUCCESS" -> plugin.getMessageUtil().send(player, "reward.success");
                case "COOLDOWN" -> plugin.getMessageUtil().send(player, "reward.cooldown",
                        Map.of("time", MessageUtil.formatTime(result.remainingSeconds)));
                case "NOT_LINKED" -> plugin.getMessageUtil().send(player, "reward.not-linked");
                case "NOT_SUBSCRIBED" -> plugin.getMessageUtil().send(player, "reward.not-subscribed");
                case "DISABLED" -> plugin.getMessageUtil().send(player, "reward.disabled");
                case "ALREADY_CLAIMED" -> plugin.getMessageUtil().send(player, "reward.already-claimed");
                default -> plugin.getMessageUtil().send(player, "reward.error");
            }
        });
    }

    private void handleStatus(Player player) {
        if (!player.hasPermission("qtgreward.status")) {
            plugin.getMessageUtil().send(player, "admin.no-permission");
            return;
        }
        plugin.getDatabaseManager().getPlayer(player.getUniqueId()).thenAccept(opt -> {
            DatabaseManager.PlayerData data = opt.orElse(new DatabaseManager.PlayerData(
                    player.getUniqueId(), player.getName(), null, null, null, 0, 0, System.currentTimeMillis()));
            String linked = data.isLinked()
                    ? "<green>Привязан</green> (" + (data.telegramUsername() != null ? data.telegramUsername() : data.telegramId()) + ")"
                    : "<red>Не привязан</red>";
            long rem = 0;
            var rewards = plugin.getRewardManager().getRewards();
            if (!rewards.isEmpty() && data.isLinked()) {
                rem = plugin.getRewardManager().getRemainingCooldown(data, rewards.iterator().next());
            }
            String next = rem > 0 ? MessageUtil.formatTime(rem) : plugin.getMessageUtil().getRaw("status.available-now");

            plugin.getMessageUtil().sendRaw(player, plugin.getMessageUtil().getRaw("status.header"));
            plugin.getMessageUtil().sendRaw(player, plugin.getMessageUtil().getRaw("status.player", Map.of("player", player.getName())));
            plugin.getMessageUtil().sendRaw(player, plugin.getMessageUtil().getRaw("status.uuid", Map.of("uuid", player.getUniqueId().toString())));
            plugin.getMessageUtil().sendRaw(player, plugin.getMessageUtil().getRaw("status.linked", Map.of("status", linked)));
            plugin.getMessageUtil().sendRaw(player, plugin.getMessageUtil().getRaw("status.rewards", Map.of("count", String.valueOf(data.rewardsCount()))));
            plugin.getMessageUtil().sendRaw(player, plugin.getMessageUtil().getRaw("status.next", Map.of("time", next)));
        });
    }

    private void handleHelp(Player player) {
        plugin.getMessageUtil().sendRaw(player, plugin.getMessageUtil().getRaw("help.header"));
        for (String line : plugin.getMessageUtil().getList("help.lines")) {
            plugin.getMessageUtil().sendRaw(player, line);
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> list = new ArrayList<>(List.of("link", "unlink", "reward", "status", "help"));
            return list.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unlink")) {
            return List.of("confirm");
        }
        return List.of();
    }
}
