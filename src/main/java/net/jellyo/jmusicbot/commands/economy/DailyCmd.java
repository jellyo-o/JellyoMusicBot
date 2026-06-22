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
package com.jagrosh.jmusicbot.commands.economy;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.EconomyService.DailyResult;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/**
 * Claims the once-per-day chest. Consecutive days build a streak that increases
 * the reward up to a cap.
 */
public class DailyCmd extends EconomyCommand
{
    public DailyCmd(Bot bot)
    {
        super(bot, "daily", "claims your daily coin chest", "");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        DailyResult result = economy.claimDaily(ctx.getAuthor(), ctx.getChannel());
        if(!result.isClaimed())
        {
            ctx.replyWarning("You've already claimed your daily chest. Come back in **"
                    + formatDuration(result.getSecondsUntilNext()) + "**."
                    + (result.getStreak() > 0 ? "  (Current streak: " + result.getStreak() + ")" : ""));
            return;
        }

        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(ECONOMY_COLOR);
        eb.setTitle("📅 Daily Chest Claimed!");
        eb.setDescription("You received **" + EconomyService.coins(result.getAmount())
                + "** and **" + String.format("%,d", result.getXp()) + " XP**!");
        eb.addField("🔥 Streak", result.getStreak() + (result.getStreak() == 1 ? " day" : " days"), true);
        eb.addField("💰 Balance", EconomyService.coins(result.getNewBalance()), true);
        eb.setFooter("Come back tomorrow to grow your streak", null);
        ctx.reply(new MessageCreateBuilder().setEmbeds(eb.build()).build());
    }
}
