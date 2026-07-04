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
package com.jagrosh.jmusicbot.commands.economy.games;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.games.TriviaBank.Question;
import java.util.ArrayList;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

/**
 * A single trivia question with four answer buttons. A correct answer pays coins by
 * difficulty plus XP; the cooldown is already consumed when the question is posed, so
 * timing out or answering wrong simply earns nothing.
 */
public class TriviaSession extends GameSession
{
    private static final char[] LETTERS = {'A', 'B', 'C', 'D'};

    private final Question question;

    public TriviaSession(Bot bot, long ownerId, String ownerName, long guildId, long channelId, Question question)
    {
        super(bot, ownerId, ownerName, guildId, channelId, 0, null); // no wager, no escrow
        this.question = question;
    }

    @Override
    public void onButton(String action, ButtonInteractionEvent event)
    {
        if(!action.startsWith("trivia:"))
        {
            ackIfNeeded(event);
            return;
        }
        int choice;
        try
        {
            choice = Integer.parseInt(action.substring("trivia:".length()));
        }
        catch(NumberFormatException ex)
        {
            ackIfNeeded(event);
            return;
        }
        if(!claimResolution())
        {
            ackIfNeeded(event);
            return;
        }
        boolean correct = question.isCorrect(choice);
        long reward = 0;
        long balance = bot.getEconomyService().getBalance(ownerId);
        if(correct)
        {
            reward = question.getDifficulty().getReward();
            balance = bot.getEconomyService().rewardTrivia(ownerId, reward, channel());
        }
        closeClaimed();
        editResult(event, resultEmbed(choice, reward, balance));
    }

    @Override
    protected void onTimeout()
    {
        if(!claimResolution())
            return;
        closeClaimed();
        editResultById(resultEmbed(-1, 0, bot.getEconomyService().getBalance(ownerId)));
    }

    private MessageEmbed resultEmbed(int choice, long reward, long balance)
    {
        boolean correct = choice == question.getCorrect();
        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(correct ? GameEmbeds.WIN : GameEmbeds.LOSS);
        eb.setTitle("🧠 Trivia");
        StringBuilder sb = new StringBuilder("**").append(question.getText()).append("**\n");
        String[] options = question.getOptions();
        for(int i = 0; i < options.length; i++)
        {
            String mark = i == question.getCorrect() ? "✅ " : (i == choice ? "❌ " : "");
            sb.append(mark).append(LETTERS[i]).append(") ").append(options[i]).append('\n');
        }
        if(choice < 0)
            sb.append("\n⏱️ Time's up — no answer.");
        else if(correct)
            sb.append("\n🎉 Correct! **+").append(EconomyService.coins(reward)).append("** • +")
                    .append(EconomyService.TRIVIA_XP).append(" XP");
        else
            sb.append("\n💀 Not quite.");
        eb.setDescription(sb.toString());
        eb.setFooter("Balance: " + EconomyService.coins(balance), null);
        return eb.build();
    }

    public MessageEmbed panel()
    {
        StringBuilder sb = new StringBuilder("**").append(question.getText()).append("**\n");
        String[] options = question.getOptions();
        for(int i = 0; i < options.length; i++)
            sb.append(LETTERS[i]).append(") ").append(options[i]).append('\n');
        sb.append("\nDifficulty: **").append(question.getDifficulty().name().toLowerCase())
                .append("** (").append(EconomyService.coins(question.getDifficulty().getReward())).append(')');
        return GameEmbeds.live("🧠 Trivia", sb.toString());
    }

    public List<ActionRow> buttons()
    {
        List<Button> row = new ArrayList<>();
        for(int i = 0; i < 4; i++)
            row.add(Button.primary(GameButtons.id("trivia:" + i), String.valueOf(LETTERS[i])));
        return List.of(ActionRow.of(row));
    }
}
