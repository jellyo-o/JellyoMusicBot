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
import com.jagrosh.jmusicbot.economy.games.DoubleGame;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

/**
 * "Double or Nothing" after a winning first flip. Each further flip's win chance
 * decays and there is a hard streak cap, so pressing on is increasingly bad value.
 * Cash out (or time out) to bank the current pot.
 */
public class DoubleSession extends GameSession
{
    private long pot;
    private int wins;

    public DoubleSession(Bot bot, long ownerId, String ownerName, long guildId, long channelId,
                         long wager, String escrowId, long pot, int wins)
    {
        super(bot, ownerId, ownerName, guildId, channelId, wager, escrowId);
        this.pot = pot;
        this.wins = wins;
    }

    @Override
    public void onButton(String action, ButtonInteractionEvent event)
    {
        if(action.equals("dbl:cash"))
        {
            long snapshot;
            synchronized(this)
            {
                if(isSettled()) { ackIfNeeded(event); return; }
                snapshot = pot;
            }
            GameOutcome outcome = settleOnce(snapshot);
            if(outcome != null)
                editResult(event, GameEmbeds.result("🎲 Double or Nothing", "You cashed out at "
                        + wins + "x streak.", outcome, wager));
            else
                ackIfNeeded(event);
            return;
        }
        if(action.equals("dbl:double"))
        {
            boolean lost;
            long snapshotPot;
            int snapshotWins;
            boolean maxed;
            synchronized(this)
            {
                if(isSettled()) { ackIfNeeded(event); return; }
                boolean win = ThreadLocalRandom.current().nextDouble() < DoubleGame.winChance(wins);
                if(win) { wins++; pot *= 2; lost = false; } else { lost = true; }
                snapshotPot = pot;
                snapshotWins = wins;
                maxed = !DoubleGame.canContinue(wins);
            }
            if(lost)
            {
                GameOutcome outcome = settleOnce(0);
                if(outcome != null)
                    editResult(event, GameEmbeds.result("🎲 Double or Nothing",
                            "💥 The flip failed — you lost it all.", outcome, wager));
                else
                    ackIfNeeded(event);
            }
            else if(maxed)
            {
                GameOutcome outcome = settleOnce(snapshotPot);
                if(outcome != null)
                    editResult(event, GameEmbeds.result("🎲 Double or Nothing",
                            "🏆 Max streak (" + snapshotWins + "x) — auto-cashed!", outcome, wager));
                else
                    ackIfNeeded(event);
            }
            else
            {
                rearmTimeout(90_000);
                editPanel(event, panel(snapshotPot, snapshotWins, wager), buttons(snapshotWins));
            }
            return;
        }
        ackIfNeeded(event);
    }

    @Override
    protected void onTimeout()
    {
        long snapshot;
        synchronized(this)
        {
            if(isSettled())
                return;
            snapshot = pot;
        }
        GameOutcome outcome = settleOnce(snapshot);
        if(outcome != null)
            editResultById(GameEmbeds.result("🎲 Double or Nothing",
                    "⏱️ Timed out — your " + wins + "x pot was cashed out.", outcome, wager));
    }

    public static MessageEmbed panel(long pot, int wins, long wager)
    {
        double nextChance = DoubleGame.winChance(wins) * 100;
        return GameEmbeds.live("🎲 Double or Nothing",
                "Current pot: **" + EconomyService.coins(pot) + "** (" + wins + "x)\n"
                        + "Double again? Next flip wins **" + String.format("%.0f", nextChance) + "%**… or cash out.");
    }

    public static List<ActionRow> buttons(int wins)
    {
        return List.of(ActionRow.of(
                Button.success(GameButtons.id("dbl:double"), "Double (" + (wins + 1) + "x)"),
                Button.secondary(GameButtons.id("dbl:cash"), "Cash Out")));
    }
}
