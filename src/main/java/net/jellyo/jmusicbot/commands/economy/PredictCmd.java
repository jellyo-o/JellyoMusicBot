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
import com.jagrosh.jmusicbot.economy.games.DiceGame;
import com.jagrosh.jmusicbot.economy.games.DiceGame.Mode;
import com.jagrosh.jmusicbot.economy.games.DiceGame.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * "Dice Predict": call the roll of a die. An exact number (1-6) pays ~5.7x; the
 * even-money calls (even/odd/high/low) pay ~1.9x. The die visibly tumbles before
 * settling.
 */
public class PredictCmd extends WagerGameCommand
{
    private static final String[] FACES = {"⚀", "⚁", "⚂", "⚃", "⚄", "⚅"};

    public PredictCmd(Bot bot)
    {
        super(bot, "predict", "predict a dice roll for coins", "<amount> <1-6|even|odd|high|low>");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        String[] tokens = ctx.getArgs() == null ? new String[0] : ctx.getArgs().trim().split("\\s+");
        if(tokens.length < 2 || tokens[0].isEmpty())
        {
            ctx.replyError("Usage: `predict <amount> <1-6|even|odd|high|low>` — e.g. `predict 100 4`.");
            return;
        }
        String pred = tokens[1].toLowerCase();
        Mode mode;
        int target = 0;
        if(pred.matches("[1-6]"))
        {
            mode = Mode.EXACT;
            target = Integer.parseInt(pred);
        }
        else if(pred.equals("even") || pred.equals("odd") || pred.equals("high") || pred.equals("low"))
        {
            mode = Mode.from(pred);
        }
        else
        {
            ctx.replyError("Predict a number **1-6**, or one of **even / odd / high / low**.");
            return;
        }

        long amount = takeWager(ctx, economy, tokens[0], mode.multiplier());
        if(amount < 0)
            return;

        final long authorId = ctx.getAuthor().getIdLong();
        final MessageChannel channel = ctx.getChannel();
        final Result result = DiceGame.play(mode, target, amount, ThreadLocalRandom.current());
        final String call = mode == Mode.EXACT ? ("exactly " + target) : mode.name().toLowerCase();

        List<MessageEmbed> frames = new ArrayList<>();
        for(int i = 0; i < 4; i++)
        {
            String face = FACES[ThreadLocalRandom.current().nextInt(6)];
            frames.add(spinEmbed("🎲 Dice Predict", "The die tumbles… " + face + "\nYou called **" + call + "**"));
        }

        sendAnimated(ctx, embedMessage(frames.get(0)), frames, 550, msg ->
        {
            GameOutcome outcome = economy.settleGame(authorId, amount, result.getPayout(), channel);
            String detail = "The die landed on **" + result.getRoll() + "** " + FACES[result.getRoll() - 1]
                    + "\nYou called **" + call + "** — " + (result.isWon() ? "nailed it!" : "no luck.");
            msg.editMessageEmbeds(resultEmbed("🎲 Dice Predict", detail, outcome, amount)).queue();
        });
    }
}
