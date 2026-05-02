/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
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

import java.util.List;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.commands.ButtonPaginator;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;


/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class QueueCmd extends MusicCommand implements UnifiedCommand
{
    private static final int ITEMS_PER_PAGE = 10;
    private static final String PAGE_NAMESPACE = "queue";
    private static final String PAGE_STATE = "q";
    
    public QueueCmd(Bot bot)
    {
        super(bot);
        this.name = "queue";
        this.help = "shows the current queue";
        this.arguments = "";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.bePlaying = true;
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
        event.reply(buildQueueMessage(bot, event.getGuild(), event.getJDA(), event.getAuthor().getIdLong(),
                parseInitialPage(event.getArgs())));
    }

    public static boolean handleButtonInteraction(Bot bot, ButtonInteractionEvent event)
    {
        ButtonPaginator.Request request = ButtonPaginator.parse(event.getComponentId());
        if(!ButtonPaginator.isNamespace(request, PAGE_NAMESPACE))
            return false;
        if(!ButtonPaginator.isAuthorized(bot, event, request, "queue"))
            return true;

        event.editMessage(buildQueueEditMessage(bot, event.getGuild(), event.getJDA(), request.getUserId(), request.getPage())).queue();
        return true;
    }

    private static MessageEditData buildQueueEditMessage(Bot bot, Guild guild, JDA jda, long userId, int requestedPage)
    {
        return MessageEditData.fromCreateData(buildQueueMessage(bot, guild, jda, userId, requestedPage));
    }

    private static MessageCreateData buildQueueMessage(Bot bot, Guild guild, JDA jda, long userId, int requestedPage)
    {
        AudioHandler ah = bot.getPlayerManager().setUpHandler(guild);
        List<QueuedTrack> list = ah.getQueue().getList();
        if(list.isEmpty())
            return buildEmptyQueueMessage(bot, ah, jda);

        int pages = ButtonPaginator.pageCount(list.size(), ITEMS_PER_PAGE);
        int page = ButtonPaginator.clampPage(requestedPage, list.size(), ITEMS_PER_PAGE);
        int start = ButtonPaginator.offset(page, ITEMS_PER_PAGE);
        int end = Math.min(start + ITEMS_PER_PAGE, list.size());

        StringBuilder description = new StringBuilder();
        AudioTrack current = ah.getPlayer().getPlayingTrack();
        if(current != null)
            description.append(ah.getStatusEmoji()).append(" **")
                    .append(trackTitle(current)).append("**\n\n");
        for(int i = start; i < end; i++)
            description.append("`").append(i + 1).append(".` ")
                    .append(FormatUtil.filter(list.get(i).toString())).append('\n');

        Settings settings = bot.getSettingsManager().getSettings(guild);
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(guild.getSelfMember().getColor())
                .setTitle(queueTitle(bot.getConfig().getSuccess(), list.size(), totalDurationText(list)))
                .setDescription(description.toString())
                .setFooter("Page " + page + "/" + pages + " | " + queueState(settings));

        MessageCreateBuilder builder = new MessageCreateBuilder().setEmbeds(eb.build());
        List<ActionRow> components = ButtonPaginator.controls(PAGE_NAMESPACE, PAGE_STATE, guild.getIdLong(), userId, page, pages);
        if(!components.isEmpty())
            builder.setComponents(components);
        return builder.build();
    }

    private static MessageCreateData buildEmptyQueueMessage(Bot bot, AudioHandler ah, JDA jda)
    {
        MessageCreateBuilder builder = new MessageCreateBuilder()
                .setContent(bot.getConfig().getWarning() + " There is no music in the queue!");
        MessageCreateData nowp = ah.getNowPlaying(jda);
        MessageCreateData nonowp = ah.getNoMusicPlaying(jda);
        MessageCreateData embedSource = nowp == null ? nonowp : nowp;
        if(embedSource != null && !embedSource.getEmbeds().isEmpty())
            builder.setEmbeds(embedSource.getEmbeds());
        return builder.build();
    }

    private static int parseInitialPage(String args)
    {
        if(args == null || args.isBlank())
            return 1;
        try
        {
            return Integer.parseInt(args.trim().split("\\s+", 2)[0]);
        }
        catch(NumberFormatException ex)
        {
            return 1;
        }
    }

    private static String queueTitle(String success, int entries, String total)
    {
        return success + " Current Queue | " + entries + " entr" + (entries == 1 ? "y" : "ies")
                + " | `" + total + "`";
    }

    private static String queueState(Settings settings)
    {
        RepeatMode repeatMode = settings.getRepeatMode();
        QueueType queueType = settings.getQueueType();
        return queueType.getEmoji() + " " + queueType.getUserFriendlyName()
                + (repeatMode != RepeatMode.OFF ? " | " + repeatMode.getUserFriendlyName() : "");
    }

    private static String totalDurationText(List<QueuedTrack> list)
    {
        long total = 0;
        for(QueuedTrack queuedTrack : list)
        {
            AudioTrack track = queuedTrack.getTrack();
            if(track == null)
                continue;
            if(track.getDuration() == Long.MAX_VALUE || (track.getInfo() != null && track.getInfo().isStream))
                return "LIVE";
            long duration = Math.max(0L, track.getDuration());
            if(Long.MAX_VALUE - total < duration)
                return "LIVE";
            total += duration;
        }
        return TimeUtil.formatTime(total);
    }

    private static String trackTitle(AudioTrack track)
    {
        if(track.getInfo() == null || track.getInfo().title == null || track.getInfo().title.isBlank())
            return "Unknown track";
        return FormatUtil.filter(track.getInfo().title);
    }
}
