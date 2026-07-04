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
import com.jagrosh.jmusicbot.commands.economy.games.DoubleSession;
import com.jagrosh.jmusicbot.commands.economy.games.GameEmbeds;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.EconomyService.GameOutcome;
import com.jagrosh.jmusicbot.economy.games.DoubleGame;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Double or Nothing": one flip to start; on a win you may keep doubling (with
 * decaying odds) or cash out.
 */
public class DoubleCmd extends InteractiveGameCommand
{
    public DoubleCmd(Bot bot)
    {
        super(bot, "double", "flip to double your coins, again and again", "<amount>");
        this.aliases = bot.getConfig().getAliases("double").length == 0
                ? new String[]{"dbl", "doubleornothing"}
                : bot.getConfig().getAliases("double");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        if(!requireGuild(ctx))
            return;
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 1 || tokens[0].isEmpty())
        {
            ctx.replyError("Usage: `double <amount>` — flip to double, then push your luck or cash out.");
            return;
        }
        EscrowedWager w = takeWager(ctx, economy, tokens[0], DoubleGame.topMultiplier());
        if(w == null)
            return;
        long amount = w.amount();

        long authorId = ctx.getAuthor().getIdLong();
        boolean firstWin = ThreadLocalRandom.current().nextDouble() < DoubleGame.winChance(0);
        if(!firstWin)
        {
            GameOutcome outcome = economy.settleGame(authorId, amount, 0, ctx.getChannel(), w.id());
            ctx.reply(embedMessage(GameEmbeds.result("🎲 Double or Nothing",
                    "💥 The first flip missed — nothing this time.", outcome, amount)));
            return;
        }
        long pot = amount * 2;
        DoubleSession session = new DoubleSession(bot, authorId, ctx.getAuthor().getEffectiveName(),
                ctx.getGuild().getIdLong(), ctx.getChannel().getIdLong(), amount, w.id(), pot, 1);
        start(ctx, session, DoubleSession.panel(pot, 1, amount), DoubleSession.buttons(1), DEFAULT_TIMEOUT_MS);
    }
}
