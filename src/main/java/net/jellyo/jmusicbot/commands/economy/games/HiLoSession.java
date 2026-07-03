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
import com.jagrosh.jmusicbot.economy.Payouts;
import com.jagrosh.jmusicbot.economy.games.HiLoGame;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

/**
 * "Higher or Lower": guess whether the next card beats the current one. Each correct
 * call grows the pot (constant ~3% edge per step); cash out any time before a miss.
 */
public class HiLoSession extends GameSession
{
    public static final double SIZING_MULTIPLIER = 50.0;

    private int card;
    private long pot;
    private int streak;

    public HiLoSession(Bot bot, long ownerId, String ownerName, long guildId, long channelId, long wager, int card)
    {
        super(bot, ownerId, ownerName, guildId, channelId, wager);
        this.card = card;
        this.pot = wager;
        this.streak = 0;
    }

    @Override
    public void onButton(String action, ButtonInteractionEvent event)
    {
        if(action.equals("hilo:cash"))
        {
            long snapshot;
            synchronized(this)
            {
                if(isSettled()) { ackIfNeeded(event); return; }
                snapshot = pot;
            }
            GameOutcome outcome = settleOnce(snapshot);
            if(outcome != null)
                editResult(event, GameEmbeds.result("🃏 Higher or Lower",
                        "You cashed out on a **" + streak + "** streak.", outcome, wager));
            else
                ackIfNeeded(event);
            return;
        }
        boolean higher = action.equals("hilo:higher");
        if(!higher && !action.equals("hilo:lower"))
        {
            ackIfNeeded(event);
            return;
        }
        boolean lost;
        int shownCard;
        int nextCard;
        long shownPot;
        int shownStreak;
        boolean capped;
        synchronized(this)
        {
            if(isSettled()) { ackIfNeeded(event); return; }
            int next = HiLoGame.draw(ThreadLocalRandom.current());
            shownCard = card;
            nextCard = next;
            if(HiLoGame.correct(card, next, higher))
            {
                pot = (long) Math.floor(pot * HiLoGame.stepMultiplier(card, higher));
                streak++;
                card = next;
                lost = false;
            }
            else
            {
                lost = true;
            }
            shownPot = pot;
            shownStreak = streak;
            capped = pot >= Payouts.MAX_RETURN_PER_ROUND || streak >= HiLoGame.MAX_STREAK;
        }
        if(lost)
        {
            GameOutcome outcome = settleOnce(0);
            if(outcome != null)
                editResult(event, GameEmbeds.result("🃏 Higher or Lower",
                        "You called **" + (higher ? "higher" : "lower") + "** on **" + label(shownCard)
                                + "** — next was **" + label(nextCard) + "**. 💥", outcome, wager));
            else
                ackIfNeeded(event);
        }
        else if(capped)
        {
            GameOutcome outcome = settleOnce(shownPot);
            if(outcome != null)
                editResult(event, GameEmbeds.result("🃏 Higher or Lower",
                        "🏆 Maxed out at a **" + shownStreak + "** streak — auto-cashed!", outcome, wager));
            else
                ackIfNeeded(event);
        }
        else
        {
            rearmTimeout(90_000);
            editPanel(event, panel(nextCard, shownPot, shownStreak, wager), buttons(nextCard));
        }
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
            editResultById(GameEmbeds.result("🃏 Higher or Lower",
                    "⏱️ Timed out — cashed out your **" + streak + "** streak.", outcome, wager));
    }

    public static MessageEmbed panel(int card, long pot, int streak, long wager)
    {
        return GameEmbeds.live("🃏 Higher or Lower",
                "Current card: **" + label(card) + "**\nPot: **" + EconomyService.coins(pot) + "** • streak **"
                        + streak + "**\nWill the next card be higher or lower?");
    }

    public static List<ActionRow> buttons(int card)
    {
        Button higher = Button.success(GameButtons.id("hilo:higher"), "Higher ⬆")
                .withDisabled(!HiLoGame.higherPossible(card));
        Button lower = Button.danger(GameButtons.id("hilo:lower"), "Lower ⬇")
                .withDisabled(!HiLoGame.lowerPossible(card));
        Button cash = Button.secondary(GameButtons.id("hilo:cash"), "Cash Out");
        return List.of(ActionRow.of(higher, lower, cash));
    }

    private static String label(int card)
    {
        switch(card)
        {
            case 1: return "A";
            case 11: return "J";
            case 12: return "Q";
            case 13: return "K";
            default: return String.valueOf(card);
        }
    }
}
