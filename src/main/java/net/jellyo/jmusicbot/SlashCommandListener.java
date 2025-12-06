/*
 * Copyright 2024 Jellyo.
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
package com.jagrosh.jmusicbot;

import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles all slash command interactions for the music bot.
 */
public class SlashCommandListener extends ListenerAdapter
{
    private final static Logger LOG = LoggerFactory.getLogger(SlashCommandListener.class);
    private final Bot bot;

    public SlashCommandListener(Bot bot)
    {
        this.bot = bot;
    }

    @Override
    public void onReady(ReadyEvent event)
    {
        registerSlashCommands(event.getJDA());
    }

    private void registerSlashCommands(JDA jda)
    {
        List<SlashCommandData> commands = new ArrayList<>();

        // General commands
        commands.add(Commands.slash("settings", "Shows the bot settings"));

        // Music commands (anyone can use)
        commands.add(Commands.slash("play", "Play a song")
                .addOptions(new OptionData(OptionType.STRING, "query", "Song name or URL", true)));
        commands.add(Commands.slash("playtop", "Play a song at the top of the queue")
                .addOptions(new OptionData(OptionType.STRING, "query", "Song name or URL", true)));
        commands.add(Commands.slash("nowplaying", "Shows the currently playing song"));
        commands.add(Commands.slash("queue", "Shows the current queue")
                .addOptions(new OptionData(OptionType.INTEGER, "page", "Page number", false)));
        commands.add(Commands.slash("skip", "Vote to skip the current song"));
        commands.add(Commands.slash("remove", "Remove a song from the queue")
                .addOptions(new OptionData(OptionType.STRING, "position", "Position in queue or 'all'", true)));
        commands.add(Commands.slash("shuffle", "Shuffle your songs in the queue"));
        commands.add(Commands.slash("seek", "Seek to a position in the current song")
                .addOptions(new OptionData(OptionType.STRING, "time", "Time to seek to (e.g., 1:30, +30, -15)", true)));
        commands.add(Commands.slash("lyrics", "Search for lyrics")
                .addOptions(new OptionData(OptionType.STRING, "query", "Song to search lyrics for", false)));
        commands.add(Commands.slash("playlists", "Shows available playlists"));

        // DJ commands
        commands.add(Commands.slash("forceskip", "Force skip the current song"));
        commands.add(Commands.slash("pause", "Pause or resume the current song"));
        commands.add(Commands.slash("stop", "Stop playback and clear the queue"));
        commands.add(Commands.slash("volume", "Set or show the volume")
                .addOptions(new OptionData(OptionType.INTEGER, "level", "Volume level (0-150)", false)));
        commands.add(Commands.slash("repeat", "Toggle repeat mode")
                .addOptions(new OptionData(OptionType.STRING, "mode", "Repeat mode", false)
                        .addChoice("off", "off")
                        .addChoice("all", "all")
                        .addChoice("single", "single")));
        commands.add(Commands.slash("skipto", "Skip to a specific position in the queue")
                .addOptions(new OptionData(OptionType.INTEGER, "position", "Position to skip to", true)));
        commands.add(Commands.slash("movetrack", "Move a track in the queue")
                .addOptions(new OptionData(OptionType.INTEGER, "from", "Current position", true))
                .addOptions(new OptionData(OptionType.INTEGER, "to", "New position", true)));
        commands.add(Commands.slash("playnext", "Play a song next in queue")
                .addOptions(new OptionData(OptionType.STRING, "query", "Song name or URL", true)));
        commands.add(Commands.slash("forceremove", "Force remove a user's songs from queue")
                .addOptions(new OptionData(OptionType.USER, "user", "User to remove songs from", true)));

        // Admin commands
        commands.add(Commands.slash("prefix", "Set the command prefix")
                .addOptions(new OptionData(OptionType.STRING, "prefix", "New prefix or 'none'", true))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(Commands.slash("setdj", "Set the DJ role")
                .addOptions(new OptionData(OptionType.ROLE, "role", "DJ role (leave empty to clear)", false))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(Commands.slash("settc", "Set the text channel for music commands")
                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Text channel (leave empty to clear)", false))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(Commands.slash("setvc", "Set the voice channel for music")
                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Voice channel (leave empty to clear)", false))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(Commands.slash("skipratio", "Set the skip vote ratio")
                .addOptions(new OptionData(OptionType.NUMBER, "ratio", "Skip ratio (0-1)", false))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(Commands.slash("queuetype", "Set the queue type")
                .addOptions(new OptionData(OptionType.STRING, "type", "Queue type", false)
                        .addChoice("fair", "fair")
                        .addChoice("linear", "linear"))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));

        jda.updateCommands().addCommands(commands).queue(
                cmds -> LOG.info("Registered {} slash commands", cmds.size()),
                err -> LOG.error("Failed to register slash commands", err)
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event)
    {
        String commandName = event.getName();
        Guild guild = event.getGuild();

        if (guild == null)
        {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        switch (commandName)
        {
            case "settings": handleSettings(event); break;
            case "play": handlePlay(event, false); break;
            case "playtop": handlePlay(event, true); break;
            case "nowplaying": handleNowPlaying(event); break;
            case "queue": handleQueue(event); break;
            case "skip": handleSkip(event); break;
            case "remove": handleRemove(event); break;
            case "shuffle": handleShuffle(event); break;
            case "seek": handleSeek(event); break;
            case "lyrics": handleLyrics(event); break;
            case "playlists": handlePlaylists(event); break;
            case "forceskip": handleForceSkip(event); break;
            case "pause": handlePause(event); break;
            case "stop": handleStop(event); break;
            case "volume": handleVolume(event); break;
            case "repeat": handleRepeat(event); break;
            case "skipto": handleSkipTo(event); break;
            case "movetrack": handleMoveTrack(event); break;
            case "playnext": handlePlayNext(event); break;
            case "forceremove": handleForceRemove(event); break;
            case "prefix": handlePrefix(event); break;
            case "setdj": handleSetDJ(event); break;
            case "settc": handleSetTC(event); break;
            case "setvc": handleSetVC(event); break;
            case "skipratio": handleSkipRatio(event); break;
            case "queuetype": handleQueueType(event); break;
            default: event.reply("Unknown command.").setEphemeral(true).queue();
        }
    }

    // ========================
    // Permission Checks
    // ========================

    private boolean checkDJPermission(SlashCommandInteractionEvent event)
    {
        Member member = event.getMember();
        if (member == null) return false;
        if (String.valueOf(bot.getConfig().getOwnerId()).equals(member.getId())) return true;
        if (member.hasPermission(Permission.MANAGE_SERVER)) return true;
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        Role djRole = settings.getRole(event.getGuild());
        return djRole != null && (member.getRoles().contains(djRole) || djRole.getIdLong() == event.getGuild().getIdLong());
    }

    private boolean checkVoiceState(SlashCommandInteractionEvent event, boolean needsPlaying)
    {
        Member member = event.getMember();
        Guild guild = event.getGuild();
        if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel())
        {
            event.reply(bot.getConfig().getError() + " You must be in a voice channel to use this command.").setEphemeral(true).queue();
            return false;
        }
        if (needsPlaying)
        {
            AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
            if (handler == null || handler.getPlayer().getPlayingTrack() == null)
            {
                event.reply(bot.getConfig().getError() + " There must be music playing to use that!").setEphemeral(true).queue();
                return false;
            }
        }
        return true;
    }

    private boolean connectToVoiceChannel(SlashCommandInteractionEvent event)
    {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel())
        {
            event.reply(bot.getConfig().getError() + " You must be in a voice channel!").setEphemeral(true).queue();
            return false;
        }
        AudioChannel target = member.getVoiceState().getChannel();
        AudioChannel current = guild.getSelfMember().getVoiceState().getChannel();
        if (current == null || !current.equals(target))
        {
            try { guild.getAudioManager().openAudioConnection((VoiceChannel) target); }
            catch (PermissionException ex)
            {
                event.reply(bot.getConfig().getError() + " I am unable to connect to " + target.getAsMention() + "!").setEphemeral(true).queue();
                return false;
            }
        }
        return true;
    }

    // ========================
    // General Commands
    // ========================

    private void handleSettings(SlashCommandInteractionEvent event)
    {
        Settings s = bot.getSettingsManager().getSettings(event.getGuild());
        TextChannel tchan = s.getTextChannel(event.getGuild());
        VoiceChannel vchan = s.getVoiceChannel(event.getGuild());
        Role role = s.getRole(event.getGuild());
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(event.getGuild().getSelfMember().getColor())
                .setTitle("\uD83C\uDFA7 " + event.getJDA().getSelfUser().getName() + " Settings")
                .setDescription(
                        "**Text Channel:** " + (tchan == null ? "Any" : tchan.getAsMention()) +
                        "\n**Voice Channel:** " + (vchan == null ? "Any" : vchan.getAsMention()) +
                        "\n**DJ Role:** " + (role == null ? "None" : role.getAsMention()) +
                        "\n**Prefix:** " + (s.getPrefix() == null ? "Default" : "`" + s.getPrefix() + "`") +
                        "\n**Repeat Mode:** " + s.getRepeatMode().getUserFriendlyName() +
                        "\n**Queue Type:** " + s.getQueueType().getUserFriendlyName() +
                        "\n**Default Playlist:** " + (s.getDefaultPlaylist() == null ? "None" : s.getDefaultPlaylist()))
                .setFooter(event.getJDA().getGuilds().size() + " servers");
        event.replyEmbeds(eb.build()).queue();
    }

    // ========================
    // Music Commands
    // ========================

    private void handlePlay(SlashCommandInteractionEvent event, boolean playTop)
    {
        if (!checkVoiceState(event, false)) return;
        if (!connectToVoiceChannel(event)) return;
        String query = event.getOption("query").getAsString();
        bot.getPlayerManager().setUpHandler(event.getGuild());
        event.deferReply().queue(hook -> {
            bot.getPlayerManager().loadItemOrdered(event.getGuild(), query, new PlayResultHandler(hook, event, query, playTop, false));
        });
    }

    private void handleNowPlaying(SlashCommandInteractionEvent event)
    {
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (handler == null)
        {
            event.reply(bot.getConfig().getWarning() + " Nothing is currently playing.").queue();
            return;
        }
        MessageCreateData m = handler.getNowPlaying(event.getJDA());
        if (m == null)
        {
            event.reply(handler.getNoMusicPlaying(event.getJDA()).getContent()).queue();
        }
        else
        {
            event.reply(m.getContent()).addEmbeds(m.getEmbeds()).queue(ih -> ih.retrieveOriginal().queue(msg -> bot.getNowplayingHandler().setLastNPMessage(msg)));
        }
    }

    private void handleQueue(SlashCommandInteractionEvent event)
    {
        AudioHandler ah = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (ah == null)
        {
            event.reply(bot.getConfig().getWarning() + " Nothing is currently playing.").queue();
            return;
        }
        List<QueuedTrack> list = ah.getQueue().getList();
        if (list.isEmpty())
        {
            MessageCreateData nowp = ah.getNowPlaying(event.getJDA());
            if (nowp != null)
                event.reply(bot.getConfig().getWarning() + " There is no music in the queue!").addEmbeds(nowp.getEmbeds()).queue();
            else
                event.reply(bot.getConfig().getWarning() + " There is no music in the queue!").queue();
            return;
        }
        int page = event.getOption("page") != null ? (int) event.getOption("page").getAsLong() : 1;
        int itemsPerPage = 10;
        int pages = (int) Math.ceil((double) list.size() / itemsPerPage);
        page = Math.max(1, Math.min(page, pages));
        StringBuilder sb = new StringBuilder();
        AudioTrack current = ah.getPlayer().getPlayingTrack();
        if (current != null)
            sb.append(ah.getStatusEmoji()).append(" **").append(current.getInfo().title).append("**\n\n");
        int start = (page - 1) * itemsPerPage;
        int end = Math.min(start + itemsPerPage, list.size());
        long total = 0;
        for (int i = 0; i < list.size(); i++)
        {
            total += list.get(i).getTrack().getDuration();
            if (i >= start && i < end)
                sb.append("`").append(i + 1).append(".` ").append(list.get(i).toString()).append("\n");
        }
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(event.getGuild().getSelfMember().getColor())
                .setTitle(bot.getConfig().getSuccess() + " Current Queue | " + list.size() + " entries | `" + TimeUtil.formatTime(total) + "`")
                .setDescription(sb.toString())
                .setFooter("Page " + page + "/" + pages + " | " + settings.getQueueType().getUserFriendlyName() +
                        (settings.getRepeatMode() != RepeatMode.OFF ? " | " + settings.getRepeatMode().getUserFriendlyName() : ""));
        event.replyEmbeds(eb.build()).queue();
    }

    private void handleSkip(SlashCommandInteractionEvent event)
    {
        if (!checkVoiceState(event, true)) return;
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        RequestMetadata rm = handler.getRequestMetadata();
        double skipRatio = bot.getSettingsManager().getSettings(event.getGuild()).getSkipRatio();
        if (skipRatio == -1) skipRatio = bot.getConfig().getSkipRatio();
        if (event.getUser().getIdLong() == rm.getOwner() || skipRatio == 0)
        {
            event.reply(bot.getConfig().getSuccess() + " Skipped **" + handler.getPlayer().getPlayingTrack().getInfo().title + "**").queue();
            handler.getPlayer().stopTrack();
        }
        else
        {
            int listeners = (int) event.getGuild().getSelfMember().getVoiceState().getChannel().getMembers().stream()
                    .filter(m -> !m.getUser().isBot() && !m.getVoiceState().isDeafened()).count();
            String msg;
            if (handler.getVotes().contains(event.getUser().getId()))
                msg = bot.getConfig().getWarning() + " You already voted to skip this song `[";
            else
            {
                msg = bot.getConfig().getSuccess() + " You voted to skip the song `[";
                handler.getVotes().add(event.getUser().getId());
            }
            int skippers = (int) event.getGuild().getSelfMember().getVoiceState().getChannel().getMembers().stream()
                    .filter(m -> handler.getVotes().contains(m.getUser().getId())).count();
            int required = (int) Math.ceil(listeners * skipRatio);
            msg += skippers + " votes, " + required + "/" + listeners + " needed]`";
            if (skippers >= required)
            {
                msg += "\n" + bot.getConfig().getSuccess() + " Skipped **" + handler.getPlayer().getPlayingTrack().getInfo().title + "**";
                handler.getPlayer().stopTrack();
            }
            event.reply(msg).queue();
        }
    }

    private void handleRemove(SlashCommandInteractionEvent event)
    {
        if (!checkVoiceState(event, true)) return;
        String arg = event.getOption("position").getAsString();
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (handler.getQueue().isEmpty())
        {
            event.reply(bot.getConfig().getError() + " There is nothing in the queue!").setEphemeral(true).queue();
            return;
        }
        if (arg.equalsIgnoreCase("all"))
        {
            int count = handler.getQueue().removeAll(event.getUser().getIdLong());
            if (count == 0) event.reply(bot.getConfig().getWarning() + " You don't have any songs in the queue!").queue();
            else event.reply(bot.getConfig().getSuccess() + " Successfully removed your " + count + " entries.").queue();
            return;
        }
        int pos;
        try { pos = Integer.parseInt(arg); }
        catch (NumberFormatException e)
        {
            event.reply(bot.getConfig().getError() + " Please provide a valid position or 'all'.").setEphemeral(true).queue();
            return;
        }
        if (pos < 1 || pos > handler.getQueue().size())
        {
            event.reply(bot.getConfig().getError() + " Position must be between 1 and " + handler.getQueue().size() + "!").setEphemeral(true).queue();
            return;
        }
        QueuedTrack qt = handler.getQueue().get(pos - 1);
        boolean isDJ = checkDJPermission(event);
        if (qt.getIdentifier() == event.getUser().getIdLong() || isDJ)
        {
            handler.getQueue().remove(pos - 1);
            event.reply(bot.getConfig().getSuccess() + " Removed **" + qt.getTrack().getInfo().title + "** from the queue").queue();
        }
        else
            event.reply(bot.getConfig().getError() + " You cannot remove that track because you didn't add it!").setEphemeral(true).queue();
    }

    private void handleShuffle(SlashCommandInteractionEvent event)
    {
        if (!checkVoiceState(event, true)) return;
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        int shuffled = handler.getQueue().shuffle(event.getUser().getIdLong());
        switch (shuffled)
        {
            case 0: event.reply(bot.getConfig().getError() + " You don't have any music in the queue to shuffle!").queue(); break;
            case 1: event.reply(bot.getConfig().getWarning() + " You only have one song in the queue!").queue(); break;
            default: event.reply(bot.getConfig().getSuccess() + " You successfully shuffled your " + shuffled + " entries.").queue();
        }
    }

    private void handleSeek(SlashCommandInteractionEvent event)
    {
        if (!checkVoiceState(event, true)) return;
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        AudioTrack track = handler.getPlayer().getPlayingTrack();
        if (!track.isSeekable())
        {
            event.reply(bot.getConfig().getError() + " This track is not seekable.").setEphemeral(true).queue();
            return;
        }
        RequestMetadata rm = handler.getRequestMetadata();
        if (!checkDJPermission(event) && rm.getOwner() != event.getUser().getIdLong())
        {
            event.reply(bot.getConfig().getError() + " You cannot seek this track because you didn't add it!").setEphemeral(true).queue();
            return;
        }
        String args = event.getOption("time").getAsString();
        TimeUtil.SeekTime seekTime = TimeUtil.parseTime(args);
        if (seekTime == null)
        {
            event.reply(bot.getConfig().getError() + " Invalid time format! Examples: `1:30`, `+30`, `-15`, `1m30s`").setEphemeral(true).queue();
            return;
        }
        long currentPos = track.getPosition();
        long duration = track.getDuration();
        long seekMs = seekTime.relative ? currentPos + seekTime.milliseconds : seekTime.milliseconds;
        if (seekMs < 0 || seekMs > duration)
        {
            event.reply(bot.getConfig().getError() + " Cannot seek to that position!").setEphemeral(true).queue();
            return;
        }
        track.setPosition(seekMs);
        event.reply(bot.getConfig().getSuccess() + " Seeked to `" + TimeUtil.formatTime(seekMs) + "/" + TimeUtil.formatTime(duration) + "`").queue();
    }

    private void handleLyrics(SlashCommandInteractionEvent event)
    {
        event.reply(bot.getConfig().getWarning() + " Lyrics search via slash commands is not yet fully implemented. Please use the message command for full functionality.").setEphemeral(true).queue();
    }

    private void handlePlaylists(SlashCommandInteractionEvent event)
    {
        List<String> playlists = bot.getPlaylistLoader().getPlaylistNames();
        if (playlists == null || playlists.isEmpty())
        {
            event.reply(bot.getConfig().getWarning() + " No playlists available.").queue();
            return;
        }
        StringBuilder sb = new StringBuilder(bot.getConfig().getSuccess() + " Available playlists:\n");
        playlists.forEach(name -> sb.append("`").append(name).append("`\n"));
        event.reply(sb.toString()).queue();
    }

    // ========================
    // DJ Commands
    // ========================

    private void handleForceSkip(SlashCommandInteractionEvent event)
    {
        if (!checkDJPermission(event))
        {
            event.reply(bot.getConfig().getError() + " You need DJ permissions to use this command.").setEphemeral(true).queue();
            return;
        }
        if (!checkVoiceState(event, true)) return;
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        RequestMetadata rm = handler.getRequestMetadata();
        event.reply(bot.getConfig().getSuccess() + " Skipped **" + handler.getPlayer().getPlayingTrack().getInfo().title + "**" +
                (rm.getOwner() == 0L ? " (autoplay)" : " (requested by **" + FormatUtil.formatUsername(rm.user) + "**)")).queue();
        handler.getPlayer().stopTrack();
    }

    private void handlePause(SlashCommandInteractionEvent event)
    {
        if (!checkDJPermission(event))
        {
            event.reply(bot.getConfig().getError() + " You need DJ permissions to use this command.").setEphemeral(true).queue();
            return;
        }
        if (!checkVoiceState(event, true)) return;
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (handler.getPlayer().isPaused())
        {
            handler.getPlayer().setPaused(false);
            event.reply(bot.getConfig().getSuccess() + " Resumed **" + handler.getPlayer().getPlayingTrack().getInfo().title + "**").queue();
        }
        else
        {
            handler.getPlayer().setPaused(true);
            event.reply(bot.getConfig().getSuccess() + " Paused **" + handler.getPlayer().getPlayingTrack().getInfo().title + "**. Use `/pause` again to resume.").queue();
        }
    }

    private void handleStop(SlashCommandInteractionEvent event)
    {
        if (!checkDJPermission(event))
        {
            event.reply(bot.getConfig().getError() + " You need DJ permissions to use this command.").setEphemeral(true).queue();
            return;
        }
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (handler != null) handler.stopAndClear();
        event.getGuild().getAudioManager().closeAudioConnection();
        event.reply(bot.getConfig().getSuccess() + " The player has stopped and the queue has been cleared.").queue();
    }

    private void handleVolume(SlashCommandInteractionEvent event)
    {
        if (!checkDJPermission(event))
        {
            event.reply(bot.getConfig().getError() + " You need DJ permissions to use this command.").setEphemeral(true).queue();
            return;
        }
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (handler == null)
        {
            event.reply(bot.getConfig().getWarning() + " No audio handler active.").setEphemeral(true).queue();
            return;
        }
        int currentVolume = handler.getPlayer().getVolume();
        if (event.getOption("level") == null)
        {
            event.reply(FormatUtil.volumeIcon(currentVolume) + " Current volume is `" + currentVolume + "`").queue();
        }
        else
        {
            int newVolume = (int) event.getOption("level").getAsLong();
            if (newVolume < 0 || newVolume > 150)
            {
                event.reply(bot.getConfig().getError() + " Volume must be between 0 and 150!").setEphemeral(true).queue();
                return;
            }
            handler.getPlayer().setVolume(newVolume);
            bot.getSettingsManager().getSettings(event.getGuild()).setVolume(newVolume);
            event.reply(FormatUtil.volumeIcon(newVolume) + " Volume changed from `" + currentVolume + "` to `" + newVolume + "`").queue();
        }
    }

    private void handleRepeat(SlashCommandInteractionEvent event)
    {
        if (!checkDJPermission(event))
        {
            event.reply(bot.getConfig().getError() + " You need DJ permissions to use this command.").setEphemeral(true).queue();
            return;
        }
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        String mode = event.getOption("mode") != null ? event.getOption("mode").getAsString() : null;
        RepeatMode value;
        if (mode == null) value = settings.getRepeatMode() == RepeatMode.OFF ? RepeatMode.ALL : RepeatMode.OFF;
        else
        {
            switch (mode.toLowerCase())
            {
                case "off": value = RepeatMode.OFF; break;
                case "all": value = RepeatMode.ALL; break;
                case "single": value = RepeatMode.SINGLE; break;
                default:
                    event.reply(bot.getConfig().getError() + " Invalid repeat mode!").setEphemeral(true).queue();
                    return;
            }
        }
        settings.setRepeatMode(value);
        event.reply(bot.getConfig().getSuccess() + " Repeat mode is now `" + value.getUserFriendlyName() + "`").queue();
    }

    private void handleSkipTo(SlashCommandInteractionEvent event)
    {
        if (!checkDJPermission(event))
        {
            event.reply(bot.getConfig().getError() + " You need DJ permissions to use this command.").setEphemeral(true).queue();
            return;
        }
        if (!checkVoiceState(event, true)) return;
        int index = (int) event.getOption("position").getAsLong();
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (index < 1 || index > handler.getQueue().size())
        {
            event.reply(bot.getConfig().getError() + " Position must be between 1 and " + handler.getQueue().size() + "!").setEphemeral(true).queue();
            return;
        }
        handler.getQueue().skip(index - 1);
        event.reply(bot.getConfig().getSuccess() + " Skipped to **" + handler.getQueue().get(0).getTrack().getInfo().title + "**").queue();
        handler.getPlayer().stopTrack();
    }

    private void handleMoveTrack(SlashCommandInteractionEvent event)
    {
        if (!checkDJPermission(event))
        {
            event.reply(bot.getConfig().getError() + " You need DJ permissions to use this command.").setEphemeral(true).queue();
            return;
        }
        if (!checkVoiceState(event, true)) return;
        int from = (int) event.getOption("from").getAsLong();
        int to = (int) event.getOption("to").getAsLong();
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (from == to)
        {
            event.reply(bot.getConfig().getError() + " Can't move a track to the same position.").setEphemeral(true).queue();
            return;
        }
        int size = handler.getQueue().size();
        if (from < 1 || from > size || to < 1 || to > size)
        {
            event.reply(bot.getConfig().getError() + " Position must be between 1 and " + size + "!").setEphemeral(true).queue();
            return;
        }
        QueuedTrack track = handler.getQueue().moveItem(from - 1, to - 1);
        event.reply(bot.getConfig().getSuccess() + " Moved **" + track.getTrack().getInfo().title + "** from position `" + from + "` to `" + to + "`.").queue();
    }

    private void handlePlayNext(SlashCommandInteractionEvent event)
    {
        if (!checkDJPermission(event))
        {
            event.reply(bot.getConfig().getError() + " You need DJ permissions to use this command.").setEphemeral(true).queue();
            return;
        }
        if (!checkVoiceState(event, false)) return;
        if (!connectToVoiceChannel(event)) return;
        String query = event.getOption("query").getAsString();
        bot.getPlayerManager().setUpHandler(event.getGuild());
        event.deferReply().queue(hook -> {
            bot.getPlayerManager().loadItemOrdered(event.getGuild(), query, new PlayNextResultHandler(hook, event, query));
        });
    }

    private void handleForceRemove(SlashCommandInteractionEvent event)
    {
        if (!checkDJPermission(event))
        {
            event.reply(bot.getConfig().getError() + " You need DJ permissions to use this command.").setEphemeral(true).queue();
            return;
        }
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (handler == null || handler.getQueue().isEmpty())
        {
            event.reply(bot.getConfig().getError() + " There is nothing in the queue!").setEphemeral(true).queue();
            return;
        }
        User target = event.getOption("user").getAsUser();
        int count = handler.getQueue().removeAll(target.getIdLong());
        if (count == 0)
            event.reply(bot.getConfig().getWarning() + " **" + target.getName() + "** doesn't have any songs in the queue!").queue();
        else
            event.reply(bot.getConfig().getSuccess() + " Successfully removed " + count + " entries from **" + target.getName() + "**.").queue();
    }

    // ========================
    // Admin Commands
    // ========================

    private void handlePrefix(SlashCommandInteractionEvent event)
    {
        String prefix = event.getOption("prefix").getAsString();
        Settings s = bot.getSettingsManager().getSettings(event.getGuild());
        if (prefix.equalsIgnoreCase("none"))
        {
            s.setPrefix(null);
            event.reply(bot.getConfig().getSuccess() + " Prefix cleared.").queue();
        }
        else
        {
            s.setPrefix(prefix);
            event.reply(bot.getConfig().getSuccess() + " Prefix set to `" + prefix + "`").queue();
        }
    }

    private void handleSetDJ(SlashCommandInteractionEvent event)
    {
        Settings s = bot.getSettingsManager().getSettings(event.getGuild());
        if (event.getOption("role") == null)
        {
            s.setDJRole(null);
            event.reply(bot.getConfig().getSuccess() + " DJ role cleared.").queue();
        }
        else
        {
            Role role = event.getOption("role").getAsRole();
            s.setDJRole(role);
            event.reply(bot.getConfig().getSuccess() + " DJ role set to **" + role.getName() + "**.").queue();
        }
    }

    private void handleSetTC(SlashCommandInteractionEvent event)
    {
        Settings s = bot.getSettingsManager().getSettings(event.getGuild());
        if (event.getOption("channel") == null)
        {
            s.setTextChannel(null);
            event.reply(bot.getConfig().getSuccess() + " Music commands can now be used in any channel.").queue();
        }
        else
        {
            TextChannel tc = event.getOption("channel").getAsChannel().asTextChannel();
            s.setTextChannel(tc);
            event.reply(bot.getConfig().getSuccess() + " Music commands are now restricted to " + tc.getAsMention()).queue();
        }
    }

    private void handleSetVC(SlashCommandInteractionEvent event)
    {
        Settings s = bot.getSettingsManager().getSettings(event.getGuild());
        if (event.getOption("channel") == null)
        {
            s.setVoiceChannel(null);
            event.reply(bot.getConfig().getSuccess() + " Music can now be played in any voice channel.").queue();
        }
        else
        {
            VoiceChannel vc = event.getOption("channel").getAsChannel().asVoiceChannel();
            s.setVoiceChannel(vc);
            event.reply(bot.getConfig().getSuccess() + " Music will now only be played in " + vc.getAsMention()).queue();
        }
    }

    private void handleSkipRatio(SlashCommandInteractionEvent event)
    {
        Settings s = bot.getSettingsManager().getSettings(event.getGuild());
        if (event.getOption("ratio") == null)
        {
            double current = s.getSkipRatio();
            if (current == -1) current = bot.getConfig().getSkipRatio();
            event.reply(bot.getConfig().getSuccess() + " Current skip ratio is `" + (int)(current * 100) + "%`").queue();
        }
        else
        {
            double ratio = event.getOption("ratio").getAsDouble();
            if (ratio < 0 || ratio > 1)
            {
                event.reply(bot.getConfig().getError() + " Skip ratio must be between 0 and 1!").setEphemeral(true).queue();
                return;
            }
            s.setSkipRatio(ratio);
            event.reply(bot.getConfig().getSuccess() + " Skip ratio set to `" + (int)(ratio * 100) + "%`").queue();
        }
    }

    private void handleQueueType(SlashCommandInteractionEvent event)
    {
        Settings s = bot.getSettingsManager().getSettings(event.getGuild());
        if (event.getOption("type") == null)
        {
            event.reply(bot.getConfig().getSuccess() + " Current queue type is `" + s.getQueueType().getUserFriendlyName() + "`").queue();
        }
        else
        {
            String type = event.getOption("type").getAsString();
            QueueType qt = type.equalsIgnoreCase("fair") ? QueueType.FAIR : QueueType.LINEAR;
            s.setQueueType(qt);
            event.reply(bot.getConfig().getSuccess() + " Queue type set to `" + qt.getUserFriendlyName() + "`").queue();
        }
    }

    // ========================
    // Audio Load Result Handlers
    // ========================

    private class PlayResultHandler implements AudioLoadResultHandler
    {
        private final InteractionHook hook;
        private final SlashCommandInteractionEvent event;
        private final String args;
        private final boolean playTop;
        private final boolean ytsearch;

        PlayResultHandler(InteractionHook hook, SlashCommandInteractionEvent event, String args, boolean playTop, boolean ytsearch)
        {
            this.hook = hook;
            this.event = event;
            this.args = args;
            this.playTop = playTop;
            this.ytsearch = ytsearch;
        }

        private void loadSingle(AudioTrack track)
        {
            if (bot.getConfig().isTooLong(track))
            {
                hook.editOriginal(bot.getConfig().getWarning() + " This track (**" + track.getInfo().title + "**) is longer than the allowed maximum: `" +
                        TimeUtil.formatTime(track.getDuration()) + "` > `" + TimeUtil.formatTime(bot.getConfig().getMaxSeconds() * 1000) + "`").queue();
                return;
            }
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            int pos;
            if (playTop)
                pos = handler.addTrackToFront(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), args, track))) + 1;
            else
                pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), args, track))) + 1;
            String addMsg = FormatUtil.filter(bot.getConfig().getSuccess() + " Added **" + track.getInfo().title +
                    "** (`" + TimeUtil.formatTime(track.getDuration()) + "`) " + (pos == 0 ? "to begin playing" : "to the queue at position " + pos));
            hook.editOriginal(addMsg).queue();
        }

        private int loadPlaylist(AudioPlaylist playlist, AudioTrack exclude)
        {
            int[] count = {0};
            playlist.getTracks().forEach(track -> {
                if (!bot.getConfig().isTooLong(track) && !track.equals(exclude))
                {
                    AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
                    handler.addTrack(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), args, track)));
                    count[0]++;
                }
            });
            return count[0];
        }

        @Override
        public void trackLoaded(AudioTrack track) { loadSingle(track); }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            if (playlist.getTracks().size() == 1 || playlist.isSearchResult())
            {
                AudioTrack single = playlist.getSelectedTrack() == null ? playlist.getTracks().get(0) : playlist.getSelectedTrack();
                loadSingle(single);
            }
            else if (playlist.getSelectedTrack() != null)
                loadSingle(playlist.getSelectedTrack());
            else
            {
                int count = loadPlaylist(playlist, null);
                if (playlist.getTracks().isEmpty())
                    hook.editOriginal(bot.getConfig().getWarning() + " The playlist could not be loaded or contained 0 entries.").queue();
                else if (count == 0)
                    hook.editOriginal(bot.getConfig().getWarning() + " All entries in this playlist were too long.").queue();
                else
                    hook.editOriginal(bot.getConfig().getSuccess() + " Loaded playlist **" + (playlist.getName() != null ? playlist.getName() : "Unknown") + "** with `" + count + "` entries!").queue();
            }
        }

        @Override
        public void noMatches()
        {
            if (ytsearch)
                hook.editOriginal(bot.getConfig().getWarning() + " No results found for `" + args + "`.").queue();
            else
                bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:" + args, new PlayResultHandler(hook, event, args, playTop, true));
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            if (throwable.severity == Severity.COMMON)
                hook.editOriginal(bot.getConfig().getError() + " Error loading: " + throwable.getMessage()).queue();
            else
                hook.editOriginal(bot.getConfig().getError() + " Error loading track.").queue();
        }
    }

    private class PlayNextResultHandler implements AudioLoadResultHandler
    {
        private final InteractionHook hook;
        private final SlashCommandInteractionEvent event;
        private final String args;

        PlayNextResultHandler(InteractionHook hook, SlashCommandInteractionEvent event, String args)
        {
            this.hook = hook;
            this.event = event;
            this.args = args;
        }

        @Override
        public void trackLoaded(AudioTrack track)
        {
            if (bot.getConfig().isTooLong(track))
            {
                hook.editOriginal(bot.getConfig().getWarning() + " This track is longer than the allowed maximum.").queue();
                return;
            }
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrackToFront(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), args, track))) + 1;
            hook.editOriginal(bot.getConfig().getSuccess() + " Added **" + track.getInfo().title + "** to play next" + (pos == 0 ? "" : " (position " + pos + ")")).queue();
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            AudioTrack track = playlist.getSelectedTrack() != null ? playlist.getSelectedTrack() : playlist.getTracks().get(0);
            trackLoaded(track);
        }

        @Override
        public void noMatches()
        {
            bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:" + args, new AudioLoadResultHandler()
            {
                @Override public void trackLoaded(AudioTrack track) { PlayNextResultHandler.this.trackLoaded(track); }
                @Override public void playlistLoaded(AudioPlaylist playlist) { PlayNextResultHandler.this.playlistLoaded(playlist); }
                @Override public void noMatches() { hook.editOriginal(bot.getConfig().getWarning() + " No results found for `" + args + "`.").queue(); }
                @Override public void loadFailed(FriendlyException throwable) { hook.editOriginal(bot.getConfig().getError() + " Error loading track.").queue(); }
            });
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            hook.editOriginal(bot.getConfig().getError() + " Error loading: " + throwable.getMessage()).queue();
        }
    }
}
