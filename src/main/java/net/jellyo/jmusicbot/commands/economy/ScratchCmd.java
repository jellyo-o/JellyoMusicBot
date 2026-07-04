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
import com.jagrosh.jmusicbot.economy.games.ScratchGame;
import com.jagrosh.jmusicbot.economy.games.ScratchGame.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * A nine-cell scratch card. Match three or more 💎 to win, up to a 50x jackpot for
 * six. The card is "scratched" open before it settles.
 */
public class ScratchCmd extends WagerGameCommand
{
    public ScratchCmd(Bot bot)
    {
        super(bot, "scratch", "scratch a card for prizes", "<amount>");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 1 || tokens[0].isEmpty())
        {
            ctx.replyError("Usage: `scratch <amount>` — match three or more 💎 to win.");
            return;
        }

        EscrowedWager w = takeWager(ctx, economy, tokens[0], ScratchGame.PRACTICAL_TOP_MULTIPLIER);
        if(w == null)
            return;
        long amount = w.amount();

        final long authorId = ctx.getAuthor().getIdLong();
        final MessageChannel channel = ctx.getChannel();
        final Result result = ScratchGame.play(amount, ThreadLocalRandom.current());

        List<MessageEmbed> frames = new ArrayList<>();
        String[] covered = new String[ScratchGame.CELLS];
        for(int reveal = 1; reveal <= 3; reveal++)
        {
            for(int i = 0; i < ScratchGame.CELLS; i++)
                covered[i] = ThreadLocalRandom.current().nextInt(4) < reveal ? result.getGrid()[i] : "⬜";
            frames.add(spinEmbed("🎟️ Scratch Card", "Scratching…\n" + grid(covered)));
        }

        // Settle synchronously, before the cosmetic animation, so the debit and payout are one crash-safe unit.
        final GameOutcome outcome = economy.settleGame(authorId, amount, result.getPayout(), channel, w.id());
        final String detail = grid(result.getGrid()) + "\n💎 count: **" + result.getLuckyCount() + "**"
                + (result.getLuckyCount() >= 6 ? " — 💎 JACKPOT!" : "");
        final MessageEmbed reveal = resultEmbed("🎟️ Scratch Card", detail, outcome, amount);
        sendAnimated(ctx, embedMessage(frames.get(0)), frames, 550, msg -> msg.editMessageEmbeds(reveal).queue());
    }

    private static String grid(String[] cells)
    {
        StringBuilder sb = new StringBuilder();
        for(int row = 0; row < 3; row++)
        {
            sb.append(cells[row * 3]).append(' ').append(cells[row * 3 + 1]).append(' ').append(cells[row * 3 + 2]);
            if(row < 2)
                sb.append('\n');
        }
        return sb.toString();
    }
}
