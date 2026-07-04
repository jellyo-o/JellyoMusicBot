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
import com.jagrosh.jmusicbot.commands.economy.games.GameAnimator;
import com.jagrosh.jmusicbot.commands.economy.games.GameEmbeds;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.EconomyService.GameOutcome;
import com.jagrosh.jmusicbot.economy.Payouts;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

/**
 * Shared plumbing for the animated, instant-resolve wager games: parsing and
 * table-limit validation of the bet, atomic debit, the cosmetic spin animation,
 * and the standard result embed (with the XP earned and any loyalty rebate).
 */
public abstract class WagerGameCommand extends EconomyCommand
{
    protected WagerGameCommand(Bot bot, String name, String help, String arguments)
    {
        super(bot, name, help, arguments);
    }

    /**
     * Validates a bet token against the table limit for {@code sizingMultiplier} and atomically escrows it
     * (debit + durable crash-recovery record). On any problem it replies to the user and returns
     * {@code null}; on success the wager is held under the returned {@link EscrowedWager#id()}, which the
     * caller must hand to {@code settleGame}/{@code resolveEscrow} when the game ends.
     */
    protected EscrowedWager takeWager(CommandContext ctx, EconomyService economy, String token, double sizingMultiplier)
    {
        long authorId = ctx.getAuthor().getIdLong();
        long balance = economy.getBalance(authorId);
        if(balance < Payouts.MIN_BET)
        {
            ctx.replyWarning("You need at least " + EconomyService.coins(Payouts.MIN_BET)
                    + " to play. Try `daily` to top up.");
            return null;
        }
        long amount = parseAmount(token, balance);
        long maxBet = Payouts.maxBetFor(sizingMultiplier);
        if(amount < Payouts.MIN_BET)
        {
            ctx.replyError("The minimum bet is " + EconomyService.coins(Payouts.MIN_BET) + ".");
            return null;
        }
        if(amount > maxBet)
        {
            ctx.replyError("The table limit for this bet is " + EconomyService.coins(maxBet)
                    + " (higher-payout bets have lower limits).");
            return null;
        }
        if(amount > balance)
        {
            ctx.replyError("You only have " + EconomyService.coins(balance) + ".");
            return null;
        }
        String escrowId = economy.escrow(authorId, amount, name);
        if(escrowId == null)
        {
            ctx.replyError("You don't have enough coins for that bet.");
            return null;
        }
        return new EscrowedWager(amount, escrowId);
    }

    /** A wager whose stake has been escrowed (debited + crash-recorded): its amount and its escrow id. */
    public static final class EscrowedWager
    {
        private final long amount;
        private final String id;

        public EscrowedWager(long amount, String id)
        {
            this.amount = amount;
            this.id = id;
        }

        public long amount() { return amount; }
        public String id() { return id; }
    }

    /** Parses {@code all}/{@code half}/{@code max} or a plain number; {@code -1} if unparseable. */
    protected static long parseAmount(String token, long balance)
    {
        if(token == null)
            return -1;
        String t = token.trim().toLowerCase(Locale.ROOT);
        if(t.equals("all") || t.equals("max") || t.equals("allin"))
            return balance;
        if(t.equals("half"))
            return balance / 2;
        if(!t.matches("\\d{1,15}"))
            return -1;
        try
        {
            return Long.parseLong(t);
        }
        catch(NumberFormatException ex)
        {
            return -1;
        }
    }

    /**
     * Sends {@code initial}, plays the cosmetic {@code frames} on the games
     * scheduler, then invokes {@code onSettle} with the message so the caller can
     * settle the economy and edit in the final result.
     */
    protected void sendAnimated(CommandContext ctx, MessageCreateData initial, List<MessageEmbed> frames,
                                long frameMs, Consumer<Message> onSettle)
    {
        ctx.reply(initial, msg -> GameAnimator.play(bot, msg, frames, frameMs, () -> onSettle.accept(msg)));
    }

    protected static MessageCreateData embedMessage(MessageEmbed embed)
    {
        return new MessageCreateBuilder().setEmbeds(embed).build();
    }

    /** The standard result embed, surfacing the net change, XP earned and any rebate. */
    protected MessageEmbed resultEmbed(String title, String detail, GameOutcome outcome, long wager)
    {
        return GameEmbeds.result(title, detail, outcome, wager);
    }

    protected MessageEmbed spinEmbed(String title, String description)
    {
        return GameEmbeds.live(title, description);
    }
}
