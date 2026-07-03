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
import com.jagrosh.jmusicbot.commands.economy.games.CrashSession;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.games.CrashGame;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Crash": watch the multiplier climb and cash out before it blows. Optionally
 * pre-set an auto-cash-out target (also required when the live game is busy).
 */
public class CrashCmd extends InteractiveGameCommand
{
    private static final long CRASH_TIMEOUT_MS = 40_000;

    public CrashCmd(Bot bot)
    {
        super(bot, "crash", "cash out before the rocket crashes", "<amount> [target multiplier]");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        if(!requireGuild(ctx))
            return;
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 1 || tokens[0].isEmpty())
        {
            ctx.replyError("Usage: `crash <amount> [target]` — e.g. `crash 100 2.5` to auto-cash at 2.5x.");
            return;
        }
        double target = 0;
        if(tokens.length >= 2)
        {
            try
            {
                target = Double.parseDouble(tokens[1]);
            }
            catch(NumberFormatException ex)
            {
                ctx.replyError("The target must be a multiplier like `2.5`.");
                return;
            }
            if(target < 1.01 || target > CrashGame.MAX_MULTIPLIER)
            {
                ctx.replyError("The target must be between 1.01 and " + (int) CrashGame.MAX_MULTIPLIER + ".");
                return;
            }
        }
        if(target == 0 && !CrashSession.liveBudgetAvailable())
        {
            ctx.replyWarning("Crash is busy right now — add an auto-cash-out target to play, e.g. `crash "
                    + tokens[0] + " 2.5`.");
            return;
        }

        long amount = takeWager(ctx, economy, tokens[0], CrashGame.MAX_MULTIPLIER);
        if(amount < 0)
            return;

        double crashPoint = CrashGame.crashPoint(ThreadLocalRandom.current());
        CrashSession session = new CrashSession(bot, ctx.getAuthor().getIdLong(),
                ctx.getAuthor().getEffectiveName(), ctx.getGuild().getIdLong(),
                ctx.getChannel().getIdLong(), amount, crashPoint, target);
        start(ctx, session, CrashSession.startPanel(amount, target), CrashSession.buttons(), CRASH_TIMEOUT_MS);
    }
}
