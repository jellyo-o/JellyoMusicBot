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
import com.jagrosh.jmusicbot.economy.EconomyService.GameOutcome;
import com.jagrosh.jmusicbot.economy.games.BlackjackGame;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

/**
 * Blackjack versus the dealer over an infinite shoe. Hit, stand or (before hitting)
 * double. A natural blackjack is settled at the deal (paid 3:2); the dealer stands
 * on all 17s. Abandoning auto-stands, which is never better than playing on.
 */
public class BlackjackSession extends GameSession
{
    public static final double SIZING_MULTIPLIER = 4.0; // a doubled win returns up to 4x the base wager

    private final List<Integer> player = new ArrayList<>();
    private final List<Integer> dealer = new ArrayList<>();
    private long stake;
    private boolean canDouble = true;

    public BlackjackSession(Bot bot, long ownerId, String ownerName, long guildId, long channelId,
                            long wager, List<Integer> playerCards, List<Integer> dealerCards)
    {
        super(bot, ownerId, ownerName, guildId, channelId, wager);
        this.player.addAll(playerCards);
        this.dealer.addAll(dealerCards);
        this.stake = wager;
    }

    @Override
    public void onButton(String action, ButtonInteractionEvent event)
    {
        switch(action)
        {
            case "bj:hit": hit(event); break;
            case "bj:stand": stand(event, "You stand."); break;
            case "bj:double": doubleDown(event); break;
            default: ackIfNeeded(event);
        }
    }

    private void hit(ButtonInteractionEvent event)
    {
        boolean bust;
        synchronized(this)
        {
            if(isSettled()) { ackIfNeeded(event); return; }
            player.add(BlackjackGame.draw(ThreadLocalRandom.current()));
            canDouble = false;
            bust = BlackjackGame.isBust(player);
        }
        if(bust)
        {
            if(!claimResolution()) { ackIfNeeded(event); return; }
            GameOutcome outcome = settleClaimed(stake, 0);
            if(outcome != null)
                editResult(event, resultEmbed(outcome, "💥 Bust!"));
        }
        else
        {
            editPanel(event, livePanel(), buttons());
        }
    }

    private void doubleDown(ButtonInteractionEvent event)
    {
        synchronized(this)
        {
            if(isSettled()) { ackIfNeeded(event); return; }
            if(!canDouble)
            {
                event.reply(bot.getConfig().getWarning() + " You can only double on your first two cards.")
                        .setEphemeral(true).queue(x -> {}, t -> {});
                return;
            }
            if(!bot.getEconomyService().trySpend(ownerId, wager))
            {
                event.reply(bot.getConfig().getError() + " You can't afford to double.")
                        .setEphemeral(true).queue(x -> {}, t -> {});
                return;
            }
            stake = wager * 2;
            canDouble = false;
            player.add(BlackjackGame.draw(ThreadLocalRandom.current()));
        }
        // One card then auto-stand (or bust).
        if(BlackjackGame.isBust(player))
        {
            if(!claimResolution()) { ackIfNeeded(event); return; }
            GameOutcome outcome = settleClaimed(stake, 0);
            if(outcome != null)
                editResult(event, resultEmbed(outcome, "💥 Doubled and bust!"));
        }
        else
        {
            stand(event, "Doubled down!");
        }
    }

    private void stand(ButtonInteractionEvent event, String note)
    {
        if(!claimResolution())
        {
            ackIfNeeded(event);
            return;
        }
        long payout = playOutAndScore();
        GameOutcome outcome = settleClaimed(stake, payout);
        if(outcome != null)
            editResult(event, resultEmbed(outcome, note + " " + summary()));
    }

    @Override
    protected void onTimeout()
    {
        if(!claimResolution())
            return;
        long payout = playOutAndScore();
        GameOutcome outcome = settleClaimed(stake, payout);
        if(outcome != null)
            editResultById(resultEmbed(outcome, "⏱️ Auto-stood. " + summary()));
    }

    /** Plays the dealer to 17 and returns the stake-inclusive payout for the player. */
    private long playOutAndScore()
    {
        synchronized(this)
        {
            while(!BlackjackGame.dealerStands(dealer))
                dealer.add(BlackjackGame.draw(ThreadLocalRandom.current()));
        }
        int pv = BlackjackGame.bestValue(player);
        int dv = BlackjackGame.bestValue(dealer);
        if(pv > 21)
            return 0;
        if(dv > 21 || pv > dv)
            return stake * 2; // even money
        if(pv == dv)
            return stake; // push
        return 0;
    }

    private MessageEmbed resultEmbed(GameOutcome outcome, String detail)
    {
        return GameEmbeds.result("🂡 Blackjack", detail + "\n" + hands(true), outcome, wager);
    }

    private MessageEmbed livePanel()
    {
        return GameEmbeds.live("🂡 Blackjack", hands(false) + "\nHit, stand"
                + (canDouble ? " or double" : "") + "?");
    }

    private String summary()
    {
        return "You **" + BlackjackGame.bestValue(player) + "**, dealer **" + BlackjackGame.bestValue(dealer) + "**.";
    }

    private String hands(boolean revealDealer)
    {
        String dealerHand = revealDealer
                ? cards(dealer) + " (" + BlackjackGame.bestValue(dealer) + ")"
                : card(dealer.get(0)) + " 🂠";
        return "**You:** " + cards(player) + " (" + BlackjackGame.bestValue(player) + ")\n"
                + "**Dealer:** " + dealerHand;
    }

    public MessageEmbed panel()
    {
        return livePanel();
    }

    public List<ActionRow> buttons()
    {
        Button hit = Button.primary(GameButtons.id("bj:hit"), "Hit");
        Button stand = Button.secondary(GameButtons.id("bj:stand"), "Stand");
        Button dbl = Button.success(GameButtons.id("bj:double"), "Double").withDisabled(!canDouble);
        return List.of(ActionRow.of(hit, stand, dbl));
    }

    public static String cards(List<Integer> ranks)
    {
        StringBuilder sb = new StringBuilder();
        for(int r : ranks)
        {
            if(sb.length() > 0)
                sb.append(' ');
            sb.append(card(r));
        }
        return sb.toString();
    }

    static String card(int rank)
    {
        switch(rank)
        {
            case 1: return "A";
            case 11: return "J";
            case 12: return "Q";
            case 13: return "K";
            default: return String.valueOf(rank);
        }
    }
}
