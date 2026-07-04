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
import com.jagrosh.jmusicbot.commands.economy.games.GameEmbeds;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.EconomyService.GameOutcome;
import com.jagrosh.jmusicbot.economy.GambleGames;
import com.jagrosh.jmusicbot.economy.GambleGames.Game;
import com.jagrosh.jmusicbot.economy.GambleGames.Result;
import com.jagrosh.jmusicbot.economy.Payouts;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/**
 * Bet coins on a game of chance (coinflip, dice or slots). The wager is debited
 * atomically before the game resolves, then settled through the shared game
 * settlement so it earns XP, honours the per-game table limit and can pay the
 * loyalty rebate — just like the other casino games.
 */
public class GambleCmd extends EconomyCommand
{
    public GambleCmd(Bot bot)
    {
        super(bot, "gamble", "bet coins on coinflip, dice or slots", "<amount|all|half> [coinflip|dice|slots]");
        this.aliases = bot.getConfig().getAliases("gamble").length == 0
                ? new String[]{"bet", "casino"}
                : bot.getConfig().getAliases("gamble");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        String args = ctx.getArgs() == null ? "" : ctx.getArgs().trim();
        if(args.isEmpty())
        {
            ctx.replyError("Usage: `gamble <amount|all|half> [coinflip|dice|slots]`");
            return;
        }

        String amountToken = null;
        String gameToken = null;
        for(String token : args.split("\\s+"))
        {
            if(amountToken == null && isAmountToken(token))
                amountToken = token;
            else if(gameToken == null && isGameToken(token))
                gameToken = token;
        }
        if(amountToken == null)
        {
            ctx.replyError("Tell me how much to bet, e.g. `gamble 100 slots`.");
            return;
        }

        long authorId = ctx.getAuthor().getIdLong();
        long balance = economy.getBalance(authorId);
        if(balance < Payouts.MIN_BET)
        {
            ctx.replyWarning("You need at least " + EconomyService.coins(Payouts.MIN_BET)
                    + " to gamble. Try `daily` to top up.");
            return;
        }
        Game game = Game.from(gameToken);
        long maxBet = Payouts.maxBetFor(sizingFor(game));
        long amount = parseAmount(amountToken, balance);
        if(amount < Payouts.MIN_BET)
        {
            ctx.replyError("The minimum bet is " + EconomyService.coins(Payouts.MIN_BET) + ".");
            return;
        }
        if(amount > maxBet)
        {
            ctx.replyError("The table limit for " + game.name().toLowerCase(Locale.ROOT) + " is "
                    + EconomyService.coins(maxBet) + " (higher-payout games have lower limits).");
            return;
        }
        if(amount > balance)
        {
            ctx.replyError("You only have " + EconomyService.coins(balance) + ".");
            return;
        }
        String escrowId = economy.escrow(authorId, amount, name);
        if(escrowId == null)
        {
            ctx.replyError("You don't have enough coins for that bet.");
            return;
        }

        Result result = GambleGames.play(game, amount, ThreadLocalRandom.current());
        GameOutcome outcome = economy.settleGame(authorId, amount, result.getPayout(), ctx.getChannel(), escrowId);
        ctx.reply(new MessageCreateBuilder()
                .setEmbeds(GameEmbeds.result(gameTitle(game), result.getDetail(), outcome, amount))
                .build());
    }

    /** The top stake-inclusive multiplier a game can pay, used to size its table limit. */
    private static double sizingFor(Game game)
    {
        switch(game)
        {
            case SLOTS: return 40.0; // triple-seven jackpot
            case DICE: return 1.7;
            case COINFLIP:
            default: return 1.95;
        }
    }

    private static boolean isAmountToken(String token)
    {
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("all") || t.equals("max") || t.equals("allin") || t.equals("half") || t.matches("\\d{1,15}");
    }

    private static boolean isGameToken(String token)
    {
        switch(token.toLowerCase(Locale.ROOT))
        {
            case "coinflip": case "coin": case "flip": case "cf":
            case "dice": case "roll":
            case "slots": case "slot": case "spin":
                return true;
            default:
                return false;
        }
    }

    private static long parseAmount(String token, long balance)
    {
        String t = token.toLowerCase(Locale.ROOT);
        if(t.equals("all") || t.equals("max") || t.equals("allin"))
            return balance;
        if(t.equals("half"))
            return balance / 2;
        try
        {
            return Long.parseLong(t);
        }
        catch(NumberFormatException ex)
        {
            return -1;
        }
    }

    private static String gameTitle(Game game)
    {
        switch(game)
        {
            case DICE:
                return "🎲 Dice";
            case SLOTS:
                return "🎰 Slots";
            case COINFLIP:
            default:
                return "🪙 Coinflip";
        }
    }
}
