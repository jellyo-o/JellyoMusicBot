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
import com.jagrosh.jmusicbot.economy.GambleGames;
import com.jagrosh.jmusicbot.economy.GambleGames.Game;
import com.jagrosh.jmusicbot.economy.GambleGames.Result;
import java.awt.Color;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/**
 * Bet coins on a game of chance (coinflip, dice or slots). The wager is debited
 * atomically before the game resolves, so it can never go negative.
 */
public class GambleCmd extends EconomyCommand
{
    private static final long MIN_BET = 10;
    private static final long MAX_BET = 1_000_000;
    private static final Color WIN_COLOR = new Color(0x2ECC71);
    private static final Color LOSS_COLOR = new Color(0xE74C3C);

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
        if(balance < MIN_BET)
        {
            ctx.replyWarning("You need at least " + EconomyService.coins(MIN_BET) + " to gamble. Try `daily` to top up.");
            return;
        }
        long amount = parseAmount(amountToken, balance);
        if(amount < MIN_BET)
        {
            ctx.replyError("The minimum bet is " + EconomyService.coins(MIN_BET) + ".");
            return;
        }
        if(amount > MAX_BET)
        {
            ctx.replyError("The maximum bet is " + EconomyService.coins(MAX_BET) + ".");
            return;
        }
        if(amount > balance)
        {
            ctx.replyError("You only have " + EconomyService.coins(balance) + ".");
            return;
        }
        if(!economy.trySpend(authorId, amount))
        {
            ctx.replyError("You don't have enough coins for that bet.");
            return;
        }

        Game game = Game.from(gameToken);
        Result result = GambleGames.play(game, amount, ThreadLocalRandom.current());
        long net = economy.settleGamble(authorId, amount, result.getPayout(), ctx.getChannel());
        long newBalance = economy.getBalance(authorId);

        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(result.isWon() ? WIN_COLOR : LOSS_COLOR);
        eb.setTitle(gameTitle(game));
        eb.setDescription(result.getDetail());
        eb.addField(result.isWon() ? "🎉 You won" : "💀 You lost",
                result.isWon() ? "**+" + EconomyService.coins(net) + "**" : "**-" + EconomyService.coins(amount) + "**",
                true);
        eb.addField("💰 New balance", EconomyService.coins(newBalance), true);
        eb.setFooter("Bet " + String.format("%,d", amount) + " " + EconomyService.CURRENCY_NAME, null);
        ctx.reply(new MessageCreateBuilder().setEmbeds(eb.build()).build());
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
