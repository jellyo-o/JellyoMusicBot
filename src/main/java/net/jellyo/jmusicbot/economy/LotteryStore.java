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
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-guild lottery storage in the unified database: one open round per guild plus
 * its tickets. The database is the source of truth for the draw schedule (a bare
 * {@code ScheduledFuture} would drop draws on restart), so a due draw is resolved
 * by {@link #resolveDraw} — which picks the winner, credits them and closes the
 * round <b>in a single transaction</b>. That makes resolution atomic and idempotent:
 * a crash mid-draw rolls back and retries on reboot, and a second attempt finds no
 * open round and pays nothing.
 */
public class LotteryStore
{
    private static final Logger LOG = LoggerFactory.getLogger(LotteryStore.class);

    private final Path dbPath;
    private Connection connection;

    public LotteryStore(Path dbPath)
    {
        this.dbPath = dbPath;
    }

    public synchronized void init() throws SQLException
    {
        connection = Database.open(dbPath);
        try(Statement st = connection.createStatement())
        {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS lottery_draws ("
                    + "guild_id INTEGER PRIMARY KEY,"
                    + "channel_id INTEGER NOT NULL,"
                    + "draw_epoch INTEGER NOT NULL,"
                    + "pot INTEGER NOT NULL DEFAULT 0,"
                    + "created_at INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS lottery_tickets ("
                    + "guild_id INTEGER NOT NULL,"
                    + "user_id INTEGER NOT NULL,"
                    + "tickets INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY(guild_id, user_id))");
        }
        LOG.info("Lottery storage ready in unified database at {}", dbPath.toAbsolutePath());
    }

    /**
     * Adds {@code count} tickets for a user (coins already debited by the caller),
     * opening a new round (draw_epoch = now + interval) if none is open. Returns the
     * updated round info.
     */
    public synchronized DrawInfo buyTickets(long guildId, long channelId, long userId, int count,
                                            long ticketPrice, long drawIntervalSeconds, long nowEpoch)
    {
        ensureRound(guildId, channelId, nowEpoch, drawIntervalSeconds);
        bumpTickets(guildId, userId, count);
        addPot(guildId, count * ticketPrice);
        return draw(guildId, userId);
    }

    public synchronized DrawInfo getDraw(long guildId, long userId)
    {
        return draw(guildId, userId);
    }

    /** Guild ids whose open round is due (draw_epoch &le; now). */
    public synchronized List<Long> dueDraws(long nowEpoch)
    {
        ensureOpen();
        List<Long> due = new ArrayList<>();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT guild_id FROM lottery_draws WHERE draw_epoch <= ?"))
        {
            ps.setLong(1, nowEpoch);
            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                    due.add(rs.getLong(1));
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyStore.EconomyException("Failed to query due lottery draws", ex);
        }
        return due;
    }

    /**
     * Atomically resolves a guild's open round: picks a ticket-weighted winner,
     * credits the pot to their profile, and deletes the tickets + round — all in one
     * transaction. Returns the result, {@code null} if there was no open round
     * (already resolved), or a no-winner result if the round had no tickets.
     */
    public synchronized DrawResult resolveDraw(long guildId, Random rng)
    {
        ensureOpen();
        boolean previousAutoCommit = true;
        try
        {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            long channelId;
            long pot;
            try(PreparedStatement ps = connection.prepareStatement(
                    "SELECT channel_id, pot FROM lottery_draws WHERE guild_id=?"))
            {
                ps.setLong(1, guildId);
                try(ResultSet rs = ps.executeQuery())
                {
                    if(!rs.next())
                    {
                        connection.commit();
                        return null; // already resolved / never existed
                    }
                    channelId = rs.getLong(1);
                    pot = rs.getLong(2);
                }
            }

            List<TicketHolder> holders = new ArrayList<>();
            long totalTickets = 0;
            try(PreparedStatement ps = connection.prepareStatement(
                    "SELECT user_id, tickets FROM lottery_tickets WHERE guild_id=? ORDER BY user_id ASC"))
            {
                ps.setLong(1, guildId);
                try(ResultSet rs = ps.executeQuery())
                {
                    while(rs.next())
                    {
                        long tickets = rs.getLong(2);
                        holders.add(new TicketHolder(rs.getLong(1), tickets));
                        totalTickets += tickets;
                    }
                }
            }

            long winnerId = -1;
            if(totalTickets > 0)
            {
                winnerId = weightedPick(holders, Math.floorMod(rng.nextLong(), totalTickets));
                try(PreparedStatement ps = connection.prepareStatement(
                        "UPDATE user_profiles SET currency=currency+?, updated_at=? WHERE user_id=?"))
                {
                    ps.setLong(1, pot);
                    ps.setLong(2, Instant.now().getEpochSecond());
                    ps.setLong(3, winnerId);
                    ps.executeUpdate();
                }
            }

            deleteRound(guildId);
            connection.commit();
            return new DrawResult(guildId, channelId, winnerId, pot, totalTickets, holders.size());
        }
        catch(SQLException ex)
        {
            rollbackQuietly();
            throw new EconomyStore.EconomyException("Failed to resolve lottery draw", ex);
        }
        finally
        {
            restoreAutoCommit(previousAutoCommit);
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
            LOG.warn("Failed to close lottery database", ex);
        }
        finally
        {
            connection = null;
        }
    }

    // ---- pure winner selection --------------------------------------------

    /** Returns the user whose cumulative ticket range contains {@code roll} (0-based). */
    static long weightedPick(List<TicketHolder> holders, long roll)
    {
        long cumulative = 0;
        for(TicketHolder holder : holders)
        {
            cumulative += holder.tickets;
            if(roll < cumulative)
                return holder.userId;
        }
        return holders.isEmpty() ? -1 : holders.get(holders.size() - 1).userId;
    }

    // ---- internals ---------------------------------------------------------

    private void ensureRound(long guildId, long channelId, long nowEpoch, long drawIntervalSeconds)
    {
        try(PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO lottery_draws(guild_id, channel_id, draw_epoch, pot, created_at) "
                        + "VALUES(?,?,?,0,?)"))
        {
            ps.setLong(1, guildId);
            ps.setLong(2, channelId);
            ps.setLong(3, nowEpoch + drawIntervalSeconds);
            ps.setLong(4, nowEpoch);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new EconomyStore.EconomyException("Failed to open lottery round", ex);
        }
    }

    private void bumpTickets(long guildId, long userId, int count)
    {
        try(PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO lottery_tickets(guild_id, user_id, tickets) VALUES(?,?,?) "
                        + "ON CONFLICT(guild_id, user_id) DO UPDATE SET tickets=tickets+?"))
        {
            ps.setLong(1, guildId);
            ps.setLong(2, userId);
            ps.setInt(3, count);
            ps.setInt(4, count);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new EconomyStore.EconomyException("Failed to add lottery tickets", ex);
        }
    }

    private void addPot(long guildId, long amount)
    {
        try(PreparedStatement ps = connection.prepareStatement(
                "UPDATE lottery_draws SET pot=pot+? WHERE guild_id=?"))
        {
            ps.setLong(1, amount);
            ps.setLong(2, guildId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new EconomyStore.EconomyException("Failed to grow lottery pot", ex);
        }
    }

    private void deleteRound(long guildId) throws SQLException
    {
        try(PreparedStatement ps = connection.prepareStatement("DELETE FROM lottery_tickets WHERE guild_id=?"))
        {
            ps.setLong(1, guildId);
            ps.executeUpdate();
        }
        try(PreparedStatement ps = connection.prepareStatement("DELETE FROM lottery_draws WHERE guild_id=?"))
        {
            ps.setLong(1, guildId);
            ps.executeUpdate();
        }
    }

    private DrawInfo draw(long guildId, long userId)
    {
        long channelId;
        long drawEpoch;
        long pot;
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT channel_id, draw_epoch, pot FROM lottery_draws WHERE guild_id=?"))
        {
            ps.setLong(1, guildId);
            try(ResultSet rs = ps.executeQuery())
            {
                if(!rs.next())
                    return null;
                channelId = rs.getLong(1);
                drawEpoch = rs.getLong(2);
                pot = rs.getLong(3);
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyStore.EconomyException("Failed to read lottery draw", ex);
        }
        long total = sumTickets(guildId, null);
        long mine = sumTickets(guildId, userId);
        return new DrawInfo(guildId, channelId, drawEpoch, pot, total, mine);
    }

    private long sumTickets(long guildId, Long userId)
    {
        String sql = userId == null
                ? "SELECT COALESCE(SUM(tickets),0) FROM lottery_tickets WHERE guild_id=?"
                : "SELECT COALESCE(SUM(tickets),0) FROM lottery_tickets WHERE guild_id=? AND user_id=?";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, guildId);
            if(userId != null)
                ps.setLong(2, userId);
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyStore.EconomyException("Failed to sum lottery tickets", ex);
        }
    }

    private void rollbackQuietly()
    {
        try
        {
            connection.rollback();
        }
        catch(SQLException ex)
        {
            LOG.warn("Failed to roll back lottery draw", ex);
        }
    }

    private void restoreAutoCommit(boolean previous)
    {
        try
        {
            connection.setAutoCommit(previous);
        }
        catch(SQLException ex)
        {
            LOG.warn("Failed to restore autocommit after lottery draw", ex);
        }
    }

    private void ensureOpen()
    {
        if(connection == null)
            throw new EconomyStore.EconomyException("Lottery database is not initialized");
    }

    // ---- value types -------------------------------------------------------

    public static final class TicketHolder
    {
        final long userId;
        final long tickets;

        TicketHolder(long userId, long tickets)
        {
            this.userId = userId;
            this.tickets = tickets;
        }
    }

    public static final class DrawInfo
    {
        private final long guildId;
        private final long channelId;
        private final long drawEpoch;
        private final long pot;
        private final long totalTickets;
        private final long userTickets;

        DrawInfo(long guildId, long channelId, long drawEpoch, long pot, long totalTickets, long userTickets)
        {
            this.guildId = guildId;
            this.channelId = channelId;
            this.drawEpoch = drawEpoch;
            this.pot = pot;
            this.totalTickets = totalTickets;
            this.userTickets = userTickets;
        }

        public long getGuildId() { return guildId; }
        public long getChannelId() { return channelId; }
        public long getDrawEpoch() { return drawEpoch; }
        public long getPot() { return pot; }
        public long getTotalTickets() { return totalTickets; }
        public long getUserTickets() { return userTickets; }
    }

    public static final class DrawResult
    {
        private final long guildId;
        private final long channelId;
        private final long winnerId;
        private final long pot;
        private final long totalTickets;
        private final int participants;

        DrawResult(long guildId, long channelId, long winnerId, long pot, long totalTickets, int participants)
        {
            this.guildId = guildId;
            this.channelId = channelId;
            this.winnerId = winnerId;
            this.pot = pot;
            this.totalTickets = totalTickets;
            this.participants = participants;
        }

        public long getGuildId() { return guildId; }
        public long getChannelId() { return channelId; }
        public long getWinnerId() { return winnerId; }
        public long getPot() { return pot; }
        public long getTotalTickets() { return totalTickets; }
        public int getParticipants() { return participants; }
        public boolean hasWinner() { return winnerId > 0; }
    }
}
