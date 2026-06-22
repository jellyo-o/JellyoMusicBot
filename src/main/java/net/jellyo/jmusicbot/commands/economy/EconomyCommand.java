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

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.economy.UserProfile;
import java.awt.Color;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.entities.User;

/**
 * Base for the global economy commands. They are usable both as prefix and slash
 * commands and, because the economy is global, are not restricted to a guild.
 */
public abstract class EconomyCommand extends Command implements UnifiedCommand
{
    protected static final Color ECONOMY_COLOR = new Color(0xF1C40F);
    private static final Pattern MENTION = Pattern.compile("<@!?(\\d{17,19})>");
    private static final Pattern RAW_ID = Pattern.compile("\\d{17,19}");

    protected final Bot bot;

    protected EconomyCommand(Bot bot, String name, String help, String arguments)
    {
        this.bot = bot;
        this.name = name;
        this.help = help;
        this.arguments = arguments;
        this.guildOnly = false;
        this.aliases = bot.getConfig().getAliases(name);
    }

    @Override
    protected void execute(CommandEvent event)
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext ctx)
    {
        EconomyService economy = bot.getEconomyService();
        if(economy == null || !economy.isEnabled())
        {
            ctx.replyError("The economy and games system is disabled on this bot.");
            return;
        }
        economy.ensureUser(ctx.getAuthor());
        run(ctx, economy);
    }

    protected abstract void run(CommandContext ctx, EconomyService economy);

    /** Resolves a target user from a mention or raw id in the args, defaulting to the caller. */
    protected long resolveTargetId(CommandContext ctx)
    {
        String args = ctx.getArgs() == null ? "" : ctx.getArgs().trim();
        if(args.isEmpty())
            return ctx.getAuthor().getIdLong();
        Matcher mention = MENTION.matcher(args);
        if(mention.find())
            return parseIdOrSelf(ctx, mention.group(1));
        String first = args.split("\\s+")[0];
        if(RAW_ID.matcher(first).matches())
            return parseIdOrSelf(ctx, first);
        return ctx.getAuthor().getIdLong();
    }

    /** Parses a snowflake, falling back to the caller's id if it overflows a long. */
    private static long parseIdOrSelf(CommandContext ctx, String id)
    {
        try
        {
            return Long.parseLong(id);
        }
        catch(NumberFormatException ex)
        {
            return ctx.getAuthor().getIdLong();
        }
    }

    protected String resolveName(CommandContext ctx, long userId, UserProfile profile)
    {
        if(userId == ctx.getAuthor().getIdLong())
            return ctx.getAuthor().getEffectiveName();
        if(profile != null && profile.getUsername() != null && !profile.getUsername().isBlank())
            return profile.getUsername();
        User user = ctx.getJDA().getUserById(userId);
        return user != null ? user.getName() : "User " + userId;
    }

    protected String resolveAvatar(CommandContext ctx, long userId, UserProfile profile)
    {
        if(userId == ctx.getAuthor().getIdLong())
            return ctx.getAuthor().getEffectiveAvatarUrl();
        if(profile != null && profile.getAvatar() != null && !profile.getAvatar().isBlank())
            return profile.getAvatar();
        User user = ctx.getJDA().getUserById(userId);
        return user != null ? user.getEffectiveAvatarUrl() : null;
    }

    /** Formats a minute count as e.g. "3d 4h 5m" / "4h 5m" / "5m". */
    protected static String formatMinutes(long minutes)
    {
        if(minutes <= 0)
            return "0m";
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long mins = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if(days > 0)
            sb.append(days).append("d ");
        if(hours > 0 || days > 0)
            sb.append(hours).append("h ");
        sb.append(mins).append("m");
        return sb.toString().trim();
    }

    /** Formats a duration in seconds as e.g. "5h 12m" / "12m 30s" / "45s". */
    protected static String formatDuration(long seconds)
    {
        if(seconds <= 0)
            return "0s";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if(hours > 0)
            sb.append(hours).append("h ");
        if(minutes > 0 || hours > 0)
            sb.append(minutes).append("m ");
        if(hours == 0)
            sb.append(secs).append("s");
        return sb.toString().trim();
    }
}
