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
import com.jagrosh.jmusicbot.economy.games.TotoGame;
import com.jagrosh.jmusicbot.economy.games.TotoGame.Draw;
import com.jagrosh.jmusicbot.economy.games.TotoGame.Group;
import com.jagrosh.jmusicbot.economy.games.TotoGame.Outcome;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * Singapore TOTO, played instant fixed-odds. Ordinary (6 numbers), System (7-12),
 * System Roll (5 + rolling), or Quick Pick. Prizes fall into the seven Singapore
 * groups; a System entry can win several.
 */
public class TotoCmd extends WagerGameCommand
{
    public TotoCmd(Bot bot)
    {
        super(bot, "toto", "pick 6 of 49, Singapore TOTO style",
                "<n1..n6 | 7-12 numbers | roll n1..n5 | qp[7-12]> <amount>");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 2)
        {
            ctx.replyError("Usage: `toto <6-12 numbers | roll n1..n5 | qp[7-12]> <amount>`\n"
                    + "e.g. `toto 4 8 15 16 23 42 100`, `toto qp 50`, `toto roll 1 2 3 4 5 20`, `toto qp8 10`.");
            return;
        }
        long unit;
        try
        {
            unit = Long.parseLong(tokens[tokens.length - 1]);
        }
        catch(NumberFormatException ex)
        {
            ctx.replyError("The last value must be your stake, e.g. `toto qp 100`.");
            return;
        }
        String[] spec = new String[tokens.length - 1];
        System.arraycopy(tokens, 0, spec, 0, spec.length);

        List<Set<Integer>> combinations = expand(spec);
        if(combinations == null)
        {
            ctx.replyError("Pick 6-12 distinct numbers (1-49), `roll` + 5 numbers, or `qp`/`qp7`..`qp12`.");
            return;
        }

        long maxUnit = Payouts.maxBetFor(TotoGame.PRACTICAL_TOP_MULTIPLIER);
        if(unit < Payouts.MIN_BET)
        {
            ctx.replyError("The minimum stake is " + EconomyService.coins(Payouts.MIN_BET) + " per board.");
            return;
        }
        if(unit > maxUnit)
        {
            ctx.replyError("The TOTO stake limit is " + EconomyService.coins(maxUnit) + " per board.");
            return;
        }
        long totalStake = (long) combinations.size() * unit;
        long balance = economy.getBalance(ctx.getAuthor().getIdLong());
        if(totalStake > balance)
        {
            ctx.replyError("That entry costs " + EconomyService.coins(totalStake) + " (" + combinations.size()
                    + " boards x " + EconomyService.coins(unit) + ") — you only have " + EconomyService.coins(balance) + ".");
            return;
        }
        if(!economy.trySpend(ctx.getAuthor().getIdLong(), totalStake))
        {
            ctx.replyError("You don't have enough coins for that entry.");
            return;
        }

        final long authorId = ctx.getAuthor().getIdLong();
        final MessageChannel channel = ctx.getChannel();
        final Draw draw = TotoGame.draw(ThreadLocalRandom.current());
        final Outcome outcome = TotoGame.settle(combinations, unit, draw);
        final int boards = combinations.size();

        List<MessageEmbed> frames = new ArrayList<>();
        for(int i = 0; i < 4; i++)
            frames.add(spinEmbed("🎯 TOTO Draw", "Drawing 6 + additional…\n" + boards + " board(s)"));

        sendAnimated(ctx, embedMessage(frames.get(0)), frames, 550, msg ->
        {
            GameOutcome settled = economy.settleGame(authorId, outcome.getTotalStake(), outcome.getTotalPayout(), channel);
            msg.editMessageEmbeds(resultEmbed(draw, outcome, boards, settled)).queue();
        });
    }

    private MessageEmbed resultEmbed(Draw draw, Outcome outcome, int boards, GameOutcome settled)
    {
        String winning = draw.getWinning().stream().sorted().map(String::valueOf).collect(Collectors.joining(" "));
        String detail = "**Winning:** " + winning + "  ➕ **" + draw.getAdditional() + "**\n"
                + boards + " board(s)\n"
                + (outcome.getWins().isEmpty()
                        ? "No winning group this draw."
                        : outcome.getWins().stream()
                                .map(w -> groupName(w.getGroup()) + " — " + EconomyService.coins(w.getPayout()))
                                .collect(Collectors.joining("\n")));
        return resultEmbed("🎯 Singapore TOTO", detail, settled, outcome.getTotalStake());
    }

    private static String groupName(Group group)
    {
        switch(group)
        {
            case G1: return "💎 Group 1 (6)";
            case G2: return "Group 2 (5+add)";
            case G3: return "Group 3 (5)";
            case G4: return "Group 4 (4+add)";
            case G5: return "Group 5 (4)";
            case G6: return "Group 6 (3+add)";
            case G7: return "Group 7 (3)";
            default: return "-";
        }
    }

    /** Expands a TOTO entry spec (numbers / roll / quick-pick) into 6-number boards. Null if invalid. */
    static List<Set<Integer>> expand(String[] spec)
    {
        if(spec.length == 0)
            return null;
        String first = spec[0].toLowerCase();

        if(first.startsWith("qp"))
        {
            int count = TotoGame.PICK;
            if(first.length() > 2)
            {
                try
                {
                    count = Integer.parseInt(first.substring(2));
                }
                catch(NumberFormatException ex)
                {
                    return null;
                }
            }
            else if(spec.length == 2 && spec[1].matches("\\d+"))
            {
                count = Integer.parseInt(spec[1]);
            }
            if(count < TotoGame.PICK || count > TotoGame.MAX_SYSTEM)
                return null;
            return TotoGame.combinations(TotoGame.quickPick(count, ThreadLocalRandom.current()));
        }

        if(first.equals("roll"))
        {
            List<Integer> five = parseNumbers(spec, 1);
            if(five == null || five.size() != 5)
                return null;
            return TotoGame.roll(five);
        }

        List<Integer> numbers = parseNumbers(spec, 0);
        if(numbers == null || numbers.size() < TotoGame.PICK || numbers.size() > TotoGame.MAX_SYSTEM)
            return null;
        return TotoGame.combinations(numbers);
    }

    private static List<Integer> parseNumbers(String[] spec, int start)
    {
        Set<Integer> numbers = new LinkedHashSet<>();
        for(int i = start; i < spec.length; i++)
        {
            if(!spec[i].matches("\\d{1,2}"))
                return null;
            int n = Integer.parseInt(spec[i]);
            if(n < 1 || n > TotoGame.MAX_NUMBER || !numbers.add(n))
                return null; // out of range or duplicate
        }
        return new ArrayList<>(numbers);
    }
}
