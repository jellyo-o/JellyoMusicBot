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
import com.jagrosh.jmusicbot.commands.economy.games.RpsSession;
import com.jagrosh.jmusicbot.economy.EconomyService;

/** Rock-Paper-Scissors versus the bot for coins. */
public class RpsCmd extends InteractiveGameCommand
{
    public RpsCmd(Bot bot)
    {
        super(bot, "rps", "play rock paper scissors for coins", "<amount>");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        if(!requireGuild(ctx))
            return;
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 1 || tokens[0].isEmpty())
        {
            ctx.replyError("Usage: `rps <amount>` — then pick rock, paper or scissors.");
            return;
        }
        long amount = takeWager(ctx, economy, tokens[0], RpsSession.SIZING_MULTIPLIER);
        if(amount < 0)
            return;
        RpsSession session = new RpsSession(bot, ctx.getAuthor().getIdLong(),
                ctx.getAuthor().getEffectiveName(), ctx.getGuild().getIdLong(),
                ctx.getChannel().getIdLong(), amount);
        start(ctx, session, RpsSession.panel(amount), RpsSession.buttons(), DEFAULT_TIMEOUT_MS);
    }
}
