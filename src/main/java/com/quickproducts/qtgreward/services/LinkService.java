package com.quickproducts.qtgreward.services;

import com.quickproducts.qtgreward.QuickTgRewardPlugin;
import com.quickproducts.qtgreward.database.DatabaseManager;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class LinkService {

    private final QuickTgRewardPlugin plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Long> lastCodeRequest = new ConcurrentHashMap<>();
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public LinkService(QuickTgRewardPlugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<String> generateCode(Player player) {
        if (!plugin.getConfigManager().getConfig().getBoolean("linking.enabled", true)) {
            return CompletableFuture.completedFuture(null);
        }

        int rateLimit = plugin.getConfigManager().getConfig().getInt("linking.rate-limit-seconds", 30);
        Long last = lastCodeRequest.get(player.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < rateLimit * 1000L) {
            return CompletableFuture.completedFuture("RATE_LIMIT");
        }

        return plugin.getDatabaseManager().getPlayer(player.getUniqueId()).thenCompose(opt -> {
            if (opt.isPresent() && opt.get().isLinked()) {
                return CompletableFuture.completedFuture("ALREADY_LINKED");
            }

            int length = plugin.getConfigManager().getConfig().getInt("linking.code-length", 8);
            int expiration = plugin.getConfigManager().getConfig().getInt("linking.code-expiration", 300);
            String code = generateRandomCode(length);
            long expires = System.currentTimeMillis() + expiration * 1000L;

            return plugin.getDatabaseManager().saveCode(code, player.getUniqueId(), expires)
                    .thenApply(v -> {
                        lastCodeRequest.put(player.getUniqueId(), System.currentTimeMillis());
                        DatabaseManager.PlayerData data = new DatabaseManager.PlayerData(
                                player.getUniqueId(),
                                player.getName(),
                                null, null, null, 0, 0, System.currentTimeMillis()
                        );
                        plugin.getDatabaseManager().savePlayer(data);
                        return code;
                    });
        });
    }

    public CompletableFuture<LinkResult> linkByCode(String code, long telegramId, String telegramUsername) {
        int maxAttempts = plugin.getConfigManager().getConfig().getInt("linking.max-attempts", 5);

        return plugin.getDatabaseManager().getAttempts(telegramId).thenCompose(attempts -> {
            if (attempts >= maxAttempts) {
                return CompletableFuture.completedFuture(LinkResult.RATE_LIMITED);
            }

            return plugin.getDatabaseManager().getCode(code).thenCompose(optCode -> {
                if (optCode.isEmpty()) {
                    plugin.getDatabaseManager().incrementAttempts(telegramId);
                    return CompletableFuture.completedFuture(LinkResult.INVALID_CODE);
                }
                DatabaseManager.CodeData codeData = optCode.get();
                if (!codeData.isValid()) {
                    plugin.getDatabaseManager().incrementAttempts(telegramId);
                    return CompletableFuture.completedFuture(codeData.used() ? LinkResult.ALREADY_USED : LinkResult.EXPIRED);
                }
                if (codeData.attempts() >= maxAttempts) {
                    return CompletableFuture.completedFuture(LinkResult.RATE_LIMITED);
                }

                boolean oneTgPerPlayer = plugin.getConfigManager().getConfig().getBoolean("linking.one-telegram-per-player", true);
                boolean onePlayerPerTg = plugin.getConfigManager().getConfig().getBoolean("linking.one-player-per-telegram", true);

                CompletableFuture<LinkResult> check = CompletableFuture.completedFuture(LinkResult.SUCCESS);

                if (onePlayerPerTg) {
                    check = plugin.getDatabaseManager().getPlayerByTelegram(telegramId).thenCompose(existing -> {
                        if (existing.isPresent()) {
                            return CompletableFuture.completedFuture(LinkResult.TELEGRAM_ALREADY_LINKED);
                        }
                        return CompletableFuture.completedFuture(LinkResult.SUCCESS);
                    });
                }

                return check.thenCompose(result -> {
                    if (result != LinkResult.SUCCESS) {
                        return CompletableFuture.completedFuture(result);
                    }

                    return plugin.getDatabaseManager().getPlayer(codeData.uuid()).thenCompose(playerOpt -> {
                        DatabaseManager.PlayerData playerData;
                        if (playerOpt.isPresent()) {
                            playerData = playerOpt.get();
                            if (oneTgPerPlayer && playerData.isLinked()) {
                                return CompletableFuture.completedFuture(LinkResult.PLAYER_ALREADY_LINKED);
                            }
                        } else {
                            playerData = new DatabaseManager.PlayerData(
                                    codeData.uuid(), "Unknown", null, null, null, 0, 0, System.currentTimeMillis()
                            );
                        }

                        DatabaseManager.PlayerData updated = new DatabaseManager.PlayerData(
                                playerData.uuid(),
                                playerData.name(),
                                telegramId,
                                telegramUsername,
                                System.currentTimeMillis(),
                                playerData.rewardsCount(),
                                playerData.lastRewardAt(),
                                playerData.createdAt()
                        );

                        return plugin.getDatabaseManager().savePlayer(updated)
                                .thenCompose(v -> plugin.getDatabaseManager().markCodeUsed(code))
                                .thenApply(v -> {
                                    LinkResult r = LinkResult.SUCCESS;
                                    r.player = updated;
                                    return r;
                                });
                    });
                });
            });
        });
    }

    public CompletableFuture<Boolean> unlink(UUID uuid) {
        return plugin.getDatabaseManager().unlinkPlayer(uuid).thenApply(v -> true);
    }

    private String generateRandomCode(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    public static class LinkResult {
        public static final LinkResult SUCCESS = new LinkResult("SUCCESS");
        public static final LinkResult INVALID_CODE = new LinkResult("INVALID_CODE");
        public static final LinkResult EXPIRED = new LinkResult("EXPIRED");
        public static final LinkResult ALREADY_USED = new LinkResult("ALREADY_USED");
        public static final LinkResult RATE_LIMITED = new LinkResult("RATE_LIMITED");
        public static final LinkResult TELEGRAM_ALREADY_LINKED = new LinkResult("TELEGRAM_ALREADY_LINKED");
        public static final LinkResult PLAYER_ALREADY_LINKED = new LinkResult("PLAYER_ALREADY_LINKED");

        private final String name;
        public DatabaseManager.PlayerData player;

        private LinkResult(String name) {
            this.name = name;
        }

        public boolean isSuccess() {
            return this == SUCCESS || (player != null);
        }
    }
}
