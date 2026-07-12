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
import com.jagrosh.jmusicbot.commands.economy.games.DuelSession;
import com.jagrosh.jmusicbot.economy.EconomyService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.entities.User;

/** Challenge another player to a zero-sum coinflip duel for coins. */
public class DuelCmd extends InteractiveGameCommand
{
    private static final long DUEL_TIMEOUT_MS = 60_000;
    private static final Pattern MENTION = Pattern.compile("<@!?(\\d{17,19})>");
    private static final Pattern RAW_ID = Pattern.compile("\\d{17,19}");
    private static final Pattern AMOUNT = Pattern.compile("(?i)all|half|max|allin|\\d{1,15}");

    public DuelCmd(Bot bot)
    {
        super(bot, "duel", "challenge another player to a coinflip duel", "<@user> <amount>");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        if(!requireGuild(ctx))
            return;
        String args = ctx.getArgs() == null ? "" : ctx.getArgs().trim();
        long opponentId = findUserId(args);
        String amountToken = findAmountToken(args);
        if(opponentId <= 0 || amountToken == null)
        {
            ctx.replyError("Usage: `duel <@user> <amount>` — e.g. `duel @rival 500`.");
            return;
        }
        long challengerId = ctx.getAuthor().getIdLong();
        if(opponentId == challengerId)
        {
            ctx.replyError("You can't duel yourself.");
            return;
        }
        User opponent = ctx.resolveUser(opponentId);
        if(opponent == null)
        {
            ctx.replyError("I couldn't find that user.");
            return;
        }
        if(opponent.isBot())
        {
            ctx.replyError("You can't duel a bot.");
            return;
        }
        economy.ensureUser(opponent);

        // Ante is zero-sum (winner takes both), so size it like an even-money bet.
        EscrowedWager w = takeWager(ctx, economy, amountToken, 2.0);
        if(w == null)
            return;
        long ante = w.amount();

        DuelSession session = new DuelSession(bot, challengerId, ctx.getAuthor().getEffectiveName(),
                ctx.getGuild().getIdLong(), ctx.getChannel().getIdLong(), ante, w.id(),
                opponentId, opponent.getEffectiveName());
        start(ctx, session, session.panel(), session.buttons(), DUEL_TIMEOUT_MS);
    }

    static long findUserId(String args)
    {
        Matcher mention = MENTION.matcher(args);
        if(mention.find())
            return parseId(mention.group(1));
        for(String token : args.split("\\s+"))
            if(RAW_ID.matcher(token).matches())
                return parseId(token);
        return -1;
    }

    static String findAmountToken(String args)
    {
        for(String token : args.split("\\s+"))
            if(!RAW_ID.matcher(token).matches() && AMOUNT.matcher(token).matches())
                return token;
        return null;
    }

    private static long parseId(String id)
    {
        try
        {
            return Long.parseLong(id);
        }
        catch(NumberFormatException ex)
        {
            return -1;
        }
    }
}
