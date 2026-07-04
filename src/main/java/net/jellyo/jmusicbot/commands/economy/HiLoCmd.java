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
import com.jagrosh.jmusicbot.commands.economy.games.HiLoSession;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.games.HiLoGame;
import java.util.concurrent.ThreadLocalRandom;

/** Higher-or-Lower: build a streak of correct calls, cash out before a miss. */
public class HiLoCmd extends InteractiveGameCommand
{
    public HiLoCmd(Bot bot)
    {
        super(bot, "hilo", "guess higher or lower to build a streak", "<amount>");
        this.aliases = bot.getConfig().getAliases("hilo").length == 0
                ? new String[]{"higherlower"}
                : bot.getConfig().getAliases("hilo");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        if(!requireGuild(ctx))
            return;
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 1 || tokens[0].isEmpty())
        {
            ctx.replyError("Usage: `hilo <amount>` — then guess higher or lower.");
            return;
        }
        EscrowedWager w = takeWager(ctx, economy, tokens[0], HiLoSession.SIZING_MULTIPLIER);
        if(w == null)
            return;
        long amount = w.amount();
        int firstCard = HiLoGame.draw(ThreadLocalRandom.current());
        HiLoSession session = new HiLoSession(bot, ctx.getAuthor().getIdLong(),
                ctx.getAuthor().getEffectiveName(), ctx.getGuild().getIdLong(),
                ctx.getChannel().getIdLong(), amount, w.id(), firstCard);
        start(ctx, session, HiLoSession.panel(firstCard, amount, 0, amount),
                HiLoSession.buttons(firstCard), DEFAULT_TIMEOUT_MS);
    }
}
