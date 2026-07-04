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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

/** Rock-Paper-Scissors versus the bot. A win pays ~1.95x, a tie is a push. One click resolves it. */
public class RpsSession extends GameSession
{
    private static final String[] NAMES = {"Rock", "Paper", "Scissors"};
    private static final String[] EMOJI = {"🪨", "📄", "✂️"};
    private static final double WIN_MULTIPLIER = 1.95;
    public static final double SIZING_MULTIPLIER = WIN_MULTIPLIER;

    public RpsSession(Bot bot, long ownerId, String ownerName, long guildId, long channelId, long wager)
    {
        super(bot, ownerId, ownerName, guildId, channelId, wager);
    }

    public static List<ActionRow> buttons()
    {
        return List.of(ActionRow.of(
                Button.primary(GameButtons.id("rps:0"), NAMES[0]).withEmoji(Emoji.fromUnicode(EMOJI[0])),
                Button.primary(GameButtons.id("rps:1"), NAMES[1]).withEmoji(Emoji.fromUnicode(EMOJI[1])),
                Button.primary(GameButtons.id("rps:2"), NAMES[2]).withEmoji(Emoji.fromUnicode(EMOJI[2]))));
    }

    @Override
    public void onButton(String action, ButtonInteractionEvent event)
    {
        int player = parseMove(action);
        if(player < 0)
        {
            event.deferEdit().queue(x -> {}, t -> {});
            return;
        }
        int house = ThreadLocalRandom.current().nextInt(3);
        GameOutcome outcome = settleOnce(payoutFor(player, house));
        if(outcome == null)
        {
            if(!event.isAcknowledged())
                event.deferEdit().queue(x -> {}, t -> {});
            return;
        }
        editResult(event, finalEmbed(player, house, outcome));
    }

    @Override
    protected void onTimeout()
    {
        int player = ThreadLocalRandom.current().nextInt(3); // auto-play (same EV as playing)
        int house = ThreadLocalRandom.current().nextInt(3);
        GameOutcome outcome = settleOnce(payoutFor(player, house));
        if(outcome != null)
            editResultById(finalEmbed(player, house, outcome));
    }

    private long payoutFor(int player, int house)
    {
        if(player == house)
            return wager; // push
        boolean win = (player == 0 && house == 2) || (player == 1 && house == 0) || (player == 2 && house == 1);
        return win ? (long) Math.floor(wager * WIN_MULTIPLIER) : 0;
    }

    private MessageEmbed finalEmbed(int player, int house, GameOutcome outcome)
    {
        String detail = "You played " + EMOJI[player] + " **" + NAMES[player] + "**\n"
                + "The bot played " + EMOJI[house] + " **" + NAMES[house] + "**";
        return GameEmbeds.result("✊ Rock Paper Scissors", detail, outcome, wager);
    }

    private static int parseMove(String action)
    {
        // action is "rps:<n>"
        if(action.length() == 5 && action.startsWith("rps:"))
        {
            char c = action.charAt(4);
            if(c >= '0' && c <= '2')
                return c - '0';
        }
        return -1;
    }

    public static MessageEmbed panel(long wager)
    {
        return GameEmbeds.live("✊ Rock Paper Scissors",
                "You bet " + EconomyService.coins(wager) + ". Pick your move!");
    }
}
