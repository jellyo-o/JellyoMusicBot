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
package com.jagrosh.jmusicbot.commands.music;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.PlaybackHistoryStore;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import java.util.Collections;
import java.util.List;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

public class HistoryCmd extends MusicCommand implements UnifiedCommand
{
    private static final int ITEMS_PER_PAGE = 10;
    private static final int TITLE_LIMIT = 120;
    private static final int AUTHOR_LIMIT = 80;
    private static final String BUTTON_PREFIX = "jmb-history:";

    public HistoryCmd(Bot bot)
    {
        super(bot);
        this.name = "history";
        this.help = "shows recently played songs";
        this.arguments = "<session|guild>";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.botPermissions = new Permission[]{Permission.MESSAGE_EMBED_LINKS};
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext event)
    {
        ParsedArgs args = parseArgs(event.getArgs());
        if(args.scope == Scope.NONE)
        {
            event.replyWarning("Please include `session` or `guild`. Example: `" + event.getPrefix() + "history session`");
            return;
        }

        if(args.scope == Scope.SESSION)
            replySessionHistory(event);
        else
            replyGuildHistory(event);
    }

    public static boolean handleButtonInteraction(Bot bot, ButtonInteractionEvent event)
    {
        ButtonRequest request = ButtonRequest.parse(event.getComponentId());
        if(request == null)
            return false;

        if(event.getGuild() == null || event.getGuild().getIdLong() != request.guildId)
        {
            event.reply(bot.getConfig().getError() + " This history control cannot be used here.")
                    .setEphemeral(true).queue();
            return true;
        }

        if(event.getUser().getIdLong() != request.userId)
        {
            event.reply(bot.getConfig().getError() + " Only the user who opened this history can change pages.")
                    .setEphemeral(true).queue();
            return true;
        }

        MessageEditData message = buildEditMessage(bot, event.getGuild(), request.scope, request.userId, request.page);
        event.editMessage(message).queue();
        return true;
    }

    private void replySessionHistory(CommandContext event)
    {
        AudioHandler handler = bot.getPlayerManager().setUpHandler(event.getGuild());
        List<PlaybackHistoryStore.Entry> history = handler.getPlaybackSessionHistory();
        if(history.isEmpty())
        {
            event.replyWarning("No songs have been played in this session yet.");
            return;
        }

        event.reply(buildCreateMessage(event.getGuild(), event.getAuthor().getIdLong(), Scope.SESSION,
                "Session Playback History", firstPage(history), history.size(), 1, 0));
    }

    private void replyGuildHistory(CommandContext event)
    {
        PlaybackHistoryStore store = bot.getPlaybackHistoryStore();
        if(store == null)
        {
            event.replyError("Playback history storage is unavailable.");
            return;
        }

        int totalEntries;
        try
        {
            totalEntries = store.countEntries(event.getGuild().getIdLong());
        }
        catch(RuntimeException ex)
        {
            event.replyError("Playback history could not be loaded.");
            return;
        }

        if(totalEntries == 0)
        {
            event.replyWarning("No songs have been played on this server yet.");
            return;
        }

        List<PlaybackHistoryStore.Entry> history;
        try
        {
            history = store.list(event.getGuild().getIdLong(), 0, ITEMS_PER_PAGE);
        }
        catch(RuntimeException ex)
        {
            event.replyError("Playback history could not be loaded.");
            return;
        }

        event.reply(buildCreateMessage(event.getGuild(), event.getAuthor().getIdLong(), Scope.GUILD,
                "Guild Playback History", history, totalEntries, 1, 0));
    }

    private static MessageEditData buildEditMessage(Bot bot, Guild guild, Scope scope, long userId, int requestedPage)
    {
        if(scope == Scope.SESSION)
        {
            AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
            List<PlaybackHistoryStore.Entry> history = handler.getPlaybackSessionHistory();
            if(history.isEmpty())
                return emptyHistoryMessage(bot.getConfig().getWarning() + " No songs have been played in this session yet.");

            int totalEntries = history.size();
            int pages = pageCount(totalEntries);
            int page = Math.max(1, Math.min(requestedPage, pages));
            int offset = (page - 1) * ITEMS_PER_PAGE;
            int end = Math.min(offset + ITEMS_PER_PAGE, history.size());
            return MessageEditData.fromCreateData(buildCreateMessage(guild, userId, scope, "Session Playback History",
                    history.subList(offset, end), totalEntries, page, offset));
        }

        PlaybackHistoryStore store = bot.getPlaybackHistoryStore();
        if(store == null)
            return emptyHistoryMessage(bot.getConfig().getError() + " Playback history storage is unavailable.");

        try
        {
            int totalEntries = store.countEntries(guild.getIdLong());
            if(totalEntries == 0)
                return emptyHistoryMessage(bot.getConfig().getWarning() + " No songs have been played on this server yet.");

            int pages = pageCount(totalEntries);
            int page = Math.max(1, Math.min(requestedPage, pages));
            int offset = (page - 1) * ITEMS_PER_PAGE;
            List<PlaybackHistoryStore.Entry> history = store.list(guild.getIdLong(), offset, ITEMS_PER_PAGE);
            return MessageEditData.fromCreateData(buildCreateMessage(guild, userId, scope, "Guild Playback History",
                    history, totalEntries, page, offset));
        }
        catch(RuntimeException ex)
        {
            return emptyHistoryMessage(bot.getConfig().getError() + " Playback history could not be loaded.");
        }
    }

