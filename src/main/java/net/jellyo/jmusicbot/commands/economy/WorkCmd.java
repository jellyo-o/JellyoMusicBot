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
import com.jagrosh.jmusicbot.economy.EconomyService.WorkResult;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/** Earn a few coins on a short cooldown with a random odd job. */
public class WorkCmd extends EconomyCommand
{
    private static final String[] JOBS = {
            "You DJ'd a wedding and kept the dancefloor packed.",
            "You busked on the corner and the crowd loved it.",
            "You tuned the neighbourhood jukebox back to life.",
            "You sold a mixtape to a very enthusiastic fan.",
            "You covered a shift at the record store.",
            "You scored an indie short film and got paid in coins.",
            "You taught a beginner their first three chords.",
            "You ran sound for the open-mic night.",
            "You fixed a jammed vinyl press just in time.",
            "You hosted trivia at the local cafe."
    };

    public WorkCmd(Bot bot)
    {
        super(bot, "work", "earn coins with a quick job", "");
        this.aliases = bot.getConfig().getAliases("work").length == 0
                ? new String[]{"job"}
                : bot.getConfig().getAliases("work");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        WorkResult result = economy.claimWork(ctx.getAuthor(), ctx.getChannel());
        if(!result.isWorked())
        {
            ctx.replyWarning("You're worn out — rest for **" + formatDuration(result.getSecondsUntilNext())
                    + "** before working again.");
            return;
        }
        String job = JOBS[ThreadLocalRandom.current().nextInt(JOBS.length)];
        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(ECONOMY_COLOR);
        eb.setTitle("💼 Work");
        eb.setDescription(job + "\nYou earned **" + EconomyService.coins(result.getAmount()) + "**.");
        eb.addField("💰 Balance", EconomyService.coins(result.getNewBalance()), true);
        eb.setFooter("+" + result.getXp() + " XP", null);
        ctx.reply(new MessageCreateBuilder().setEmbeds(eb.build()).build());
    }
}
