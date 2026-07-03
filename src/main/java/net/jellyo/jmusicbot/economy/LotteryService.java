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
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the per-guild lottery: selling tickets and, on its own scheduler, sweeping
 * for due rounds and resolving them. The DB is the source of truth for the draw
 * schedule; the sweep's first run (shortly after boot) resolves any round whose
 * time passed while the bot was offline, and {@link LotteryStore#resolveDraw} is
 * atomic so a restart never drops or double-pays a draw.
 */
public class LotteryService
{
    private static final Logger LOG = LoggerFactory.getLogger(LotteryService.class);

    public static final long TICKET_PRICE = 100;
    public static final int MAX_TICKETS_PER_BUY = 100;
    public static final long DRAW_INTERVAL_SECONDS = 86_400; // 24h
    private static final long SWEEP_INITIAL_DELAY_SECONDS = 20;
    private static final long SWEEP_PERIOD_SECONDS = 30;

    private final Bot bot;
    private final LotteryStore store;
    private ScheduledExecutorService scheduler;

    public LotteryService(Bot bot, LotteryStore store)
    {
        this.bot = bot;
        this.store = store;
    }

    public boolean isEnabled()
    {
        return store != null && bot.getEconomyService().isEnabled();
    }

    public void init()
    {
        if(store == null)
            return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread thread = new Thread(r, "jmusicbot-lottery");
            thread.setDaemon(true);
            return thread;
        });
        // The first sweep (after a short delay so JDA is ready to announce) is the boot-time
        // catch-up for any round whose draw time passed while the bot was offline.
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

    /** Buys tickets for the caller (debits coins), opening a round if none is live. */
    public BuyResult buy(long guildId, long channelId, net.dv8tion.jda.api.entities.User user, int count)
    {
        if(!isEnabled())
            return BuyResult.disabled();
        int tickets = Math.max(1, count);
        if(tickets > MAX_TICKETS_PER_BUY)
            return BuyResult.tooMany();
        long cost = tickets * TICKET_PRICE;
        bot.getEconomyService().ensureUser(user);
        if(!bot.getEconomyService().trySpend(user.getIdLong(), cost))
            return BuyResult.insufficient(cost);
        long now = Instant.now().getEpochSecond();
        DrawInfo info = store.buyTickets(guildId, channelId, user.getIdLong(), tickets,
                TICKET_PRICE, DRAW_INTERVAL_SECONDS, now);
        return BuyResult.bought(tickets, cost, info);
    }

    public DrawInfo info(long guildId, long userId)
    {
        if(store == null)
            return null;
        return store.getDraw(guildId, userId);
    }

    private void sweep()
    {
        try
        {
            long now = Instant.now().getEpochSecond();
            List<Long> due = store.dueDraws(now);
            for(long guildId : due)
            {
                try
                {
                    DrawResult result = store.resolveDraw(guildId, ThreadLocalRandom.current());
                    if(result != null)
                        announce(result);
                }
                catch(RuntimeException ex)
                {
                    LOG.warn("Failed to resolve lottery draw for guild {}", guildId, ex);
                }
            }
        }
        catch(RuntimeException ex)
        {
            LOG.warn("Lottery sweep failed", ex);
        }
    }

    private void announce(DrawResult result)
    {
        MessageChannel channel = bot.getJDA() == null
                ? null : bot.getJDA().getChannelById(MessageChannel.class, result.getChannelId());
        if(channel == null)
            return; // winner already credited atomically; announcement is best-effort
        if(!result.hasWinner())
            return;
        String message = "🎉 **Lottery draw!** <@" + result.getWinnerId() + "> won the pot of **"
                + EconomyService.coins(result.getPot()) + "** from " + result.getParticipants()
                + " player(s) and " + result.getTotalTickets() + " tickets!\nStart the next round with `/lottery buy`.";
        channel.sendMessage(message)
                .setAllowedMentions(EnumSet.of(Message.MentionType.USER))
                .queue(m -> {}, t -> LOG.debug("Failed to announce lottery winner: {}", t.toString()));
    }

    public static final class BuyResult
    {
        public enum Status { DISABLED, TOO_MANY, INSUFFICIENT, BOUGHT }

        private final Status status;
        private final int tickets;
        private final long cost;
        private final DrawInfo info;

        private BuyResult(Status status, int tickets, long cost, DrawInfo info)
        {
            this.status = status;
            this.tickets = tickets;
            this.cost = cost;
            this.info = info;
        }

        static BuyResult disabled() { return new BuyResult(Status.DISABLED, 0, 0, null); }
        static BuyResult tooMany() { return new BuyResult(Status.TOO_MANY, 0, 0, null); }
        static BuyResult insufficient(long cost) { return new BuyResult(Status.INSUFFICIENT, 0, cost, null); }
        static BuyResult bought(int tickets, long cost, DrawInfo info) { return new BuyResult(Status.BOUGHT, tickets, cost, info); }

        public Status getStatus() { return status; }
        public int getTickets() { return tickets; }
        public long getCost() { return cost; }
        public DrawInfo getInfo() { return info; }
    }
}
