package com.quickproducts.qtgreward.telegram;

import com.quickproducts.qtgreward.QuickTgRewardPlugin;
import com.quickproducts.qtgreward.database.DatabaseManager;
import com.quickproducts.qtgreward.rewards.RewardManager;
import com.quickproducts.qtgreward.services.LinkService;
import com.quickproducts.qtgreward.utils.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class TelegramBotService implements LongPollingSingleThreadUpdateConsumer {

    private final QuickTgRewardPlugin plugin;
    private TelegramClient client;
    private TelegramBotsLongPollingApplication botsApplication;
    private String token;
    private String username;
    private volatile boolean running;

    public TelegramBotService(QuickTgRewardPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        token = plugin.getConfigManager().getTelegramConfig().getString("bot.token", "");
        username = plugin.getConfigManager().getTelegramConfig().getString("bot.username", "");
        if (token == null || token.isBlank() || token.equals("YOUR_BOT_TOKEN_HERE")) {
            plugin.getLogger().warning("Telegram bot token not set. Bot will not start.");
            return;
        }
        try {
            client = new OkHttpTelegramClient(token);
            botsApplication = new TelegramBotsLongPollingApplication();
            botsApplication.registerBot(token, this);
            running = true;
            plugin.getLogger().info("Telegram bot started successfully.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start Telegram bot", e);
            running = false;
        }
    }

    public void stop() {
        running = false;
        if (botsApplication != null) {
            try {
                botsApplication.close();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error stopping bot", e);
            }
        }
    }

    public void reload() {
        stop();
        start();
    }

    public boolean isRunning() {
        return running;
    }

    public <T extends java.io.Serializable> T execute(BotApiMethod<T> method) throws TelegramApiException {
        if (client == null) throw new TelegramApiException("Client not initialized");
        return client.execute(method);
    }

    @Override
    public void consume(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleMessage(update.getMessage());
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Update processing error", e);
        }
    }

    private void handleMessage(Message message) {
        String text = message.getText().trim();
        long chatId = message.getChatId();
        long userId = message.getFrom().getId();
        String tgUsername = message.getFrom().getUserName();

        if (text.startsWith("/start")) {
            sendMainMenu(chatId);
        } else if (text.startsWith("/help")) {
            sendHelp(chatId);
        } else if (text.startsWith("/profile")) {
            sendProfile(chatId, userId);
        } else if (text.startsWith("/unlink")) {
            handleUnlink(chatId, userId);
        } else if (text.matches("^[A-Za-z0-9]{4,16}$")) {
            // Possible link code
            handleLinkCode(chatId, userId, tgUsername, text.toUpperCase());
        } else {
            sendMainMenu(chatId);
        }
    }

    private void handleCallback(CallbackQuery query) {
        String data = query.getData();
        long chatId = query.getMessage().getChatId();
        long userId = query.getFrom().getId();
        Integer messageId = query.getMessage().getMessageId();

        switch (data) {
            case "menu_main" -> editMainMenu(chatId, messageId);
            case "menu_profile" -> editProfile(chatId, messageId, userId);
            case "menu_help" -> editHelp(chatId, messageId);
            case "action_claim" -> handleClaim(chatId, messageId, userId);
            case "action_link" -> sendLinkInstructions(chatId);
            case "action_next" -> sendNextRewardInfo(chatId, userId);
            case "action_channel" -> sendChannelLink(chatId);
            case "action_check_sub" -> handleCheckSub(chatId, messageId, userId);
            case "action_unlink" -> confirmUnlink(chatId, messageId);
            case "action_unlink_confirm" -> handleUnlink(chatId, userId);
            default -> {}
        }
    }

    private void handleLinkCode(long chatId, long userId, String tgUsername, String code) {
        plugin.getLinkService().linkByCode(code, userId, tgUsername).thenAccept(result -> {
            String msg;
            if (result.isSuccess() && result.player != null) {
                msg = getTgMsg("link-success").replace("{player}", result.player.name());
                // Notify Minecraft
                plugin.getNotificationService().notifyOnlineRaw(result.player.uuid(),
                        "<gradient:#2563EB:#38BDF8>QuickTgReward</gradient> <green>Telegram успешно привязан!</green>");
            } else if (result == LinkService.LinkResult.RATE_LIMITED) {
                msg = getTgMsg("rate-limit").replace("{seconds}", "300");
            } else if (result == LinkService.LinkResult.TELEGRAM_ALREADY_LINKED) {
                msg = getTgMsg("already-linked");
            } else {
                msg = getTgMsg("link-failed");
            }
            sendHtml(chatId, msg);
        });
    }

    private void handleClaim(long chatId, Integer messageId, long userId) {
        plugin.getDatabaseManager().getPlayerByTelegram(userId).thenAccept(opt -> {
            if (opt.isEmpty() || !opt.get().isLinked()) {
                sendHtml(chatId, getTgMsg("not-linked"));
                return;
            }
            DatabaseManager.PlayerData data = opt.get();
            Player player = Bukkit.getPlayer(data.uuid());
            if (player == null || !player.isOnline()) {
                sendHtml(chatId, "Игрок должен быть онлайн на сервере для получения награды.");
                return;
            }

            plugin.getRewardManager().claim(player, "weekly").thenAccept(result -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result == RewardManager.ClaimResult.SUCCESS) {
                        sendHtml(chatId, getTgMsg("reward-success"));
                        plugin.getMessageUtil().send(player, "reward.success");
                    } else if (result == RewardManager.ClaimResult.COOLDOWN) {
                        sendHtml(chatId, getTgMsg("reward-cooldown")
                                .replace("{time}", MessageUtil.formatTime(result.remainingSeconds)));
                    } else if (result == RewardManager.ClaimResult.NOT_SUBSCRIBED) {
                        sendNotSubscribed(chatId);
                    } else if (result == RewardManager.ClaimResult.NOT_LINKED) {
                        sendHtml(chatId, getTgMsg("not-linked"));
                    } else {
                        sendHtml(chatId, getTgMsg("reward-error"));
                    }
                });
            });
        });
    }

    private void handleCheckSub(long chatId, Integer messageId, long userId) {
        plugin.getSubscriptionService().isSubscribed(userId).thenAccept(sub -> {
            if (sub) {
                sendHtml(chatId, getTgMsg("subscribed"));
            } else {
                sendNotSubscribed(chatId);
            }
        });
    }

    private void handleUnlink(long chatId, long userId) {
        plugin.getDatabaseManager().getPlayerByTelegram(userId).thenAccept(opt -> {
            if (opt.isEmpty()) {
                sendHtml(chatId, getTgMsg("not-linked"));
                return;
            }
            plugin.getLinkService().unlink(opt.get().uuid()).thenAccept(v -> {
                sendHtml(chatId, getTgMsg("unlink-success"));
                plugin.getNotificationService().notifyOnlineRaw(opt.get().uuid(),
                        "<yellow>Ваш Telegram-аккаунт был отвязан.</yellow>");
            });
        });
    }

    private void sendMainMenu(long chatId) {
        String text = getTgMsg("start");
        InlineKeyboardMarkup keyboard = buildMainKeyboard();
        sendHtml(chatId, text, keyboard);
    }

    private void editMainMenu(long chatId, Integer messageId) {
        String text = getTgMsg("start");
        InlineKeyboardMarkup keyboard = buildMainKeyboard();
        editHtml(chatId, messageId, text, keyboard);
    }

    private InlineKeyboardMarkup buildMainKeyboard() {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(row(btn(getBtn("claim-reward"), "action_claim")));
        rows.add(row(btn(getBtn("link-minecraft"), "action_link"), btn(getBtn("my-profile"), "menu_profile")));
        rows.add(row(btn(getBtn("next-reward"), "action_next"), btn(getBtn("our-channel"), "action_channel")));
        rows.add(row(btn(getBtn("help"), "menu_help")));
        return new InlineKeyboardMarkup(rows);
    }

    private void sendProfile(long chatId, long userId) {
        plugin.getDatabaseManager().getPlayerByTelegram(userId).thenAccept(opt -> {
            String text;
            if (opt.isEmpty() || !opt.get().isLinked()) {
                text = getTgMsg("not-linked");
            } else {
                DatabaseManager.PlayerData d = opt.get();
                long remaining = 0;
                var rewards = plugin.getRewardManager().getRewards();
                if (!rewards.isEmpty()) {
                    remaining = plugin.getRewardManager().getRemainingCooldown(d, rewards.iterator().next());
                }
                text = getTgMsg("profile")
                        .replace("{player}", d.name())
                        .replace("{status}", "Привязан")
                        .replace("{subscription}", "—")
                        .replace("{rewards_count}", String.valueOf(d.rewardsCount()))
                        .replace("{next_reward}", remaining > 0 ? MessageUtil.formatTime(remaining) : "доступна");
            }
            InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                    row(btn(getBtn("back"), "menu_main"), btn(getBtn("unlink"), "action_unlink"))
            ));
            sendHtml(chatId, text, kb);
        });
    }

    private void editProfile(long chatId, Integer messageId, long userId) {
        sendProfile(chatId, userId);
    }

    private void sendHelp(long chatId) {
        sendHtml(chatId, getTgMsg("help"), new InlineKeyboardMarkup(List.of(row(btn(getBtn("back"), "menu_main")))));
    }

    private void editHelp(long chatId, Integer messageId) {
        sendHelp(chatId);
    }

    private void sendLinkInstructions(long chatId) {
        sendHtml(chatId, "Чтобы привязать аккаунт:\n1. На сервере Minecraft введите /tg link\n2. Скопируйте код\n3. Отправьте его сюда боту.");
    }

    private void sendNextRewardInfo(long chatId, long userId) {
        plugin.getDatabaseManager().getPlayerByTelegram(userId).thenAccept(opt -> {
            if (opt.isEmpty()) {
                sendHtml(chatId, getTgMsg("not-linked"));
                return;
            }
            DatabaseManager.PlayerData d = opt.get();
            var rewards = plugin.getRewardManager().getRewards();
            if (rewards.isEmpty()) {
                sendHtml(chatId, "Награды не настроены.");
                return;
            }
            long rem = plugin.getRewardManager().getRemainingCooldown(d, rewards.iterator().next());
            sendHtml(chatId, getTgMsg("reward-cooldown").replace("{time}", MessageUtil.formatTime(rem)));
        });
    }

    private void sendChannelLink(long chatId) {
        String link = plugin.getConfigManager().getTelegramConfig().getString("channel.invite-link", "https://t.me/");
        sendHtml(chatId, "Наш канал: " + link);
    }

    private void sendNotSubscribed(long chatId) {
        String link = plugin.getConfigManager().getTelegramConfig().getString("channel.invite-link", "");
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                row(InlineKeyboardButton.builder().text(getBtn("subscribe")).url(link).build()),
                row(btn(getBtn("check-subscription"), "action_check_sub"))
        ));
        sendHtml(chatId, getTgMsg("not-subscribed"), kb);
    }

    private void confirmUnlink(long chatId, Integer messageId) {
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(
                row(btn(getBtn("confirm-unlink"), "action_unlink_confirm"), btn(getBtn("cancel"), "menu_main"))
        ));
        editHtml(chatId, messageId, "Вы уверены, что хотите отвязать аккаунт?", kb);
    }

    private String getTgMsg(String key) {
        return plugin.getConfigManager().getTelegramConfig().getString("messages." + key, key);
    }

    private String getBtn(String key) {
        return plugin.getConfigManager().getTelegramConfig().getString("buttons." + key, key);
    }

    private InlineKeyboardButton btn(String text, String data) {
        return InlineKeyboardButton.builder().text(text).callbackData(data).build();
    }

    private InlineKeyboardRow row(InlineKeyboardButton... buttons) {
        InlineKeyboardRow row = new InlineKeyboardRow();
        for (InlineKeyboardButton b : buttons) row.add(b);
        return row;
    }

    private void sendHtml(long chatId, String text) {
        sendHtml(chatId, text, null);
    }

    private void sendHtml(long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            SendMessage.SendMessageBuilder builder = SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML");
            if (keyboard != null) builder.replyMarkup(keyboard);
            client.execute(builder.build());
        } catch (TelegramApiException e) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().log(Level.WARNING, "Send failed", e);
            }
        }
    }

    private void editHtml(long chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard) {
        try {
            EditMessageText.EditMessageTextBuilder builder = EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .parseMode("HTML");
            if (keyboard != null) builder.replyMarkup(keyboard);
            client.execute(builder.build());
        } catch (TelegramApiException e) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().log(Level.WARNING, "Edit failed", e);
            }
        }
    }
}
