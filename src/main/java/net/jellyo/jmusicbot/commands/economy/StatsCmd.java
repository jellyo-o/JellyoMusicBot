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
import com.jagrosh.jmusicbot.achievements.Achievement;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.EconomyStore;
import com.jagrosh.jmusicbot.economy.EconomyStore.LeaderMetric;
import com.jagrosh.jmusicbot.economy.UserProfile;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/**
 * Shows a user's global profile: level/XP, balance, listening and request
 * stats, guess-game record, gambling record and achievement progress.
 */
public class StatsCmd extends EconomyCommand
{
    public StatsCmd(Bot bot)
    {
        super(bot, "stats", "shows your (or another user's) global XP, coins and achievements", "[@user]");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        long targetId = resolveTargetId(ctx);
        UserProfile p = economy.getProfile(targetId);
        boolean self = targetId == ctx.getAuthor().getIdLong();
        if(!self && p.getCreatedAt() <= 0)
        {
            ctx.replyWarning(resolveName(ctx, targetId, p) + " hasn't used the bot yet, so there are no stats to show.");
            return;
        }

        EconomyStore store = economy.getStore();
        String name = resolveName(ctx, targetId, p);
        String avatar = resolveAvatar(ctx, targetId, p);
        int level = p.getLevel();
        long into = p.getXpIntoLevel();
        long need = p.getXpForNextLevel();
        double pct = need > 0 ? (double) into / need : 0;

        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(ECONOMY_COLOR);
        eb.setAuthor(name + " — Profile", null, avatar);
        if(avatar != null)
            eb.setThumbnail(avatar);
        eb.addField("Level " + level,
                FormatUtil.progressBar(pct) + "\n" + String.format("%,d / %,d XP to next  •  %,d XP total", into, need, p.getXp()),
                false);
        eb.addField("💰 Balance", EconomyService.coins(p.getCurrency()) + "\nRank #" + store.rankBy(LeaderMetric.CURRENCY, targetId), true);
        eb.addField("✨ XP Rank", "#" + store.rankBy(LeaderMetric.XP, targetId), true);
        eb.addField("🏆 Achievements", store.earnedAchievementIds(targetId).size() + " / " + Achievement.values().length, true);
        eb.addField("🎵 Songs requested", String.format("%,d", p.getSongsRequested()), true);
        eb.addField("🎧 Time listened", formatMinutes(p.getMinutesListened()), true);
        eb.addField("📅 Daily streak", p.getDailyStreak() + (p.getDailyStreak() == 1 ? " day" : " days"), true);
        eb.addField("🎮 Guess the Song",
                "Correct: **" + String.format("%,d", p.getGuessesCorrect()) + "**  •  Wins: **" + p.getGuessWins()
                        + "**  •  Games: **" + p.getGamesPlayed() + "**",
                false);
        if(p.getGambleWins() + p.getGambleLosses() > 0)
        {
            String net = (p.getGambleNet() >= 0 ? "+" : "") + String.format("%,d", p.getGambleNet());
            eb.addField("🎲 Gambling",
                    "Wins: **" + p.getGambleWins() + "**  •  Losses: **" + p.getGambleLosses() + "**  •  Net: **"
                            + net + "** " + EconomyService.CURRENCY_EMOJI,
                    false);
        }
        eb.setFooter("Global stats, shared across every server • Listening earns XP only while music plays and after you've queued a song this session", null);
        ctx.reply(new MessageCreateBuilder().setEmbeds(eb.build()).build());
    }
}
