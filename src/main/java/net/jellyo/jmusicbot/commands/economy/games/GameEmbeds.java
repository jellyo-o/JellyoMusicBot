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

import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.EconomyService.GameOutcome;
import java.awt.Color;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

/** Shared embed builders for the casino games (final results and live/interactive panels). */
public final class GameEmbeds
{
    public static final Color WIN = new Color(0x2ECC71);
    public static final Color LOSS = new Color(0xE74C3C);
    public static final Color NEUTRAL = new Color(0xF1C40F);
    public static final Color LIVE = new Color(0x5865F2);

    private GameEmbeds() {}

    /** The standard settled-result embed, surfacing the net change, XP and any rebate. */
    public static MessageEmbed result(String title, String detail, GameOutcome outcome, long wager)
    {
        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(outcome.isWin() ? WIN : (outcome.getNet() == 0 ? NEUTRAL : LOSS));
        eb.setTitle(title);
        if(detail != null && !detail.isEmpty())
            eb.setDescription(detail);
        String result;
        if(outcome.isWin())
            result = "🎉 **+" + EconomyService.coins(outcome.getNet()) + "**";
        else if(outcome.getNet() == 0)
            result = "➖ Push — your bet is returned";
        else
            result = "💀 **-" + EconomyService.coins(-outcome.getNet()) + "**";
        eb.addField("Result", result, true);
        eb.addField("💰 Balance", EconomyService.coins(outcome.getNewBalance()), true);
        StringBuilder footer = new StringBuilder("Bet ")
                .append(String.format("%,d", wager)).append(' ').append(EconomyService.CURRENCY_NAME)
                .append(" • +").append(outcome.getXpAwarded()).append(" XP");
        if(outcome.getRebate() > 0)
            footer.append(" • VIP rebate +").append(String.format("%,d", outcome.getRebate())).append(" 🪙");
        eb.setFooter(footer.toString(), null);
        return eb.build();
    }

    /** A live/interactive panel embed (in-progress state). */
    public static MessageEmbed live(String title, String description)
    {
        return new EmbedBuilder().setColor(LIVE).setTitle(title).setDescription(description).build();
    }
}
