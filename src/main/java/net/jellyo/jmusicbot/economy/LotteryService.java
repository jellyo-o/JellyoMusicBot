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

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.economy.LotteryStore.DrawInfo;
import com.jagrosh.jmusicbot.economy.LotteryStore.DrawResult;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.entities.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the single, bot-wide lottery. Selling tickets debits coins; a sweep on its own
 * scheduler resolves the draw when its bot-owner-configured time arrives (with a
 * boot-time catch-up for a draw missed while offline). The winner is picked weighted
 * by tickets and credited atomically by {@link LotteryStore}; the result is announced
 * <b>anonymously</b> (a winner count, never a name) and the winner is DM'd.
 *
 * <p>Anti-whale: a hard {@value #MAX_TICKETS_PER_USER}-ticket-per-draw cap per user, so
 * no one can buy the odds — the biggest buyer tops out at the same ceiling as anyone else.
 */
public class LotteryService
{
    private static final Logger LOG = LoggerFactory.getLogger(LotteryService.class);

    /** Anti-whale cap: the most tickets any single user may hold in one draw. */
    public static final int MAX_TICKETS_PER_USER = 50;
    private static final long SWEEP_INITIAL_DELAY_SECONDS = 20;
    private static final long SWEEP_PERIOD_SECONDS = 30;

    private final Bot bot;
    private final LotteryStore store;
    private ScheduledExecutorService scheduler;
    private volatile LastDraw lastDraw;

    public LotteryService(Bot bot, LotteryStore store)
    {
        this.bot = bot;
        this.store = store;
    }

    public boolean isEnabled()
    {
        return store != null && bot.getConfig().isLotteryEnabled() && bot.getEconomyService().isEnabled();
    }

    public long ticketPrice()
    {
        return bot.getConfig().getLotteryTicketPrice();
    }

    public void init()
    {
        if(store == null || !bot.getConfig().isLotteryEnabled())
            return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread thread = new Thread(r, "jmusicbot-lottery");
            thread.setDaemon(true);
            return thread;
        });
        // The first sweep (after a short delay so JDA is ready) catches up any draw whose
        // time passed while the bot was offline.
        scheduler.scheduleWithFixedDelay(this::sweep, SWEEP_INITIAL_DELAY_SECONDS,
                SWEEP_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    public void shutdown()
    {
        if(scheduler != null)
            scheduler.shutdownNow();
        if(store != null)
            store.close();
    }

    /** Buys tickets for the caller, enforcing the anti-whale per-user cap, in one crash-safe transaction. */
    public BuyResult buy(User user, int count)
    {
        if(!isEnabled())
            return BuyResult.disabled();
        int tickets = Math.max(1, count);
        long userId = user.getIdLong();
        // Fast, friendly pre-check (also guarantees a single request never exceeds the cap). The
        // authoritative cap + affordability check is the atomic transaction in the store below.
        long held = store.getUserTickets(userId);
        if(tickets > MAX_TICKETS_PER_USER || held + tickets > MAX_TICKETS_PER_USER)
            return BuyResult.capReached(MAX_TICKETS_PER_USER, held);
        long price = ticketPrice();
        long cost = tickets * price;
        bot.getEconomyService().ensureUser(user);
        long intervalSeconds = bot.getConfig().getLotteryDrawIntervalHours() * 3600L;
        // The debit + ticket write share one LotteryStore transaction, so there is no debit-without-tickets
        // window to crash into and no separate refund path to get wrong.
        LotteryStore.BuyOutcome outcome = store.buyTickets(userId, tickets, price, intervalSeconds,
                Instant.now().getEpochSecond(), MAX_TICKETS_PER_USER);
        switch(outcome.getStatus())
        {
            case INSUFFICIENT:
                return BuyResult.insufficient(cost);
            case CAP_REACHED:
                return BuyResult.capReached(MAX_TICKETS_PER_USER, store.getUserTickets(userId));
            case BOUGHT:
            default:
                return BuyResult.bought(tickets, cost, outcome.getInfo());
        }
    }

    public DrawInfo info(long userId)
    {
        return store == null ? null : store.getInfo(userId);
    }

    public LastDraw lastDraw()
    {
        return lastDraw;
    }

    private void sweep()
    {
        try
        {
            if(!store.isDue(Instant.now().getEpochSecond()))
                return;
            DrawResult result = store.resolveDraw(ThreadLocalRandom.current());
            if(result == null)
                return;
            if(result.hasWinner())
            {
                lastDraw = new LastDraw(result.getPot(), result.getParticipants(), result.getTotalTickets());
                dmWinner(result);
            }
        }
        catch(RuntimeException ex)
        {
            LOG.warn("Lottery sweep failed", ex);
        }
    }

    private void dmWinner(DrawResult result)
    {
        if(bot.getJDA() == null)
            return; // winner already credited atomically; the DM is best-effort
        bot.getJDA().retrieveUserById(result.getWinnerId()).queue(user ->
                user.openPrivateChannel().queue(channel ->
                        channel.sendMessage("🎉 You won the lottery! **"
                                + EconomyService.coins(result.getPot()) + "** has been added to your balance "
                                + "(from " + result.getParticipants() + " players and "
                                + result.getTotalTickets() + " tickets).").queue(m -> {}, t -> {}),
                        t -> {}),
                t -> LOG.debug("Could not DM lottery winner {}: {}", result.getWinnerId(), t.toString()));
    }

    public static final class LastDraw
    {
        private final long pot;
        private final int participants;
        private final long totalTickets;

        LastDraw(long pot, int participants, long totalTickets)
        {
            this.pot = pot;
            this.participants = participants;
            this.totalTickets = totalTickets;
        }

        public long getPot() { return pot; }
        public int getParticipants() { return participants; }
        public long getTotalTickets() { return totalTickets; }
    }

    public static final class BuyResult
    {
        public enum Status { DISABLED, CAP_REACHED, INSUFFICIENT, BOUGHT }

        private final Status status;
        private final int tickets;
        private final long cost;
        private final int cap;
        private final long held;
        private final DrawInfo info;

        private BuyResult(Status status, int tickets, long cost, int cap, long held, DrawInfo info)
        {
            this.status = status;
            this.tickets = tickets;
            this.cost = cost;
            this.cap = cap;
            this.held = held;
            this.info = info;
        }

        static BuyResult disabled() { return new BuyResult(Status.DISABLED, 0, 0, 0, 0, null); }
        static BuyResult capReached(int cap, long held) { return new BuyResult(Status.CAP_REACHED, 0, 0, cap, held, null); }
        static BuyResult insufficient(long cost) { return new BuyResult(Status.INSUFFICIENT, 0, cost, 0, 0, null); }
        static BuyResult bought(int tickets, long cost, DrawInfo info) { return new BuyResult(Status.BOUGHT, tickets, cost, 0, 0, info); }

        public Status getStatus() { return status; }
        public int getTickets() { return tickets; }
        public long getCost() { return cost; }
        public int getCap() { return cap; }
        public long getHeld() { return held; }
        public DrawInfo getInfo() { return info; }
    }
}
