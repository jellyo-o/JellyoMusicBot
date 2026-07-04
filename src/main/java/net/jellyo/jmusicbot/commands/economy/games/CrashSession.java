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
package com.jagrosh.jmusicbot.commands.economy.games;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.EconomyService.GameOutcome;
import com.jagrosh.jmusicbot.economy.games.CrashGame;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

/**
 * "Crash": a multiplier climbs in coarse ~1.2s steps until it crashes at a point
 * fixed when the round starts. Hit <b>Cash Out</b> before it blows (a cash-out
 * <i>received</i> before the crash tick wins — arrival-order reflex play), or pre-set
 * an auto-cash-out target. When the live budget is saturated, a target is required.
 */
public class CrashSession extends GameSession
{
    static final long TICK_MS = 1200;
    /** The coarse multiplier ladder the display climbs; tops out at the crash ceiling. */
    private static final double[] LADDER =
            {1.2, 1.4, 1.6, 1.9, 2.2, 2.6, 3.1, 3.7, 4.5, 5.5, 7, 9, 12, 16, 22, 30, 45, 65, CrashGame.MAX_MULTIPLIER};
    private static final int MAX_LIVE = 16;
    private static final AtomicInteger LIVE = new AtomicInteger();

    private final double crashPoint;
    private final double target; // 0 = live cash-out only
    private volatile double current = 1.0;
    private int tick;
    private boolean counted;
    private final List<ScheduledFuture<?>> tickTasks = new CopyOnWriteArrayList<>();

    public CrashSession(Bot bot, long ownerId, String ownerName, long guildId, long channelId,
                        long wager, String escrowId, double crashPoint, double target)
    {
        super(bot, ownerId, ownerName, guildId, channelId, wager, escrowId);
        this.crashPoint = crashPoint;
        this.target = target;
    }

    /** True if another live crash round can start (else the caller should require a target). */
    public static boolean liveBudgetAvailable()
    {
        return LIVE.get() < MAX_LIVE;
    }

    @Override
    protected void onStarted()
    {
        LIVE.incrementAndGet();
        counted = true;
        scheduleNextTick();
    }

    @Override
    protected void onResolved()
    {
        if(counted)
        {
            LIVE.decrementAndGet();
            counted = false;
        }
        for(ScheduledFuture<?> f : tickTasks)
            f.cancel(false);
    }

    private void scheduleNextTick()
    {
        tickTasks.add(bot.getGamesScheduler().schedule(this::onTick, TICK_MS, TimeUnit.MILLISECONDS));
    }

    private void onTick()
    {
        if(isSettled())
            return;
        double value;
        boolean crash = false;
        boolean targetReached = false;
        synchronized(this)
        {
            if(isSettled())
                return;
            if(tick >= LADDER.length)
            {
                crash = true;
                value = crashPoint;
            }
            else
            {
                value = LADDER[tick++];
                if(target > 0 && value >= target)
                    targetReached = true;
                else if(value >= crashPoint)
                    crash = true;
                else
                    current = value;
            }
        }
        if(targetReached)
        {
            if(target <= crashPoint)
                finishById(settleOnce((long) Math.floor(wager * target)),
                        "🤑 Auto-cashed at **" + fmt(target) + "x**! (it crashed at " + fmt(crashPoint) + "x)");
            else
                finishById(settleOnce(0),
                        "💥 Crashed at **" + fmt(crashPoint) + "x** before your " + fmt(target) + "x target.");
        }
        else if(crash)
        {
            finishById(settleOnce(0), "💥 Crashed at **" + fmt(crashPoint) + "x**.");
        }
        else
        {
            editPanelById(panel(value), buttons());
            scheduleNextTick();
        }
    }

    @Override
    public void onButton(String action, ButtonInteractionEvent event)
    {
        if(!action.equals("crash:cash"))
        {
            ackIfNeeded(event);
            return;
        }
        double cashAt;
        synchronized(this)
        {
            if(isSettled()) { ackIfNeeded(event); return; }
            cashAt = current;
        }
        GameOutcome outcome = settleOnce((long) Math.floor(wager * cashAt));
        if(outcome != null)
            editResult(event, GameEmbeds.result("🚀 Crash",
                    "You cashed out at **" + fmt(cashAt) + "x**! (it crashed at " + fmt(crashPoint) + "x)",
                    outcome, wager));
        else
            ackIfNeeded(event);
    }

    @Override
    protected void onTimeout()
    {
        // No cash-out received = loss (arrival-order reflex semantics).
        finishById(settleOnce(0), "⏱️ You never cashed out — it crashed at " + fmt(crashPoint) + "x.");
    }

    private void finishById(GameOutcome outcome, String detail)
    {
        if(outcome != null)
            editResultById(GameEmbeds.result("🚀 Crash", detail, outcome, wager));
    }

    private MessageEmbed panel(double value)
    {
        return GameEmbeds.live("🚀 Crash",
                "📈 **" + fmt(value) + "x**\nCash out before it crashes!"
                        + (target > 0 ? "\nAuto-cash target: **" + fmt(target) + "x**" : ""));
    }

    public static MessageEmbed startPanel(long wager, double target)
    {
        return GameEmbeds.live("🚀 Crash",
                "You bet " + EconomyService.coins(wager) + ". The rocket is launching…"
                        + (target > 0 ? "\nAuto-cash target: **" + fmt(target) + "x**" : ""));
    }

    public static List<ActionRow> buttons()
    {
        return List.of(ActionRow.of(Button.success(GameButtons.id("crash:cash"), "💰 Cash Out")));
    }

    private static String fmt(double m)
    {
        return String.format("%.2f", m);
    }
}
