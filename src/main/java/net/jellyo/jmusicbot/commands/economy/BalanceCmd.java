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
import com.jagrosh.jmusicbot.economy.EconomyStore.LeaderMetric;
import com.jagrosh.jmusicbot.economy.UserProfile;

/**
 * Quick check of a user's coin balance and global wealth rank.
 */
public class BalanceCmd extends EconomyCommand
{
    public BalanceCmd(Bot bot)
    {
        super(bot, "balance", "shows your coin balance", "[@user]");
        this.aliases = mergeAliases(bot, "bal", "coins", "wallet");
    }

    private static String[] mergeAliases(Bot bot, String... extra)
    {
        String[] configured = bot.getConfig().getAliases("balance");
        if(configured.length == 0)
            return extra;
        String[] all = new String[configured.length + extra.length];
        System.arraycopy(configured, 0, all, 0, configured.length);
        System.arraycopy(extra, 0, all, configured.length, extra.length);
        return all;
    }

    @Override
    protected void run(CommandContext ctx, EconomyService economy)
    {
        long targetId = resolveTargetId(ctx);
        UserProfile p = economy.getProfile(targetId);
        boolean self = targetId == ctx.getAuthor().getIdLong();
        if(!self && p.getCreatedAt() <= 0)
        {
            ctx.replyWarning(resolveName(ctx, targetId, p) + " hasn't earned any coins yet.");
            return;
        }
        String name = resolveName(ctx, targetId, p);
        int rank = economy.getStore().rankBy(LeaderMetric.CURRENCY, targetId);
        ctx.reply("💰 **" + name + "** has " + EconomyService.coins(p.getCurrency())
                + "  •  Rank **#" + rank + "** globally");
    }
}
