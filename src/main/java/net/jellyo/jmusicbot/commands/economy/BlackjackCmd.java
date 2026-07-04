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
import com.jagrosh.jmusicbot.commands.economy.games.BlackjackSession;
import com.jagrosh.jmusicbot.commands.economy.games.GameEmbeds;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.EconomyService.GameOutcome;
import com.jagrosh.jmusicbot.economy.games.BlackjackGame;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Blackjack against the dealer, with hit / stand / double. */
public class BlackjackCmd extends InteractiveGameCommand
{
    public BlackjackCmd(Bot bot)
    {
        super(bot, "blackjack", "play blackjack against the dealer", "<amount>");
        this.aliases = bot.getConfig().getAliases("blackjack").length == 0
                ? new String[]{"bj", "21"}
                : bot.getConfig().getAliases("blackjack");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        if(!requireGuild(ctx))
            return;
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 1 || tokens[0].isEmpty())
        {
            ctx.replyError("Usage: `blackjack <amount>` — then hit, stand or double.");
            return;
        }
        EscrowedWager w = takeWager(ctx, economy, tokens[0], BlackjackSession.SIZING_MULTIPLIER);
        if(w == null)
            return;
        long amount = w.amount();

        long authorId = ctx.getAuthor().getIdLong();
        List<Integer> player = new ArrayList<>(List.of(
                BlackjackGame.draw(ThreadLocalRandom.current()), BlackjackGame.draw(ThreadLocalRandom.current())));
        List<Integer> dealer = new ArrayList<>(List.of(
                BlackjackGame.draw(ThreadLocalRandom.current()), BlackjackGame.draw(ThreadLocalRandom.current())));

        boolean playerBj = BlackjackGame.isBlackjack(player);
        boolean dealerBj = BlackjackGame.isBlackjack(dealer);
        if(playerBj || dealerBj)
        {
            long payout;
            String detail;
            if(playerBj && dealerBj)
            {
                payout = amount;
                detail = "Both have blackjack — push.";
            }
            else if(playerBj)
            {
                payout = (long) Math.floor(amount * BlackjackGame.BLACKJACK_MULTIPLIER);
                detail = "♠️ **Blackjack!** Paid 3:2.";
            }
            else
            {
                payout = 0;
                detail = "The dealer has blackjack.";
            }
            GameOutcome outcome = economy.settleGame(authorId, amount, payout, ctx.getChannel(), w.id());
            String hands = "**You:** " + BlackjackSession.cards(player) + " (" + BlackjackGame.bestValue(player) + ")\n"
                    + "**Dealer:** " + BlackjackSession.cards(dealer) + " (" + BlackjackGame.bestValue(dealer) + ")";
            ctx.reply(embedMessage(GameEmbeds.result("🂡 Blackjack", detail + "\n" + hands, outcome, amount)));
            return;
        }

        BlackjackSession session = new BlackjackSession(bot, authorId, ctx.getAuthor().getEffectiveName(),
                ctx.getGuild().getIdLong(), ctx.getChannel().getIdLong(), amount, w.id(), player, dealer);
        start(ctx, session, session.panel(), session.buttons(), DEFAULT_TIMEOUT_MS);
    }
}
