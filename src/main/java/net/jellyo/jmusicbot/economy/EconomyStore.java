/*
 * Copyright 2026 Jellyo.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.economy;

import com.jagrosh.jmusicbot.database.Database;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed global, per-user economy storage (currency, XP, lifetime stats
 * and earned achievements). Lives in the unified {@link Database}. Users are
 * keyed solely by Discord snowflake, so the data follows a user across servers
 * and survives username changes.
 *
 * <p>All public methods are {@code synchronized} against the single owned
 * connection, mirroring the other stores in the project.
 */
public class EconomyStore
{
    private static final Logger LOG = LoggerFactory.getLogger(EconomyStore.class);

    private final Path dbPath;
    private Connection connection;

    public EconomyStore(Path dbPath)
    {
        this.dbPath = dbPath;
    }

    public synchronized void init() throws SQLException
    {
        connection = Database.open(dbPath);
        try(Statement st = connection.createStatement())
        {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS user_profiles ("
                    + "user_id INTEGER PRIMARY KEY,"
                    + "currency INTEGER NOT NULL DEFAULT 0,"
                    + "xp INTEGER NOT NULL DEFAULT 0,"
                    + "songs_requested INTEGER NOT NULL DEFAULT 0,"
                    + "ms_listened INTEGER NOT NULL DEFAULT 0,"
                    + "guesses_correct INTEGER NOT NULL DEFAULT 0,"
                    + "guess_wins INTEGER NOT NULL DEFAULT 0,"
                    + "games_played INTEGER NOT NULL DEFAULT 0,"
                    + "gamble_wins INTEGER NOT NULL DEFAULT 0,"
                    + "gamble_losses INTEGER NOT NULL DEFAULT 0,"
                    + "gamble_wagered INTEGER NOT NULL DEFAULT 0,"
                    + "gamble_net INTEGER NOT NULL DEFAULT 0,"
                    + "biggest_win INTEGER NOT NULL DEFAULT 0,"
                    + "rebate_accrued_today INTEGER NOT NULL DEFAULT 0,"
                    + "rebate_day INTEGER NOT NULL DEFAULT 0,"
                    + "daily_streak INTEGER NOT NULL DEFAULT 0,"
                    + "last_daily_at INTEGER NOT NULL DEFAULT 0,"
                    + "last_seen_at INTEGER NOT NULL DEFAULT 0,"
                    + "last_username TEXT,"
                    + "last_avatar TEXT,"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL"
                    + ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS user_achievements ("
                    + "user_id INTEGER NOT NULL,"
                    + "achievement_id TEXT NOT NULL,"
                    + "earned_at INTEGER NOT NULL,"
                    + "PRIMARY KEY(user_id, achievement_id)"
                    + ")");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_user_profiles_currency ON user_profiles(currency DESC)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_user_profiles_xp ON user_profiles(xp DESC)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_user_profiles_listened ON user_profiles(ms_listened DESC)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_user_achievements_user ON user_achievements(user_id, earned_at)");
        }
        // Idempotent column migration for databases created before these columns existed.
        // (CREATE TABLE above already includes them for fresh databases.)
        ensureColumn("user_profiles", "biggest_win", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn("user_profiles", "rebate_accrued_today", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn("user_profiles", "rebate_day", "INTEGER NOT NULL DEFAULT 0");
        LOG.info("Economy database ready in unified database at {}", dbPath.toAbsolutePath());
    }

    // ---- profile lifecycle -------------------------------------------------

    /** Inserts a bare row for the user if absent and refreshes cached display fields. */
    public synchronized void ensureProfile(long userId, String username, String avatar)
    {
        ensureRow(userId);
        try(PreparedStatement ps = connection.prepareStatement(
                "UPDATE user_profiles SET last_username=COALESCE(?, last_username), "
                        + "last_avatar=COALESCE(?, last_avatar), last_seen_at=?, updated_at=? WHERE user_id=?"))
        {
            ps.setString(1, username);
            ps.setString(2, avatar);
            long now = now();
            ps.setLong(3, now);
            ps.setLong(4, now);
            ps.setLong(5, userId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to ensure user profile", ex);
        }
    }

    public synchronized UserProfile getProfile(long userId)
    {
        ensureOpen();
        try(PreparedStatement ps = connection.prepareStatement("SELECT * FROM user_profiles WHERE user_id=?"))
        {
            ps.setLong(1, userId);
            try(ResultSet rs = ps.executeQuery())
            {
                if(rs.next())
                    return map(rs);
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to load user profile", ex);
        }
        return UserProfile.empty(userId);
    }

    // ---- currency ----------------------------------------------------------

    public synchronized long getBalance(long userId)
    {
        ensureOpen();
        try(PreparedStatement ps = connection.prepareStatement("SELECT currency FROM user_profiles WHERE user_id=?"))
        {
            ps.setLong(1, userId);
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to read balance", ex);
        }
    }

    /** Adds (or, with a negative delta, removes) currency, clamping at zero. Returns the new balance. */
    public synchronized long addCurrency(long userId, long delta)
    {
        ensureRow(userId);
        try(PreparedStatement ps = connection.prepareStatement(
                "UPDATE user_profiles SET currency=MAX(0, currency+?), updated_at=? WHERE user_id=?"))
        {
            ps.setLong(1, delta);
            ps.setLong(2, now());
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to adjust currency", ex);
        }
        return getBalance(userId);
    }

    /** Atomically deducts {@code amount} if the user can afford it. Returns true on success. */
    public synchronized boolean trySpend(long userId, long amount)
    {
        if(amount <= 0)
            return true;
        ensureRow(userId);
        try(PreparedStatement ps = connection.prepareStatement(
                "UPDATE user_profiles SET currency=currency-?, updated_at=? WHERE user_id=? AND currency>=?"))
        {
            ps.setLong(1, amount);
            ps.setLong(2, now());
            ps.setLong(3, userId);
            ps.setLong(4, amount);
            return ps.executeUpdate() > 0;
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to spend currency", ex);
        }
    }

    // ---- xp & counters -----------------------------------------------------

    public synchronized void addXp(long userId, long delta)
    {
        if(delta == 0)
            return;
        bump(userId, "xp", delta);
    }

    public synchronized void incrementSongsRequested(long userId, long delta)
    {
        bump(userId, "songs_requested", delta);
    }

    /** Adds listening time and returns the new lifetime total in milliseconds. */
    public synchronized long addMsListened(long userId, long deltaMs)
    {
        if(deltaMs > 0)
            bump(userId, "ms_listened", deltaMs);
        UserProfile p = getProfile(userId);
        return p.getMsListened();
    }

    public synchronized void incrementGuessesCorrect(long userId, long delta)
    {
        bump(userId, "guesses_correct", delta);
    }

    public synchronized void incrementGuessWins(long userId, long delta)
    {
        bump(userId, "guess_wins", delta);
    }

    public synchronized void incrementGamesPlayed(long userId, long delta)
    {
        bump(userId, "games_played", delta);
    }

    /** Records the statistical outcome of a gamble. Currency changes are applied separately. */
    public synchronized void recordGamble(long userId, long wagered, long net, boolean won)
    {
        ensureRow(userId);
        String column = won ? "gamble_wins" : "gamble_losses";
        try(PreparedStatement ps = connection.prepareStatement(
                "UPDATE user_profiles SET gamble_wagered=gamble_wagered+?, gamble_net=gamble_net+?, "
                        + column + "=" + column + "+1, updated_at=? WHERE user_id=?"))
        {
            ps.setLong(1, Math.max(0, wagered));
            ps.setLong(2, net);
            ps.setLong(3, now());
            ps.setLong(4, userId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to record gamble", ex);
        }
    }

    /** Raises the user's record single-round win to {@code win} if it is a new high. */
    public synchronized void updateBiggestWin(long userId, long win)
    {
        if(win <= 0)
            return;
        ensureRow(userId);
        try(PreparedStatement ps = connection.prepareStatement(
                "UPDATE user_profiles SET biggest_win=MAX(biggest_win, ?), updated_at=? WHERE user_id=?"))
        {
            ps.setLong(1, win);
            ps.setLong(2, now());
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to update biggest win", ex);
        }
    }

    /**
     * Credits a loyalty rebate, enforcing a per-day cap atomically. The accrual
     * resets when {@code dayKey} advances past the stored day. Returns the coins
     * actually granted (0 if the day's cap is already exhausted).
     */
    public synchronized long addRebateCapped(long userId, long rebate, long dayKey, long dailyCap)
    {
        if(rebate <= 0 || dailyCap <= 0)
            return 0;
        ensureRow(userId);
        long accrued;
        long storedDay;
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT rebate_accrued_today, rebate_day FROM user_profiles WHERE user_id=?"))
        {
            ps.setLong(1, userId);
            try(ResultSet rs = ps.executeQuery())
            {
                if(rs.next())
                {
                    accrued = rs.getLong(1);
                    storedDay = rs.getLong(2);
                }
                else
                {
                    accrued = 0;
                    storedDay = dayKey;
                }
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to read rebate accrual", ex);
        }
        if(storedDay != dayKey)
            accrued = 0; // new day resets the accrual
        long grant = Math.min(rebate, Math.max(0, dailyCap - accrued));
        if(grant <= 0)
            return 0;
        try(PreparedStatement ps = connection.prepareStatement(
                "UPDATE user_profiles SET currency=currency+?, rebate_accrued_today=?, rebate_day=?, "
                        + "updated_at=? WHERE user_id=?"))
        {
            ps.setLong(1, grant);
            ps.setLong(2, accrued + grant);
            ps.setLong(3, dayKey);
            ps.setLong(4, now());
            ps.setLong(5, userId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to credit rebate", ex);
        }
        return grant;
    }

    public synchronized void setDaily(long userId, long lastDailyAtEpoch, int streak)
    {
        ensureRow(userId);
        try(PreparedStatement ps = connection.prepareStatement(
                "UPDATE user_profiles SET last_daily_at=?, daily_streak=?, updated_at=? WHERE user_id=?"))
        {
            ps.setLong(1, lastDailyAtEpoch);
            ps.setInt(2, streak);
            ps.setLong(3, now());
            ps.setLong(4, userId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to record daily reward", ex);
        }
    }

    // ---- achievements ------------------------------------------------------

    public synchronized boolean hasAchievement(long userId, String achievementId)
    {
        ensureOpen();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM user_achievements WHERE user_id=? AND achievement_id=?"))
        {
            ps.setLong(1, userId);
            ps.setString(2, achievementId);
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next();
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to check achievement", ex);
        }
    }

    /** Grants an achievement. Returns true only if it was newly granted (not already held). */
    public synchronized boolean grantAchievement(long userId, String achievementId, long earnedAtEpoch)
    {
        ensureRow(userId);
        try(PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO user_achievements(user_id, achievement_id, earned_at) VALUES(?,?,?)"))
        {
            ps.setLong(1, userId);
            ps.setString(2, achievementId);
            ps.setLong(3, earnedAtEpoch);
            return ps.executeUpdate() > 0;
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to grant achievement", ex);
        }
    }

    public synchronized Set<String> earnedAchievementIds(long userId)
    {
        ensureOpen();
        Set<String> ids = new LinkedHashSet<>();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT achievement_id FROM user_achievements WHERE user_id=? ORDER BY earned_at ASC"))
        {
            ps.setLong(1, userId);
            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                    ids.add(rs.getString(1));
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to list achievements", ex);
        }
        return ids;
    }

    public synchronized List<EarnedAchievement> listAchievements(long userId)
    {
        ensureOpen();
        List<EarnedAchievement> list = new ArrayList<>();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT achievement_id, earned_at FROM user_achievements WHERE user_id=? ORDER BY earned_at ASC"))
        {
            ps.setLong(1, userId);
            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                    list.add(new EarnedAchievement(rs.getString(1), rs.getLong(2)));
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to list achievements", ex);
        }
        return list;
    }

    // ---- leaderboards ------------------------------------------------------

    public synchronized List<LeaderEntry> topBy(LeaderMetric metric, int limit)
    {
        ensureOpen();
        String column = metric.column;
        String sql = "SELECT user_id, last_username, last_avatar, " + column + " AS metric, xp "
                + "FROM user_profiles WHERE " + column + " > 0 "
                + "ORDER BY metric DESC, xp DESC LIMIT ?";
        List<LeaderEntry> entries = new ArrayList<>();
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setInt(1, Math.max(1, limit));
            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                    entries.add(new LeaderEntry(rs.getLong("user_id"), rs.getString("last_username"),
                            rs.getString("last_avatar"), rs.getLong("metric")));
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to query leaderboard", ex);
        }
        return entries;
    }

    /** 1-based rank of a user for the given metric (number of users strictly ahead + 1). */
    public synchronized int rankBy(LeaderMetric metric, long userId)
    {
        ensureOpen();
        String column = metric.column;
        String sql = "SELECT COUNT(*)+1 FROM user_profiles WHERE " + column + " > "
                + "(SELECT " + column + " FROM user_profiles WHERE user_id=?)";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, userId);
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to compute rank", ex);
        }
    }

    public synchronized int countProfiles()
    {
        ensureOpen();
        try(Statement st = connection.createStatement();
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM user_profiles"))
        {
            return rs.next() ? rs.getInt(1) : 0;
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to count profiles", ex);
        }
    }

    public synchronized void close()
    {
        if(connection == null)
            return;
        try
        {
            connection.close();
        }
        catch(SQLException ex)
        {
            LOG.warn("Failed to close economy database", ex);
        }
        finally
        {
            connection = null;
        }
    }

    // ---- internals ---------------------------------------------------------

    private void bump(long userId, String column, long delta)
    {
        ensureRow(userId);
        try(PreparedStatement ps = connection.prepareStatement(
                "UPDATE user_profiles SET " + column + "=MAX(0, " + column + "+?), updated_at=? WHERE user_id=?"))
        {
            ps.setLong(1, delta);
            ps.setLong(2, now());
            ps.setLong(3, userId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to update " + column, ex);
        }
    }

    /** Adds {@code column} to {@code table} if absent (idempotent schema migration). */
    private void ensureColumn(String table, String column, String ddl)
    {
        ensureOpen();
        if(hasColumn(table, column))
            return;
        try(Statement st = connection.createStatement())
        {
            // table/column/ddl are compile-time constants, never user input.
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to add column " + column + " to " + table, ex);
        }
    }

    private boolean hasColumn(String table, String column)
    {
        try(Statement st = connection.createStatement();
            ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(rs.next())
            {
                if(column.equalsIgnoreCase(rs.getString("name")))
                    return true;
            }
            return false;
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to inspect columns of " + table, ex);
        }
    }

    private void ensureRow(long userId)
    {
        ensureOpen();
        try(PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO user_profiles(user_id, created_at, updated_at) VALUES(?,?,?)"))
        {
            long now = now();
            ps.setLong(1, userId);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new EconomyException("Failed to create user profile", ex);
        }
    }

    private UserProfile map(ResultSet rs) throws SQLException
    {
        return UserProfile.builder(rs.getLong("user_id"))
                .currency(rs.getLong("currency"))
                .xp(rs.getLong("xp"))
                .songsRequested(rs.getLong("songs_requested"))
                .msListened(rs.getLong("ms_listened"))
                .guessesCorrect(rs.getLong("guesses_correct"))
                .guessWins(rs.getLong("guess_wins"))
                .gamesPlayed(rs.getLong("games_played"))
                .gambleWins(rs.getLong("gamble_wins"))
                .gambleLosses(rs.getLong("gamble_losses"))
                .gambleWagered(rs.getLong("gamble_wagered"))
                .gambleNet(rs.getLong("gamble_net"))
                .biggestWin(rs.getLong("biggest_win"))
                .dailyStreak(rs.getInt("daily_streak"))
                .lastDailyAt(rs.getLong("last_daily_at"))
                .lastSeenAt(rs.getLong("last_seen_at"))
                .username(rs.getString("last_username"))
                .avatar(rs.getString("last_avatar"))
                .createdAt(rs.getLong("created_at"))
                .build();
    }

    private void ensureOpen()
    {
        if(connection == null)
            throw new EconomyException("Economy database is not initialized");
    }

    private static long now()
    {
        return Instant.now().getEpochSecond();
    }

    public enum LeaderMetric
    {
        CURRENCY("currency"),
        XP("xp"),
        MINUTES_LISTENED("ms_listened"),
        SONGS_REQUESTED("songs_requested"),
        GUESS_WINS("guess_wins");

        private final String column;

        LeaderMetric(String column)
        {
            this.column = column;
        }
    }

    public static final class LeaderEntry
    {
        private final long userId;
        private final String username;
        private final String avatar;
        private final long value;

        public LeaderEntry(long userId, String username, String avatar, long value)
        {
            this.userId = userId;
            this.username = username;
            this.avatar = avatar;
            this.value = value;
        }

        public long getUserId() { return userId; }
        public String getUsername() { return username; }
        public String getAvatar() { return avatar; }
        public long getValue() { return value; }
    }

    public static final class EarnedAchievement
    {
        private final String achievementId;
        private final long earnedAt;

        public EarnedAchievement(String achievementId, long earnedAt)
        {
            this.achievementId = achievementId;
            this.earnedAt = earnedAt;
        }

        public String getAchievementId() { return achievementId; }
        public long getEarnedAt() { return earnedAt; }
    }

    public static class EconomyException extends RuntimeException
    {
        public EconomyException(String message)
        {
            super(message);
        }

        public EconomyException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
