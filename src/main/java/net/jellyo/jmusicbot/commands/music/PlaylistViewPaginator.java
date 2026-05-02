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

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.ButtonPaginator;
import com.jagrosh.jmusicbot.playlist.PlaylistTrack;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.PlaylistSummary;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

public final class PlaylistViewPaginator
{
    private static final int ITEMS_PER_PAGE = 10;
    private static final String PAGE_NAMESPACE = "playlist";

    private PlaylistViewPaginator()
    {
    }

    public static MessageCreateData buildCreateMessage(Bot bot, Guild guild, long userId,
                                                       PlaylistSummary playlist, List<PlaylistTrack> items,
                                                       int requestedPage)
    {
        int pages = ButtonPaginator.pageCount(items.size(), ITEMS_PER_PAGE);
        int page = ButtonPaginator.clampPage(requestedPage, items.size(), ITEMS_PER_PAGE);
        int start = ButtonPaginator.offset(page, ITEMS_PER_PAGE);
        int end = Math.min(start + ITEMS_PER_PAGE, items.size());

        StringBuilder description = new StringBuilder();
        for(int i = start; i < end; i++)
            description.append(formatPlaylistItem(i + 1, items.get(i))).append('\n');

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(guild.getSelfMember().getColor())
                .setTitle(playlist.getName() + " | " + items.size() + " songs")
                .setDescription(description.toString())
                .setFooter("Page " + page + "/" + pages + (playlist.isFollowed() ? " | followed read-only" : ""));

        MessageCreateBuilder builder = new MessageCreateBuilder().setEmbeds(eb.build());
        List<ActionRow> components = ButtonPaginator.controls(PAGE_NAMESPACE, String.valueOf(playlist.getId()),
                guild.getIdLong(), userId, page, pages);
        if(!components.isEmpty())
            builder.setComponents(components);
        return builder.build();
    }

    public static boolean handleButtonInteraction(Bot bot, ButtonInteractionEvent event)
    {
        ButtonPaginator.Request request = ButtonPaginator.parse(event.getComponentId());
        if(!ButtonPaginator.isNamespace(request, PAGE_NAMESPACE))
            return false;
        if(!ButtonPaginator.isAuthorized(bot, event, request, "playlist"))
            return true;

        long playlistId;
        try
        {
            playlistId = Long.parseLong(request.getState());
        }
        catch(NumberFormatException ex)
        {
            event.reply(bot.getConfig().getError() + " This playlist control is invalid.")
                    .setEphemeral(true).queue();
            return true;
        }

        event.editMessage(buildEditMessage(bot, event.getGuild(), request.getUserId(), playlistId, request.getPage())).queue();
        return true;
    }

    private static MessageEditData buildEditMessage(Bot bot, Guild guild, long userId, long playlistId, int requestedPage)
    {
        Optional<PlaylistSummary> playlist = bot.getUserPlaylistService().resolveVisible(userId, playlistId);
        if(playlist.isEmpty())
            return emptyMessage(bot.getConfig().getError() + " That playlist is no longer available.");

        List<PlaylistTrack> items = bot.getUserPlaylistService().listItems(playlistId);
        if(items.isEmpty())
            return emptyMessage(bot.getConfig().getWarning() + " Playlist `" + playlist.get().getName() + "` is empty.");

        return MessageEditData.fromCreateData(buildCreateMessage(bot, guild, userId, playlist.get(), items, requestedPage));
    }

    private static MessageEditData emptyMessage(String content)
    {
        return new MessageEditBuilder()
                .setContent(content)
                .setEmbeds(Collections.emptyList())
                .setComponents(Collections.emptyList())
                .build();
    }

    private static String formatPlaylistItem(int index, PlaylistTrack item)
    {
        String duration = item.getDuration() > 0 ? "`[" + TimeUtil.formatTime(item.getDuration()) + "]` " : "";
        String title = FormatUtil.filter(item.getDisplayTitle());
        if(item.getUrl() != null && item.getUrl().startsWith("http"))
            title = "[**" + title + "**](" + item.getUrl() + ")";
        else
            title = "**" + title + "**";
        String author = item.getAuthor() == null || item.getAuthor().isBlank()
                ? "" : " - " + FormatUtil.filter(item.getAuthor());
        return "`" + index + ".` " + duration + title + author;
    }
}
