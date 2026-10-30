package com.quickproducts.qtgreward.database;

import com.quickproducts.qtgreward.QuickTgRewardPlugin;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class DatabaseManager {

    private final QuickTgRewardPlugin plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(QuickTgRewardPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        FileConfiguration dbCfg = plugin.getConfigManager().getDatabase();
        File dataFolder = new File(plugin.getDataFolder(), "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        String file = dbCfg.getString("sqlite.file", "data/qtgreward.db");
        File dbFile = new File(plugin.getDataFolder(), file);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(dbCfg.getInt("pool.maximum-pool-size", 10));
        config.setMinimumIdle(dbCfg.getInt("pool.minimum-idle", 2));
        config.setConnectionTimeout(dbCfg.getLong("pool.connection-timeout", 30000));
        config.setIdleTimeout(dbCfg.getLong("pool.idle-timeout", 600000));
        config.setMaxLifetime(dbCfg.getLong("pool.max-lifetime", 1800000));
        config.setPoolName("QuickTgReward-Pool");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");

        dataSource = new HikariDataSource(config);
        createTables();
        plugin.getLogger().info("Database initialized: " + dbFile.getName());
    }

    private void createTables() {
        String players = """
            CREATE TABLE IF NOT EXISTS qtg_players (
                uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                telegram_id BIGINT UNIQUE,
                telegram_username TEXT,
                linked_at BIGINT,
                rewards_count INTEGER DEFAULT 0,
                last_reward_at BIGINT DEFAULT 0,
                created_at BIGINT NOT NULL
            )
            """;
        String codes = """
            CREATE TABLE IF NOT EXISTS qtg_codes (
                code TEXT PRIMARY KEY,
                uuid TEXT NOT NULL,
                created_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL,
                used INTEGER DEFAULT 0,
                attempts INTEGER DEFAULT 0
            )
            """;
        String rewards = """
            CREATE TABLE IF NOT EXISTS qtg_rewards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                reward_id TEXT NOT NULL,
                claimed_at BIGINT NOT NULL,
                UNIQUE(uuid, reward_id)
            )
            """;
        String attempts = """
            CREATE TABLE IF NOT EXISTS qtg_attempts (
                telegram_id BIGINT PRIMARY KEY,
                attempts INTEGER DEFAULT 0,
                last_attempt BIGINT DEFAULT 0
            )
            """;

        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.execute(players);
            st.execute(codes);
            st.execute(rewards);
            st.execute(attempts);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create tables", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    // Player operations
    public CompletableFuture<Optional<PlayerData>> getPlayer(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM qtg_players WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapPlayer(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getPlayer failed", e);
            }
            return Optional.empty();
        });
    }

    public CompletableFuture<Optional<PlayerData>> getPlayerByTelegram(long telegramId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM qtg_players WHERE telegram_id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, telegramId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapPlayer(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getPlayerByTelegram failed", e);
            }
            return Optional.empty();
        });
    }

    public CompletableFuture<Void> savePlayer(PlayerData data) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO qtg_players (uuid, name, telegram_id, telegram_username, linked_at, rewards_count, last_reward_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    name = excluded.name,
                    telegram_id = excluded.telegram_id,
                    telegram_username = excluded.telegram_username,
                    linked_at = excluded.linked_at,
                    rewards_count = excluded.rewards_count,
                    last_reward_at = excluded.last_reward_at
                """;
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, data.uuid().toString());
                ps.setString(2, data.name());
                if (data.telegramId() != null) {
                    ps.setLong(3, data.telegramId());
                } else {
                    ps.setObject(3, null);
                }
                ps.setString(4, data.telegramUsername());
                if (data.linkedAt() != null) {
                    ps.setLong(5, data.linkedAt());
                } else {
                    ps.setObject(5, null);
                }
                ps.setInt(6, data.rewardsCount());
                ps.setLong(7, data.lastRewardAt());
                ps.setLong(8, data.createdAt());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "savePlayer failed", e);
            }
        });
    }

    public CompletableFuture<Void> unlinkPlayer(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE qtg_players SET telegram_id = NULL, telegram_username = NULL, linked_at = NULL WHERE uuid = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "unlinkPlayer failed", e);
            }
        });
    }

    // Codes
    public CompletableFuture<Void> saveCode(String code, UUID uuid, long expiresAt) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR REPLACE INTO qtg_codes (code, uuid, created_at, expires_at, used, attempts) VALUES (?, ?, ?, ?, 0, 0)";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, code);
                ps.setString(2, uuid.toString());
                ps.setLong(3, System.currentTimeMillis());
                ps.setLong(4, expiresAt);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "saveCode failed", e);
            }
        });
    }

    public CompletableFuture<Optional<CodeData>> getCode(String code) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM qtg_codes WHERE code = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, code.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(new CodeData(
                                rs.getString("code"),
                                UUID.fromString(rs.getString("uuid")),
                                rs.getLong("created_at"),
                                rs.getLong("expires_at"),
                                rs.getInt("used") == 1,
                                rs.getInt("attempts")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getCode failed", e);
            }
            return Optional.empty();
        });
    }

    public CompletableFuture<Void> markCodeUsed(String code) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE qtg_codes SET used = 1 WHERE code = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, code.toUpperCase());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "markCodeUsed failed", e);
            }
        });
    }

    public CompletableFuture<Void> incrementCodeAttempts(String code) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE qtg_codes SET attempts = attempts + 1 WHERE code = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, code.toUpperCase());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "incrementCodeAttempts failed", e);
            }
        });
    }

    // Rewards history
    public CompletableFuture<Boolean> hasClaimed(UUID uuid, String rewardId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT 1 FROM qtg_rewards WHERE uuid = ? AND reward_id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, rewardId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "hasClaimed failed", e);
            }
            return false;
        });
    }

    public CompletableFuture<Void> recordClaim(UUID uuid, String rewardId) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT OR IGNORE INTO qtg_rewards (uuid, reward_id, claimed_at) VALUES (?, ?, ?)";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, rewardId);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "recordClaim failed", e);
            }
        });
    }

    public CompletableFuture<Void> resetPlayerRewards(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM qtg_rewards WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE qtg_players SET rewards_count = 0, last_reward_at = 0 WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "resetPlayerRewards failed", e);
            }
        });
    }

    // Attempts rate limit
    public CompletableFuture<Integer> getAttempts(long telegramId) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT attempts, last_attempt FROM qtg_attempts WHERE telegram_id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, telegramId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long last = rs.getLong("last_attempt");
                        long window = 300_000L; // 5 min
                        if (System.currentTimeMillis() - last > window) {
                            return 0;
                        }
                        return rs.getInt("attempts");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "getAttempts failed", e);
            }
            return 0;
        });
    }

    public CompletableFuture<Void> incrementAttempts(long telegramId) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO qtg_attempts (telegram_id, attempts, last_attempt) VALUES (?, 1, ?)
                ON CONFLICT(telegram_id) DO UPDATE SET
                    attempts = CASE WHEN ? - last_attempt > 300000 THEN 1 ELSE attempts + 1 END,
                    last_attempt = ?
                """;
            long now = System.currentTimeMillis();
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, telegramId);
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "incrementAttempts failed", e);
            }
        });
    }

    private PlayerData mapPlayer(ResultSet rs) throws SQLException {
        Long tgId = rs.getObject("telegram_id") != null ? rs.getLong("telegram_id") : null;
        Long linked = rs.getObject("linked_at") != null ? rs.getLong("linked_at") : null;
        return new PlayerData(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("name"),
                tgId,
                rs.getString("telegram_username"),
                linked,
                rs.getInt("rewards_count"),
                rs.getLong("last_reward_at"),
                rs.getLong("created_at")
        );
    }

    public record PlayerData(
            UUID uuid,
            String name,
            Long telegramId,
            String telegramUsername,
            Long linkedAt,
            int rewardsCount,
            long lastRewardAt,
            long createdAt
    ) {
        public boolean isLinked() {
            return telegramId != null;
        }
    }

    public record CodeData(
            String code,
            UUID uuid,
            long createdAt,
            long expiresAt,
            boolean used,
            int attempts
    ) {
        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }

        public boolean isValid() {
            return !used && !isExpired();
        }
    }
}
