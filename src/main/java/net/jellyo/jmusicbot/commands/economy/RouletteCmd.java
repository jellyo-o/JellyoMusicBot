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
import com.jagrosh.jmusicbot.economy.games.RouletteGame;
import com.jagrosh.jmusicbot.economy.games.RouletteGame.BetType;
import com.jagrosh.jmusicbot.economy.games.RouletteGame.Color;
import com.jagrosh.jmusicbot.economy.games.RouletteGame.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * European roulette: bet on red/black, even/odd, low/high (2x), a dozen (3x) or a
 * straight-up number 0-36 (36x). The wheel spins before it settles.
 */
public class RouletteCmd extends WagerGameCommand
{
    public RouletteCmd(Bot bot)
    {
        super(bot, "roulette", "spin the roulette wheel",
                "<amount> <red|black|even|odd|low|high|dozen1|dozen2|dozen3|number> [0-36]");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 2 || tokens[0].isEmpty())
        {
            ctx.replyError("Usage: `roulette <amount> <red|black|even|odd|low|high|dozen1-3|number> [0-36]`.");
            return;
        }
        BetType bet = BetType.from(tokens[1]);
        int straightTarget = -1;
        if(bet == BetType.STRAIGHT)
        {
            String numToken = tokens.length >= 3 ? tokens[2] : tokens[1];
            if(!numToken.matches("\\d{1,2}") || Integer.parseInt(numToken) > 36)
            {
                ctx.replyError("For a straight-up bet, pick a number **0-36**, e.g. `roulette 100 number 17`.");
                return;
            }
            straightTarget = Integer.parseInt(numToken);
        }

        EscrowedWager w = takeWager(ctx, economy, tokens[0], bet.multiplier());
        if(w == null)
            return;
        long amount = w.amount();

        final long authorId = ctx.getAuthor().getIdLong();
        final MessageChannel channel = ctx.getChannel();
        final Result result = RouletteGame.play(bet, straightTarget, amount, ThreadLocalRandom.current());
        final String betLabel = betLabel(bet, straightTarget);

        List<MessageEmbed> frames = new ArrayList<>();
        for(int i = 0; i < 4; i++)
        {
            int n = ThreadLocalRandom.current().nextInt(37);
            frames.add(spinEmbed("🎡 Roulette", "The ball rattles around… " + slot(n, RouletteGame.colorOf(n))
                    + "\nYou bet **" + betLabel + "**"));
        }

        // Settle synchronously, before the cosmetic animation, so the debit and payout are one crash-safe unit.
        final GameOutcome outcome = economy.settleGame(authorId, amount, result.getPayout(), channel, w.id());
        final String detail = "The ball settled on " + slot(result.getNumber(), result.getColor())
                + "\nYou bet **" + betLabel + "** — " + (result.isWon() ? "winner!" : "no luck.");
        final MessageEmbed reveal = resultEmbed("🎡 Roulette", detail, outcome, amount);
        sendAnimated(ctx, embedMessage(frames.get(0)), frames, 550, msg -> msg.editMessageEmbeds(reveal).queue());
    }

    private static String slot(int number, Color color)
    {
        String dot = color == Color.RED ? "🔴" : color == Color.BLACK ? "⚫" : "🟢";
        return dot + " **" + number + "**";
    }

    private static String betLabel(BetType bet, int straightTarget)
    {
        if(bet == BetType.STRAIGHT)
            return "number " + straightTarget;
        return bet.name().toLowerCase().replace("dozen", "dozen ");
    }
}
