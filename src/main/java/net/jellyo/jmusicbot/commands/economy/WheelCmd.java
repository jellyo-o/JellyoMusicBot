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
import com.jagrosh.jmusicbot.economy.games.WheelGame;
import com.jagrosh.jmusicbot.economy.games.WheelGame.Result;
import com.jagrosh.jmusicbot.economy.games.WheelGame.Segment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * "Wheel of Fortune": one spin lands on a multiplier segment (0x up to a 25x
 * jackpot). The pointer ticks around the wheel before it settles.
 */
public class WheelCmd extends WagerGameCommand
{
    public WheelCmd(Bot bot)
    {
        super(bot, "wheel", "spin the wheel of fortune", "<amount>");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 1 || tokens[0].isEmpty())
        {
            ctx.replyError("Usage: `wheel <amount>` — spin for up to a 25x jackpot.");
            return;
        }

        long amount = takeWager(ctx, economy, tokens[0], WheelGame.TOP_MULTIPLIER);
        if(amount < 0)
            return;

        final long authorId = ctx.getAuthor().getIdLong();
        final MessageChannel channel = ctx.getChannel();
        final Result result = WheelGame.play(amount, ThreadLocalRandom.current());

        List<MessageEmbed> frames = new ArrayList<>();
        Segment[] segments = Segment.values();
        for(int i = 0; i < 5; i++)
        {
            Segment s = segments[ThreadLocalRandom.current().nextInt(segments.length)];
            frames.add(spinEmbed("🎡 Wheel of Fortune", "The wheel spins… ➡️ " + label(s)));
        }

        // Settle synchronously, before the cosmetic animation, so the debit and payout are one crash-safe
        // unit — a crash during the ~3s animation can no longer strand the already-decided wager.
        final GameOutcome outcome = economy.settleGame(authorId, amount, result.getPayout(), channel);
        final String detail = "The pointer stops on **" + label(result.getSegment()) + "**"
                + (result.getMultiplier() >= 8.0 ? " — huge!" : "");
        final MessageEmbed reveal = resultEmbed("🎡 Wheel of Fortune", detail, outcome, amount);
        sendAnimated(ctx, embedMessage(frames.get(0)), frames, 500, msg -> msg.editMessageEmbeds(reveal).queue());
    }

    private static String label(Segment s)
    {
        if(s == Segment.LOSE)
            return "0x";
        return (s.getMultiplier() == Math.floor(s.getMultiplier())
                ? String.valueOf((long) s.getMultiplier())
                : String.valueOf(s.getMultiplier())) + "x";
    }
}
