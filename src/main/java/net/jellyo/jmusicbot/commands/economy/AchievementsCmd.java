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
import com.jagrosh.jmusicbot.economy.UserProfile;
import java.util.Set;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

/**
 * Lists every achievement grouped by category, showing which a user has earned
 * and the requirements for the ones still locked.
 */
public class AchievementsCmd extends EconomyCommand
{
    public AchievementsCmd(Bot bot)
    {
        super(bot, "achievements", "shows your earned and locked achievements", "[@user]");
        this.aliases = bot.getConfig().getAliases("achievements").length == 0
                ? new String[]{"achs", "ach"}
                : bot.getConfig().getAliases("achievements");
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        long targetId = resolveTargetId(ctx);
        UserProfile profile = economy.getProfile(targetId);
        boolean self = targetId == ctx.getAuthor().getIdLong();
        if(!self && profile.getCreatedAt() <= 0)
        {
            ctx.replyWarning(resolveName(ctx, targetId, profile) + " hasn't earned any achievements yet.");
            return;
        }

        Set<String> earned = economy.getStore().earnedAchievementIds(targetId);
        String name = resolveName(ctx, targetId, profile);
        String avatar = resolveAvatar(ctx, targetId, profile);

        EmbedBuilder eb = new EmbedBuilder();
        eb.setColor(ECONOMY_COLOR);
        eb.setAuthor(name + " — Achievements (" + earned.size() + "/" + Achievement.values().length + ")", null, avatar);

        for(Achievement.Category category : Achievement.Category.values())
        {
            StringBuilder lines = new StringBuilder();
            for(Achievement a : Achievement.values())
            {
                if(a.getCategory() != category)
                    continue;
                if(earned.contains(a.getId()))
                    lines.append("✅ ").append(a.getEmoji()).append(" **").append(a.getDisplayName()).append("**\n");
                else
                    lines.append("🔒 ").append(a.getEmoji()).append(' ').append(a.getDisplayName())
                            .append(" — *").append(a.getDescription()).append("*\n");
            }
            if(lines.length() > 0)
                eb.addField(category.getLabel(), lines.toString().trim(), false);
        }
        eb.setFooter("Earn achievements by using the bot — each grants bonus coins and XP", null);
        ctx.reply(new MessageCreateBuilder().setEmbeds(eb.build()).build());
    }
}
