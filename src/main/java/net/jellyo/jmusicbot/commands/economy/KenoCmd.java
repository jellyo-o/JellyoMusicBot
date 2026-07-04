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
import com.jagrosh.jmusicbot.economy.games.KenoGame;
import com.jagrosh.jmusicbot.economy.games.KenoGame.Result;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * Keno: pick {@value com.jagrosh.jmusicbot.economy.games.KenoGame#PICKS} numbers
 * (1-40) or let it quick-pick; the house draws ten. Match all four for the 100x
 * jackpot. The draw is revealed ball by ball.
 */
public class KenoCmd extends WagerGameCommand
{
    public KenoCmd(Bot bot)
    {
        super(bot, "keno", "pick numbers and match the draw", "<amount> [n1 n2 n3 n4]");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 1 || tokens[0].isEmpty())
        {
            ctx.replyError("Usage: `keno <amount> [n1 n2 n3 n4]` — pick " + KenoGame.PICKS
                    + " numbers 1-" + KenoGame.POOL + ", or omit them to quick-pick.");
            return;
        }

        Set<Integer> picks;
        if(tokens.length >= 1 + KenoGame.PICKS)
        {
            picks = new LinkedHashSet<>();
            for(int i = 1; i <= KenoGame.PICKS; i++)
            {
                if(!tokens[i].matches("\\d{1,2}"))
                {
                    ctx.replyError("Picks must be numbers between 1 and " + KenoGame.POOL + ".");
                    return;
                }
                int n = Integer.parseInt(tokens[i]);
                if(n < 1 || n > KenoGame.POOL || !picks.add(n))
                {
                    ctx.replyError("Pick " + KenoGame.PICKS + " *distinct* numbers between 1 and " + KenoGame.POOL + ".");
                    return;
                }
            }
        }
        else
        {
            picks = quickPick();
        }

        EscrowedWager w = takeWager(ctx, economy, tokens[0], KenoGame.PRACTICAL_TOP_MULTIPLIER);
        if(w == null)
            return;
        long amount = w.amount();

        final long authorId = ctx.getAuthor().getIdLong();
        final MessageChannel channel = ctx.getChannel();
        final Set<Integer> chosen = picks;
        final Result result = KenoGame.play(picks, amount, ThreadLocalRandom.current());
        final String pickList = chosen.stream().sorted().map(String::valueOf).collect(Collectors.joining(" "));

        List<MessageEmbed> frames = new ArrayList<>();
        for(int i = 0; i < 4; i++)
            frames.add(spinEmbed("🔢 Keno", "Your picks: **" + pickList + "**\nDrawing the balls… 🎱"));

        // Settle synchronously, before the cosmetic animation, so the debit and payout are one crash-safe unit.
        final GameOutcome outcome = economy.settleGame(authorId, amount, result.getPayout(), channel, w.id());
        final String drawn = Arrays.stream(result.getDrawn()).sorted()
                .mapToObj(n -> chosen.contains(n) ? "**[" + n + "]**" : String.valueOf(n))
                .collect(Collectors.joining(" "));
        final String detail = "Your picks: **" + pickList + "**\nDraw: " + drawn
                + "\nHits: **" + result.getHits() + "**"
                + (result.getHits() == KenoGame.PICKS ? " — 💎 JACKPOT!" : "");
        final MessageEmbed reveal = resultEmbed("🔢 Keno", detail, outcome, amount);
        sendAnimated(ctx, embedMessage(frames.get(0)), frames, 500, msg -> msg.editMessageEmbeds(reveal).queue());
    }

    private static Set<Integer> quickPick()
    {
        Set<Integer> picks = new LinkedHashSet<>();
        while(picks.size() < KenoGame.PICKS)
            picks.add(ThreadLocalRandom.current().nextInt(KenoGame.POOL) + 1);
        return picks;
    }
}
