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
import com.jagrosh.jmusicbot.commands.economy.games.TriviaSession;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.games.TriviaBank;
import com.jagrosh.jmusicbot.economy.games.TriviaBank.Question;
import java.util.concurrent.ThreadLocalRandom;

/** Answer a trivia question for coins, on a short cooldown. */
public class TriviaCmd extends InteractiveGameCommand
{
    private static final long TRIVIA_TIMEOUT_MS = 30_000;

    public TriviaCmd(Bot bot)
    {
        super(bot, "trivia", "answer a question for coins", "");
        this.aliases = bot.getConfig().getAliases("trivia").length == 0
                ? new String[]{"quiz"}
                : bot.getConfig().getAliases("trivia");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        if(!requireGuild(ctx))
            return;
        long cooldown = economy.tryStartTrivia(ctx.getAuthor());
        if(cooldown > 0)
        {
            ctx.replyWarning("You've answered recently — try again in **" + formatDuration(cooldown) + "**.");
            return;
        }
        Question question = TriviaBank.random(ThreadLocalRandom.current());
        TriviaSession session = new TriviaSession(bot, ctx.getAuthor().getIdLong(),
                ctx.getAuthor().getEffectiveName(), ctx.getGuild().getIdLong(),
                ctx.getChannel().getIdLong(), question);
        start(ctx, session, session.panel(), session.buttons(), TRIVIA_TIMEOUT_MS);
    }
}
