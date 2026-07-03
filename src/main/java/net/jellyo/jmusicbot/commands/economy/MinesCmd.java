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
import com.jagrosh.jmusicbot.commands.economy.games.MinesSession;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.games.MinesGame;
import java.util.concurrent.ThreadLocalRandom;

/** Mines: reveal safe tiles for a growing multiplier, cash out before you hit a mine. */
public class MinesCmd extends InteractiveGameCommand
{
    public MinesCmd(Bot bot)
    {
        super(bot, "mines", "reveal safe tiles and dodge the mines", "<amount> [bombs 1-10]");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        if(!requireGuild(ctx))
            return;
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 1 || tokens[0].isEmpty())
        {
            ctx.replyError("Usage: `mines <amount> [bombs]` — bombs 1-10 (default " + MinesGame.DEFAULT_BOMBS + ").");
            return;
        }
        int bombs = MinesGame.DEFAULT_BOMBS;
        if(tokens.length >= 2 && tokens[1].matches("\\d{1,2}"))
        {
            bombs = Integer.parseInt(tokens[1]);
            if(bombs < MinesGame.MIN_BOMBS || bombs > MinesGame.MAX_BOMBS)
            {
                ctx.replyError("Choose between " + MinesGame.MIN_BOMBS + " and " + MinesGame.MAX_BOMBS + " bombs.");
                return;
            }
        }
        long amount = takeWager(ctx, economy, tokens[0], MinesSession.SIZING_MULTIPLIER);
        if(amount < 0)
            return;
        MinesSession session = new MinesSession(bot, ctx.getAuthor().getIdLong(),
                ctx.getAuthor().getEffectiveName(), ctx.getGuild().getIdLong(),
                ctx.getChannel().getIdLong(), amount, bombs, ThreadLocalRandom.current());
        start(ctx, session, session.panel(), session.grid(false), DEFAULT_TIMEOUT_MS);
    }
}
