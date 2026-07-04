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
import com.jagrosh.jmusicbot.economy.EconomyService.GameOutcome;
import com.jagrosh.jmusicbot.economy.Payouts;
import com.jagrosh.jmusicbot.economy.games.FourDGame;
import com.jagrosh.jmusicbot.economy.games.FourDGame.BetType;
import com.jagrosh.jmusicbot.economy.games.FourDGame.Draw;
import com.jagrosh.jmusicbot.economy.games.FourDGame.Outcome;
import com.jagrosh.jmusicbot.economy.games.FourDGame.Win;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * Singapore 4-D, fixed-odds against the house. Bet a 4-digit number Big or Small,
 * a System entry (all permutations), a Roll (one rolling digit), or a Quick Pick.
 */
public class FourDCmd extends WagerGameCommand
{
    public FourDCmd(Bot bot)
    {
        super(bot, "4d", "bet a 4-digit number, Singapore 4-D style",
                "<number|Rpattern|qp> <big|small> <amount> [system]");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 3)
        {
            ctx.replyError("Usage: `4d <number|Rpattern|qp> <big|small> <amount> [system]`\n"
                    + "e.g. `4d 1234 big 100`, `4d 1234 small 50 system`, `4d R234 big 20`, `4d qp big 100`.");
            return;
        }
        String numberSpec = tokens[0];
        BetType betType = tokens[1].equalsIgnoreCase("small") ? BetType.SMALL : BetType.BIG;
        long unit;
        try
        {
            unit = Long.parseLong(tokens[2]);
        }
        catch(NumberFormatException ex)
        {
            ctx.replyError("The stake must be a number, e.g. `4d 1234 big 100`.");
            return;
        }
        boolean system = tokens.length > 3 && tokens[3].equalsIgnoreCase("system");

        Set<Integer> numbers = expand(numberSpec, system);
        if(numbers == null || numbers.isEmpty())
        {
            ctx.replyError("Give a 4-digit number (`1234`), a roll pattern (`R234`), or `qp` for a quick pick.");
            return;
        }

        // Bound the per-number unit AND the whole entry's stake to the per-round return cap, so a System
        // (up to 24 permutations) or Roll (10 numbers) entry can never stake more than it could win back.
        long maxUnit = Payouts.maxUnitForEntry(FourDGame.PRACTICAL_TOP_MULTIPLIER, numbers.size());
        if(unit < Payouts.MIN_BET)
        {
            ctx.replyError("The minimum stake is " + EconomyService.coins(Payouts.MIN_BET) + " per number.");
            return;
        }
        if(unit > maxUnit)
        {
            ctx.replyError("The stake limit for this entry is " + EconomyService.coins(maxUnit) + " per number ("
                    + numbers.size() + " number(s)).");
            return;
        }
        long totalStake = (long) numbers.size() * unit;
        long balance = economy.getBalance(ctx.getAuthor().getIdLong());
        if(totalStake > balance)
        {
            ctx.replyError("That bet costs " + EconomyService.coins(totalStake) + " (" + numbers.size()
                    + " numbers x " + EconomyService.coins(unit) + ") — you only have " + EconomyService.coins(balance) + ".");
            return;
        }
        if(!economy.trySpend(ctx.getAuthor().getIdLong(), totalStake))
        {
            ctx.replyError("You don't have enough coins for that bet.");
            return;
        }

        final long authorId = ctx.getAuthor().getIdLong();
        final MessageChannel channel = ctx.getChannel();
        final Draw draw = FourDGame.draw(ThreadLocalRandom.current());
        final Outcome outcome = FourDGame.settle(numbers, betType, unit, draw);
        final String betLabel = numbers.size() == 1
                ? FourDGame.format(numbers.iterator().next())
                : numbers.size() + " numbers (" + numberSpec.toUpperCase(Locale.ROOT) + ")";

        List<MessageEmbed> frames = new ArrayList<>();
        for(int i = 0; i < 4; i++)
            frames.add(spinEmbed("🎱 4-D Draw", "Drawing the winning numbers…\nYou bet **" + betLabel
                    + "** (" + betType.name().toLowerCase(Locale.ROOT) + ")"));

        // Settle synchronously, before the cosmetic animation, so the debit and payout are one crash-safe unit.
        final GameOutcome settled = economy.settleGame(authorId, outcome.getTotalStake(), outcome.getTotalPayout(), channel);
        final MessageEmbed reveal = resultEmbed(draw, outcome, betType, betLabel, settled);
        sendAnimated(ctx, embedMessage(frames.get(0)), frames, 550, msg -> msg.editMessageEmbeds(reveal).queue());
    }

    private MessageEmbed resultEmbed(Draw draw, Outcome outcome, BetType betType, String betLabel, GameOutcome settled)
    {
        String detail = "**Winning numbers**\n"
                + "🥇 " + FourDGame.format(draw.getFirst())
                + "  🥈 " + FourDGame.format(draw.getSecond())
                + "  🥉 " + FourDGame.format(draw.getThird()) + "\n"
                + "Your bet: **" + betLabel + "** (" + betType.name().toLowerCase(Locale.ROOT) + ")\n"
                + (outcome.getWins().isEmpty()
                        ? "No match this draw."
                        : outcome.getWins().stream()
                                .map(w -> FourDGame.format(w.getNumber()) + " — " + tierName(w.getTier()))
                                .collect(Collectors.joining("\n")));
        return resultEmbed("🎱 Singapore 4-D", detail, settled, outcome.getTotalStake());
    }

    private static String tierName(FourDGame.Tier tier)
    {
        switch(tier)
        {
            case FIRST: return "🥇 1st Prize";
            case SECOND: return "🥈 2nd Prize";
            case THIRD: return "🥉 3rd Prize";
            case STARTER: return "⭐ Starter";
            case CONSOLATION: return "🎗️ Consolation";
            default: return "-";
        }
    }

    /** Expands a 4-D bet spec into the set of numbers played. Returns null if invalid. */
    static Set<Integer> expand(String spec, boolean system)
    {
        String s = spec.trim();
        if(s.equalsIgnoreCase("qp"))
            return Set.of(ThreadLocalRandom.current().nextInt(10000));
        if(s.length() == 4 && (s.indexOf('R') >= 0 || s.indexOf('r') >= 0))
        {
            int rollAt = -1;
            int[] fixed = new int[4];
            for(int i = 0; i < 4; i++)
            {
                char c = s.charAt(i);
                if(c == 'R' || c == 'r')
                {
                    if(rollAt >= 0)
                        return null; // only one rolling digit
                    rollAt = i;
                    fixed[i] = 0;
                }
                else if(c >= '0' && c <= '9')
                {
                    fixed[i] = c - '0';
                }
                else
                {
                    return null;
                }
            }
            return rollAt < 0 ? null : FourDGame.roll(fixed, rollAt);
        }
        if(s.length() == 4 && s.chars().allMatch(c -> c >= '0' && c <= '9'))
        {
            int[] digits = new int[]{s.charAt(0) - '0', s.charAt(1) - '0', s.charAt(2) - '0', s.charAt(3) - '0'};
            if(system)
                return FourDGame.permutations(digits);
            return Set.of(digits[0] * 1000 + digits[1] * 100 + digits[2] * 10 + digits[3]);
        }
        return null;
    }
}
