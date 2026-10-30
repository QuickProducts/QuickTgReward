package com.quickproducts.qtgreward.services;

import com.quickproducts.qtgreward.QuickTgRewardPlugin;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class SubscriptionService {

    private final QuickTgRewardPlugin plugin;
    private static final Set<String> VALID_STATUSES = Set.of("creator", "administrator", "member", "restricted");

    public SubscriptionService(QuickTgRewardPlugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Boolean> isSubscribed(long telegramId) {
        if (!plugin.getConfigManager().getTelegramConfig().getBoolean("channel.required", true)) {
            return CompletableFuture.completedFuture(true);
        }
        String channelId = plugin.getConfigManager().getTelegramConfig().getString("channel.id", "");
        if (channelId.isEmpty() || channelId.equals("@your_channel")) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (plugin.getTelegramBotService() == null || !plugin.getTelegramBotService().isRunning()) {
                    return false;
                }
                GetChatMember method = new GetChatMember(channelId, telegramId);
                ChatMember member = plugin.getTelegramBotService().execute(method);
                if (member == null) return false;
                String status = member.getStatus();
                return VALID_STATUSES.contains(status);
            } catch (TelegramApiException e) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().warning("Subscription check failed: " + e.getMessage());
                }
                return false;
            } catch (Exception e) {
                plugin.getLogger().warning("Subscription check error: " + e.getMessage());
                return false;
            }
        });
    }
}