    private static MessageEditData emptyHistoryMessage(String content)
    {
        return new MessageEditBuilder()
                .setContent(content)
                .setEmbeds(Collections.emptyList())
                .setComponents(Collections.emptyList())
                .build();
    }

    private static MessageCreateData buildCreateMessage(Guild guild, long userId, Scope scope, String title,
                                                        List<PlaybackHistoryStore.Entry> history, int totalEntries,
                                                        int page, int offset)
    {
        int pages = pageCount(totalEntries);

        StringBuilder description = new StringBuilder();
        for(int i = 0; i < history.size(); i++)
            description.append(formatEntry(offset + i + 1, history.get(i))).append('\n');

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(guild.getSelfMember().getColor())
                .setTitle(title + " | " + totalEntries + " entr" + (totalEntries == 1 ? "y" : "ies"))
                .setDescription(description.toString())
                .setFooter("Page " + page + "/" + pages);

        MessageCreateBuilder builder = new MessageCreateBuilder().setEmbeds(eb.build());
        if(pages > 1)
            builder.setComponents(ActionRow.of(
                    Button.secondary(buttonId(scope, guild.getIdLong(), userId, Math.max(1, page - 1)), "Previous")
                            .withDisabled(page <= 1),
                    Button.secondary(buttonId(scope, guild.getIdLong(), userId, Math.min(pages, page + 1)), "Next")
                            .withDisabled(page >= pages)));
        return builder.build();
    }

    private static ParsedArgs parseArgs(String rawArgs)
    {
        String args = rawArgs == null ? "" : rawArgs.trim();
        if(args.isEmpty())
            return new ParsedArgs(Scope.NONE);

        String[] parts = args.split("\\s+", 2);
        Scope scope = Scope.from(parts[0]);
        if(scope == Scope.NONE)
            return new ParsedArgs(Scope.NONE);

        return new ParsedArgs(scope);
    }

    private static List<PlaybackHistoryStore.Entry> firstPage(List<PlaybackHistoryStore.Entry> history)
    {
        return history.subList(0, Math.min(ITEMS_PER_PAGE, history.size()));
    }

    private static int pageCount(int totalEntries)
    {
        return Math.max(1, (int)Math.ceil((double)totalEntries / ITEMS_PER_PAGE));
    }

    private static String buttonId(Scope scope, long guildId, long userId, int page)
    {
        return BUTTON_PREFIX + scope.id + ":" + guildId + ":" + userId + ":" + page;
    }

    private static String formatEntry(int index, PlaybackHistoryStore.Entry entry)
    {
        String title = display(entry.getTitle(), "Unknown track", TITLE_LIMIT);
        String author = display(entry.getAuthor(), "", AUTHOR_LIMIT);
        StringBuilder line = new StringBuilder("`")
                .append(index)
                .append(".` `")
                .append(formatDuration(entry))
                .append("` ");

        if(entry.getUri() != null && entry.getUri().startsWith("http"))
            line.append("[**").append(title).append("**](").append(entry.getUri()).append(")");
        else
            line.append("**").append(title).append("**");

        if(entry.getCount() > 1)
            line.append(" (x").append(entry.getCount()).append(")");
        if(!author.isEmpty())
            line.append(" - ").append(author);

        return line.toString();
    }

    private static String formatDuration(PlaybackHistoryStore.Entry entry)
    {
        if(entry.isStream() || entry.getDuration() == Long.MAX_VALUE)
            return "LIVE";
        return TimeUtil.formatTime(Math.max(0L, entry.getDuration()));
    }

    private static String display(String value, String fallback, int maxLength)
    {
        String text = value == null || value.isBlank() ? fallback : value.trim();
        text = FormatUtil.filter(text);
        if(text.length() <= maxLength)
            return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    private enum Scope
    {
        NONE,
        SESSION,
        GUILD;

        private final String id;

        Scope()
        {
            id = name().substring(0, 1).toLowerCase();
        }

        private static Scope from(String value)
        {
            if("session".equalsIgnoreCase(value))
                return SESSION;
            if("guild".equalsIgnoreCase(value) || "server".equalsIgnoreCase(value))
                return GUILD;
            return NONE;
        }

        private static Scope fromId(String value)
        {
            for(Scope scope : values())
                if(scope.id.equals(value))
                    return scope;
            return NONE;
        }
    }

    private static final class ParsedArgs
    {
        private final Scope scope;

        private ParsedArgs(Scope scope)
        {
            this.scope = scope;
        }
    }

    private static final class ButtonRequest
    {
        private final Scope scope;
        private final long guildId;
        private final long userId;
        private final int page;

        private ButtonRequest(Scope scope, long guildId, long userId, int page)
        {
            this.scope = scope;
            this.guildId = guildId;
            this.userId = userId;
            this.page = page;
        }

        private static ButtonRequest parse(String componentId)
        {
            if(componentId == null || !componentId.startsWith(BUTTON_PREFIX))
                return null;

            String[] parts = componentId.substring(BUTTON_PREFIX.length()).split(":");
            if(parts.length != 4)
                return null;

            try
            {
                Scope scope = Scope.fromId(parts[0]);
                if(scope == Scope.NONE)
                    return null;
                return new ButtonRequest(scope, Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                        Math.max(1, Integer.parseInt(parts[3])));
            }
            catch(NumberFormatException ex)
            {
                return null;
            }
        }
    }
}
