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
import com.jagrosh.jmusicbot.economy.EconomyStore;
import com.jagrosh.jmusicbot.economy.EconomyStore.LeaderEntry;
import com.jagrosh.jmusicbot.economy.EconomyStore.LeaderMetric;
import com.jagrosh.jmusicbot.economy.LevelCurve;
import com.jagrosh.jmusicbot.economy.UserProfile;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/**
 * Global leaderboards across coins, XP, listening time, songs requested and
 * guess-game wins. Rankings are cross-server because the economy is global.
 */
public class LeaderboardCmd extends EconomyCommand
{
    public LeaderboardCmd(Bot bot)
    {
        super(bot, "leaderboard", "shows the global leaderboard", "[coins|xp|time|songs|wins]");
        this.aliases = bot.getConfig().getAliases("leaderboard").length == 0
                ? new String[]{"lb", "top"}
                : bot.getConfig().getAliases("leaderboard");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        LeaderMetric metric = parseMetric(ctx.getArgs());
        EconomyStore store = economy.getStore();
        List<LeaderEntry> top = store.topBy(metric, 10);

        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(ECONOMY_COLOR);
        eb.setTitle("🏆 " + metricLabel(metric) + " Leaderboard");
        if(top.isEmpty())
        {
            eb.setDescription("No data yet — be the first to climb the ranks!");
            ctx.reply(new MessageCreateBuilder().setEmbeds(eb.build()).build());
            return;
        }

        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < top.size(); i++)
        {
            LeaderEntry entry = top.get(i);
            String name = entry.getUsername() != null && !entry.getUsername().isBlank()
                    ? entry.getUsername() : "User " + entry.getUserId();
            sb.append(medal(i)).append(" **").append(name).append("** — ")
                    .append(formatValue(metric, entry.getValue())).append('\n');
        }
        eb.setDescription(sb.toString());

        long authorId = ctx.getAuthor().getIdLong();
        UserProfile me = economy.getProfile(authorId);
        eb.setFooter("Your rank: #" + store.rankBy(metric, authorId) + "  •  "
                + formatValue(metric, metricValue(metric, me)), null);
        ctx.reply(new MessageCreateBuilder().setEmbeds(eb.build()).build());
    }

    private static String medal(int index)
    {
        switch(index)
        {
            case 0: return "🥇";
            case 1: return "🥈";
            case 2: return "🥉";
            default: return "`#" + (index + 1) + "`";
        }
    }

    private static LeaderMetric parseMetric(String args)
    {
        if(args == null)
            return LeaderMetric.CURRENCY;
        switch(args.trim().toLowerCase(Locale.ROOT))
        {
            case "xp": case "level": case "levels":
                return LeaderMetric.XP;
            case "time": case "minutes": case "listened": case "listening":
                return LeaderMetric.MINUTES_LISTENED;
            case "songs": case "requested": case "requests":
                return LeaderMetric.SONGS_REQUESTED;
            case "wins": case "guess": case "guesses":
                return LeaderMetric.GUESS_WINS;
            case "coins": case "balance": case "money": case "currency":
            default:
                return LeaderMetric.CURRENCY;
        }
    }

    private static String metricLabel(LeaderMetric metric)
    {
        switch(metric)
        {
            case XP: return "XP";
            case MINUTES_LISTENED: return "Listening Time";
            case SONGS_REQUESTED: return "Songs Requested";
            case GUESS_WINS: return "Guess-the-Song Wins";
            case CURRENCY:
            default: return "Coins";
        }
    }

    private static long metricValue(LeaderMetric metric, UserProfile p)
    {
        switch(metric)
        {
            case XP: return p.getXp();
            case MINUTES_LISTENED: return p.getMsListened();
            case SONGS_REQUESTED: return p.getSongsRequested();
            case GUESS_WINS: return p.getGuessWins();
            case CURRENCY:
            default: return p.getCurrency();
        }
    }

    private static String formatValue(LeaderMetric metric, long value)
    {
        switch(metric)
        {
            case XP:
                return String.format("%,d XP (Lvl %d)", value, LevelCurve.levelForXp(value));
            case MINUTES_LISTENED:
                return formatMinutes(value / 60_000L);
            case SONGS_REQUESTED:
                return String.format("%,d songs", value);
            case GUESS_WINS:
                return value + (value == 1 ? " win" : " wins");
            case CURRENCY:
            default:
                return EconomyService.coins(value);
        }
    }
}
