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
 * Storage for the single, bot-wide lottery — one global pot at a time. The draw
 * schedule (a bot-owner setting) lives in the database rather than a bare
 * {@code ScheduledFuture}, so a due draw survives restarts. {@link #resolveDraw}
 * picks a ticket-weighted winner, credits them and closes the round in one
 * transaction: a crash mid-draw rolls back and retries on reboot, and a second
 * attempt finds no open round and pays nothing.
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
            st.executeUpdate("CREATE TABLE IF NOT EXISTS lottery_global ("
                    + "id INTEGER PRIMARY KEY CHECK(id = 1),"
                    + "draw_epoch INTEGER NOT NULL,"
                    + "pot INTEGER NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS lottery_tickets ("
                    + "user_id INTEGER PRIMARY KEY,"
                    + "tickets INTEGER NOT NULL DEFAULT 0)");
        }
        LOG.info("Global lottery storage ready in unified database at {}", dbPath.toAbsolutePath());
    }

    /** Tickets the user already holds in the current round (0 if none / no round open). */
    public synchronized long getUserTickets(long userId)
    {
        ensureOpen();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT tickets FROM lottery_tickets WHERE user_id=?"))
        {
            ps.setLong(1, userId);
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyStore.EconomyException("Failed to read lottery tickets", ex);
        }
    }

    /**
     * Buys {@code count} tickets for a user in a <b>single transaction</b> that debits their coins, opens the
     * round if none is live, enforces the per-user cap, and grows the pot — all on this store's connection.
     * Because the coin debit and the ticket write commit or roll back together, a crash mid-buy can never
     * take the coins without granting the tickets (or vice-versa): the whole buy is crash-safe with no
     * separate refund needed. Returns {@link BuyOutcome.Status#INSUFFICIENT} if the buyer can't afford the
     * cost, {@link BuyOutcome.Status#CAP_REACHED} if it would exceed the cap (nothing written), or
     * {@link BuyOutcome.Status#BOUGHT} with the updated round info.
     *
     * <p>The caller guarantees {@code count <= maxTicketsPerUser} (a single request never exceeds the cap),
     * so the fresh-insert branch is always within the cap; concurrent repeat buys route through the
     * conflict {@code UPDATE ... WHERE tickets+count <= cap}, which closes the check-then-act race.
     */
    public synchronized BuyOutcome buyTickets(long userId, int count, long ticketPrice,
                                              long drawIntervalSeconds, long nowEpoch, int maxTicketsPerUser)
    {
        ensureOpen();
        long cost = (long) count * ticketPrice;
        boolean previousAutoCommit = true;
        try
        {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            // Debit the buyer's coins on THIS connection, inside the same transaction as the ticket write.
            int debited;
            try(PreparedStatement ps = connection.prepareStatement(
                    "UPDATE user_profiles SET currency=currency-?, updated_at=? WHERE user_id=? AND currency>=?"))
            {
                ps.setLong(1, cost);
                ps.setLong(2, nowEpoch);
                ps.setLong(3, userId);
                ps.setLong(4, cost);
                debited = ps.executeUpdate();
            }
            if(debited == 0)
            {
                connection.rollback(); // can't afford — nothing taken
                return BuyOutcome.insufficient();
            }

            try(PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO lottery_global(id, draw_epoch, pot) VALUES(1, ?, 0)"))
            {
                ps.setLong(1, nowEpoch + drawIntervalSeconds);
                ps.executeUpdate();
            }

            int updated;
            try(PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO lottery_tickets(user_id, tickets) VALUES(?, ?) "
                            + "ON CONFLICT(user_id) DO UPDATE SET tickets=tickets+? WHERE tickets+? <= ?"))
            {
                ps.setLong(1, userId);
                ps.setInt(2, count);
                ps.setInt(3, count);
                ps.setInt(4, count);
                ps.setInt(5, maxTicketsPerUser);
                updated = ps.executeUpdate();
            }
            if(updated == 0)
            {
                connection.rollback(); // cap would be exceeded — this also undoes the debit above
                return BuyOutcome.capReached();
            }

            try(PreparedStatement ps = connection.prepareStatement("UPDATE lottery_global SET pot=pot+? WHERE id=1"))
            {
                ps.setLong(1, cost);
                ps.executeUpdate();
            }
            // Read the return value INSIDE the transaction, before commit, so a read fault rolls the whole
            // buy back rather than being mistaken for a committed-but-unreadable buy.
            DrawInfo info = readInfoInTransaction(userId);
            connection.commit();
            return BuyOutcome.bought(info);
        }
        catch(SQLException ex)
        {
            rollbackQuietly();
            throw new EconomyStore.EconomyException("Failed to buy lottery tickets", ex);
        }
        finally
        {
            restoreAutoCommit(previousAutoCommit);
        }
    }

    /** Reads the round info on the current connection, letting a fault surface as SQLException (so the
     *  enclosing transaction can roll back). Used for buyTickets' pre-commit return value. */
    private DrawInfo readInfoInTransaction(long userId) throws SQLException
    {
        long drawEpoch = 0;
        long pot = 0;
        try(PreparedStatement ps = connection.prepareStatement("SELECT draw_epoch, pot FROM lottery_global WHERE id=1");
            ResultSet rs = ps.executeQuery())
        {
            if(rs.next())
            {
                drawEpoch = rs.getLong(1);
                pot = rs.getLong(2);
            }
        }
        long total = 0;
        long userTickets = 0;
        try(PreparedStatement ps = connection.prepareStatement("SELECT user_id, tickets FROM lottery_tickets");
            ResultSet rs = ps.executeQuery())
        {
            while(rs.next())
            {
                long tickets = rs.getLong(2);
                total += tickets;
                if(rs.getLong(1) == userId)
                    userTickets = tickets;
            }
        }
        return new DrawInfo(drawEpoch, pot, total, userTickets);
    }

    public synchronized DrawInfo getInfo(long userId)
    {
        ensureOpen();
        long drawEpoch;
        long pot;
        try(PreparedStatement ps = connection.prepareStatement("SELECT draw_epoch, pot FROM lottery_global WHERE id=1"))
        {
            try(ResultSet rs = ps.executeQuery())
            {
                if(!rs.next())
                    return null;
                drawEpoch = rs.getLong(1);
                pot = rs.getLong(2);
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyStore.EconomyException("Failed to read lottery info", ex);
        }
        return new DrawInfo(drawEpoch, pot, totalTickets(), getUserTickets(userId));
    }

    /** True if a round is open and its draw time has passed. */
    public synchronized boolean isDue(long nowEpoch)
    {
        ensureOpen();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM lottery_global WHERE id=1 AND draw_epoch <= ?"))
        {
            ps.setLong(1, nowEpoch);
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next();
            }
        }
        catch(SQLException ex)
        {
            throw new EconomyStore.EconomyException("Failed to check lottery due", ex);
        }
    }

    /**
     * Atomically resolves the open round: picks a ticket-weighted winner, credits the
     * pot to their profile, and closes the round — all in one transaction. Returns the
     * result, or {@code null} if there was no open round (already resolved).
     */
    public synchronized DrawResult resolveDraw(Random rng)
    {
        ensureOpen();
        boolean previousAutoCommit = true;
        try
        {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            long pot;
            try(PreparedStatement ps = connection.prepareStatement("SELECT pot FROM lottery_global WHERE id=1"))
            {
                try(ResultSet rs = ps.executeQuery())
                {
                    if(!rs.next())
                    {
                        connection.commit();
                        return null; // already resolved / never opened
                    }
                    pot = rs.getLong(1);
                }
            }

            List<TicketHolder> holders = new ArrayList<>();
            long total = 0;
            try(PreparedStatement ps = connection.prepareStatement(
                    "SELECT user_id, tickets FROM lottery_tickets ORDER BY user_id ASC"))
            {
                try(ResultSet rs = ps.executeQuery())
                {
                    while(rs.next())
                    {
                        long tickets = rs.getLong(2);
                        holders.add(new TicketHolder(rs.getLong(1), tickets));
                        total += tickets;
                    }
                }
            }

            long winnerId = -1;
            if(total > 0)
            {
                winnerId = weightedPick(holders, Math.floorMod(rng.nextLong(), total));
                try(PreparedStatement ps = connection.prepareStatement(
                        "UPDATE user_profiles SET currency=currency+?, updated_at=? WHERE user_id=?"))
                {
                    ps.setLong(1, pot);
                    ps.setLong(2, Instant.now().getEpochSecond());
                    ps.setLong(3, winnerId);
                    ps.executeUpdate();
                }
            }

            try(Statement st = connection.createStatement())
            {
                st.executeUpdate("DELETE FROM lottery_tickets");
                st.executeUpdate("DELETE FROM lottery_global");
            }
            connection.commit();
            return new DrawResult(winnerId, pot, total, holders.size());
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

    private long totalTickets()
    {
        try(Statement st = connection.createStatement();
            ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(tickets),0) FROM lottery_tickets"))
        {
            return rs.next() ? rs.getLong(1) : 0;
        }
        catch(SQLException ex)
        {
            throw new EconomyStore.EconomyException("Failed to total lottery tickets", ex);
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
        private final long drawEpoch;
        private final long pot;
        private final long totalTickets;
        private final long userTickets;

        DrawInfo(long drawEpoch, long pot, long totalTickets, long userTickets)
        {
            this.drawEpoch = drawEpoch;
            this.pot = pot;
            this.totalTickets = totalTickets;
            this.userTickets = userTickets;
        }

        public long getDrawEpoch() { return drawEpoch; }
        public long getPot() { return pot; }
        public long getTotalTickets() { return totalTickets; }
        public long getUserTickets() { return userTickets; }
    }

    /** Outcome of a {@link #buyTickets} call: the buy went through, or was refused (can't afford / cap). */
    public static final class BuyOutcome
    {
        public enum Status { INSUFFICIENT, CAP_REACHED, BOUGHT }

        private final Status status;
        private final DrawInfo info;

        private BuyOutcome(Status status, DrawInfo info)
        {
            this.status = status;
            this.info = info;
        }

        static BuyOutcome insufficient() { return new BuyOutcome(Status.INSUFFICIENT, null); }
        static BuyOutcome capReached() { return new BuyOutcome(Status.CAP_REACHED, null); }
        static BuyOutcome bought(DrawInfo info) { return new BuyOutcome(Status.BOUGHT, info); }

        public Status getStatus() { return status; }
        public DrawInfo getInfo() { return info; }
    }

    public static final class DrawResult
    {
        private final long winnerId;
        private final long pot;
        private final long totalTickets;
        private final int participants;

        DrawResult(long winnerId, long pot, long totalTickets, int participants)
        {
            this.winnerId = winnerId;
            this.pot = pot;
            this.totalTickets = totalTickets;
            this.participants = participants;
        }

        public long getWinnerId() { return winnerId; }
        public long getPot() { return pot; }
        public long getTotalTickets() { return totalTickets; }
        public int getParticipants() { return participants; }
        public boolean hasWinner() { return winnerId > 0; }
    }
}
