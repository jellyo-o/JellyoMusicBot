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
import com.jagrosh.jmusicbot.commands.CommandChecks;
import com.jagrosh.jmusicbot.commands.SlashCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.commands.admin.PrefixCmd;
import com.jagrosh.jmusicbot.commands.admin.QueueTypeCmd;
import com.jagrosh.jmusicbot.commands.admin.SkipratioCmd;
import com.jagrosh.jmusicbot.commands.dj.ForceskipCmd;
import com.jagrosh.jmusicbot.commands.dj.MoveTrackCmd;
import com.jagrosh.jmusicbot.commands.dj.RepeatCmd;
import com.jagrosh.jmusicbot.commands.dj.SkiptoCmd;
import com.jagrosh.jmusicbot.commands.dj.StopCmd;
import com.jagrosh.jmusicbot.commands.dj.VolumeCmd;
import com.jagrosh.jmusicbot.lyrics.InputValidator;
import com.jagrosh.jmusicbot.lyrics.LyricsCache;
import com.jagrosh.jmusicbot.lyrics.LyricsService;
import com.jagrosh.jmusicbot.commands.music.RemoveCmd;
import com.jagrosh.jmusicbot.commands.music.SeekCmd;
import com.jagrosh.jmusicbot.commands.music.ShuffleCmd;
import com.jagrosh.jmusicbot.commands.music.SkipCmd;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader.Playlist;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.OtherUtil;
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
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Handles all slash command interactions for the music bot.
 */
public class SlashCommandListener extends ListenerAdapter
{
    private final static Logger LOG = LoggerFactory.getLogger(SlashCommandListener.class);
    private static final int MAX_AUTOCOMPLETE_CHOICES = 25;
    private static final int MAX_AUTOCOMPLETE_LENGTH = 100;
    private static final int MAX_SEARCH_RESULTS = 5;
    private static final long SEARCH_MENU_EXPIRATION_MS = 15 * 60 * 1000L;
    private static final String SEARCH_MENU_PREFIX = "jmb-search:";
    private static final AtomicBoolean SLASH_COMMAND_REGISTRATION_STARTED = new AtomicBoolean();
    private final Bot bot;
    private final AtomicLong searchMenuCounter = new AtomicLong();
    private final Map<String, SearchMenuState> searchMenus = new ConcurrentHashMap<>();
    private volatile LyricsService lyricsService;
    private final SkipCmd skipCmd;
    private final RemoveCmd removeCmd;
    private final ShuffleCmd shuffleCmd;
    private final SeekCmd seekCmd;
    private final ForceskipCmd forceskipCmd;
    private final StopCmd stopCmd;
    private final VolumeCmd volumeCmd;
    private final RepeatCmd repeatCmd;
    private final SkiptoCmd skiptoCmd;
    private final MoveTrackCmd moveTrackCmd;
    private final PrefixCmd prefixCmd;
    private final SkipratioCmd skipratioCmd;
    private final QueueTypeCmd queueTypeCmd;

    public SlashCommandListener(Bot bot)
    {
        this.bot = bot;
        this.skipCmd = new SkipCmd(bot);
        this.removeCmd = new RemoveCmd(bot);
        this.shuffleCmd = new ShuffleCmd(bot);
        this.seekCmd = new SeekCmd(bot);
        this.forceskipCmd = new ForceskipCmd(bot);
        this.stopCmd = new StopCmd(bot);
        this.volumeCmd = new VolumeCmd(bot);
        this.repeatCmd = new RepeatCmd(bot);
        this.skiptoCmd = new SkiptoCmd(bot);
        this.moveTrackCmd = new MoveTrackCmd(bot);
        this.prefixCmd = new PrefixCmd(bot);
        this.skipratioCmd = new SkipratioCmd(bot);
        this.queueTypeCmd = new QueueTypeCmd(bot);
    }

    @Override
    public void onReady(ReadyEvent event)
    {
        registerSlashCommands(event.getJDA());
    }

    private void registerSlashCommands(JDA jda)
    {
        if(!SLASH_COMMAND_REGISTRATION_STARTED.compareAndSet(false, true))
        {
            LOG.debug("Slash command registration already checked for this process");
            return;
        }

        List<SlashCommandData> commands = buildSlashCommands();

        clearGuildSlashCommands(jda);
        jda.retrieveCommands().queue(
                existing ->
                {
                    if(!commandsNeedUpdate(commands, existing))
                    {
                        LOG.info("Global slash commands are already up to date");
                        return;
                    }

                    jda.updateCommands().addCommands(commands).queue(
                            cmds -> LOG.info("Registered {} global slash commands", cmds.size()),
                            err -> LOG.error("Failed to register slash commands", err)
                    );
                },
                err -> LOG.warn("Failed to retrieve current slash commands; skipping automatic registration to avoid repeated overwrites", err)
        );
    }

    static boolean commandsNeedUpdate(List<SlashCommandData> desiredCommands, List<Command> existingCommands)
    {
        List<CommandData> existingData = existingCommands.stream()
                .filter(command -> command.getType() == Command.Type.SLASH)
                .map(CommandData::fromCommand)
                .collect(Collectors.toList());

        return commandDataNeedUpdate(desiredCommands, existingData);
    }

    static boolean commandDataNeedUpdate(List<SlashCommandData> desiredCommands, List<? extends CommandData> existingCommands)
    {
        Map<String, ? extends CommandData> existingByName = existingCommands.stream()
                .collect(Collectors.toMap(CommandData::getName, command -> command));
        if(existingByName.size() != desiredCommands.size())
            return true;

        for(SlashCommandData desired : desiredCommands)
        {
            CommandData existing = existingByName.get(desired.getName());
            if(existing == null || !existing.toData().equals(desired.toData()))
                return true;
        }
        return false;
    }

    static List<SlashCommandData> buildSlashCommands()
    {
        List<SlashCommandData> commands = new ArrayList<>();

        // General commands
        commands.add(Commands.slash("about", "Shows information about the bot"));
        commands.add(Commands.slash("help", "Shows available commands"));
        commands.add(Commands.slash("ping", "Checks the bot latency"));
        commands.add(Commands.slash("settings", "Shows the bot settings"));

        // Music commands (anyone can use)
        commands.add(Commands.slash("play", "Play a song")
                .addOptions(songQueryOption()));
        commands.add(Commands.slash("playtop", "Play a song at the top of the queue")
                .addOptions(songQueryOption()));
        commands.add(Commands.slash("playplaylist", "Play a saved playlist")
                .addOptions(playlistNameOption()));
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
        commands.add(Commands.slash("correctlyrics", "Correct cached lyrics for a song")
                .addOptions(
                        new OptionData(OptionType.STRING, "url", "Genius lyrics URL", true),
                        new OptionData(OptionType.STRING, "query", "Song to correct", true)));
        commands.add(Commands.slash("playlists", "Shows available playlists"));
        commands.add(Commands.slash("search", "Search YouTube and choose a result")
                .addOptions(new OptionData(OptionType.STRING, "query", "Search query", true)));
        commands.add(Commands.slash("scsearch", "Search SoundCloud and choose a result")
                .addOptions(new OptionData(OptionType.STRING, "query", "Search query", true)));

        // DJ commands
        commands.add(Commands.slash("forceskip", "Force skip the current song"));
        commands.add(Commands.slash("pause", "Pause or resume the current song"));
        commands.add(Commands.slash("resume", "Resume the current song if paused"));
        commands.add(Commands.slash("stop", "Stop playback and clear the queue"));
        commands.add(Commands.slash("volume", "Set or show the volume")
                .addOptions(new OptionData(OptionType.INTEGER, "level", "Volume level (0-150)", false)));
        commands.add(Commands.slash("repeat", "Toggle repeat mode")
                .addOptions(new OptionData(OptionType.STRING, "mode", "Repeat mode", false)
                        .addChoice("off", "off")
                        .addChoice("all", "all")
                        .addChoice("single", "single")));
        commands.add(Commands.slash("loop", "Toggle repeat mode")
                .addOptions(new OptionData(OptionType.STRING, "mode", "Repeat mode", false)
                        .addChoice("off", "off")
                        .addChoice("all", "all")
                        .addChoice("single", "single")));
        commands.add(Commands.slash("skipto", "Skip to a specific position in the queue")
                .addOptions(new OptionData(OptionType.INTEGER, "position", "Position to skip to", true)));
        commands.add(Commands.slash("move", "Move a track in the queue")
                .addOptions(new OptionData(OptionType.INTEGER, "from", "Current position", true))
                .addOptions(new OptionData(OptionType.INTEGER, "to", "New position", true)));
        commands.add(Commands.slash("playnext", "Play a song next in queue")
                .addOptions(songQueryOption()));
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
        commands.add(Commands.slash("setskip", "Set the skip vote percentage")
                .addOptions(new OptionData(OptionType.INTEGER, "percent", "Skip percentage (0-100)", true)
                        .setRequiredRange(0, 100))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(Commands.slash("queuetype", "Set the queue type")
                .addOptions(new OptionData(OptionType.STRING, "type", "Queue type", false)
                        .addChoice("fair", "fair")
                        .addChoice("linear", "linear"))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));

        return commands;
    }

    private static OptionData songQueryOption()
    {
        return new OptionData(OptionType.STRING, "query", "Song name or URL", true)
                .setAutoComplete(true);
    }

    private static OptionData playlistNameOption()
    {
        return new OptionData(OptionType.STRING, "name", "Playlist name", true)
                .setAutoComplete(true);
    }

    private void clearGuildSlashCommands(JDA jda)
    {
        jda.getGuilds().forEach(guild -> guild.retrieveCommands().queue(
                commands ->
                {
                    if(commands.isEmpty())
                        return;
                    guild.updateCommands().queue(
                            ignored -> LOG.info("Cleared {} guild-specific slash commands for {}", commands.size(), guild.getName()),
                            err -> LOG.warn("Failed to clear guild-specific slash commands for {}", guild.getName(), err)
                    );
                },
                err -> LOG.warn("Failed to retrieve guild-specific slash commands for {}", guild.getName(), err)
        ));
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

        LOG.debug("Slash command /{} invoked in guild {} ({}) by user {} ({})",
                commandName, guild.getName(), guild.getId(), event.getUser().getName(), event.getUser().getId());

        switch (commandName)
        {
            case "about": handleAbout(event); break;
            case "help": handleHelp(event); break;
            case "ping": handlePing(event); break;
            case "settings": handleSettings(event); break;
            case "play": handlePlay(event, false); break;
            case "playtop": handlePlay(event, true); break;
            case "playplaylist": handlePlayPlaylist(event); break;
            case "nowplaying": handleNowPlaying(event); break;
            case "queue": handleQueue(event); break;
            case "skip": handleSharedMusicCommand(event, skipCmd, "", false, true, true); break;
            case "remove": handleSharedMusicCommand(event, removeCmd, event.getOption("position").getAsString(), false, true, true); break;
            case "shuffle": handleSharedMusicCommand(event, shuffleCmd, "", false, true, true); break;
            case "seek": handleSharedMusicCommand(event, seekCmd, event.getOption("time").getAsString(), false, true, true); break;
            case "lyrics": handleLyrics(event); break;
            case "correctlyrics": handleCorrectLyrics(event); break;
            case "playlists": handlePlaylists(event); break;
            case "search": handleSearch(event, "ytsearch:", "YouTube"); break;
            case "scsearch": handleSearch(event, "scsearch:", "SoundCloud"); break;
            case "forceskip": handleSharedMusicCommand(event, forceskipCmd, "", true, true, false); break;
            case "pause": handlePause(event); break;
            case "resume": handleResume(event); break;
            case "stop": handleSharedMusicCommand(event, stopCmd, "", true, false, false); break;
            case "volume": handleSharedMusicCommand(event, volumeCmd, getOptionalLongArg(event, "level"), true, false, false); break;
            case "repeat": handleSharedDJCommand(event, repeatCmd, getOptionalStringArg(event, "mode")); break;
            case "loop": handleSharedDJCommand(event, repeatCmd, getOptionalStringArg(event, "mode")); break;
            case "skipto": handleSharedMusicCommand(event, skiptoCmd, String.valueOf(event.getOption("position").getAsLong()), true, true, false); break;
            case "move": handleSharedMusicCommand(event, moveTrackCmd, event.getOption("from").getAsLong() + " " + event.getOption("to").getAsLong(), true, true, false); break;
            case "playnext": handlePlayNext(event); break;
            case "forceremove": handleForceRemove(event); break;
            case "prefix": handleSharedAdminCommand(event, prefixCmd, event.getOption("prefix").getAsString()); break;
            case "setdj": handleSetDJ(event); break;
            case "settc": handleSetTC(event); break;
            case "setvc": handleSetVC(event); break;
            case "skipratio": handleSkipRatio(event); break;
            case "setskip": handleSharedAdminCommand(event, skipratioCmd, String.valueOf(event.getOption("percent").getAsLong())); break;
            case "queuetype": handleSharedAdminCommand(event, queueTypeCmd, getOptionalStringArg(event, "type")); break;
            default: event.reply("Unknown command.").setEphemeral(true).queue();
        }
    }

    private void handleSharedMusicCommand(SlashCommandInteractionEvent event, UnifiedCommand command, String args,
                                          boolean requiresDJ, boolean bePlaying, boolean beListening)
    {
        SlashCommandContext context = new SlashCommandContext(event, bot, args);
        if(requiresDJ && !CommandChecks.checkDJPermission(context, bot))
        {
            context.replyErrorEphemeral("You need DJ permissions to use this command.");
            return;
        }
        if(!CommandChecks.checkMusicCommand(bot, context, bePlaying, beListening))
            return;
        command.doCommand(context);
    }

    private void handleSharedDJCommand(SlashCommandInteractionEvent event, UnifiedCommand command, String args)
    {
        SlashCommandContext context = new SlashCommandContext(event, bot, args);
        if(!CommandChecks.checkDJPermission(context, bot))
        {
            context.replyErrorEphemeral("You need DJ permissions to use this command.");
            return;
        }
        command.doCommand(context);
    }

    private void handleSharedAdminCommand(SlashCommandInteractionEvent event, UnifiedCommand command, String args)
    {
        SlashCommandContext context = new SlashCommandContext(event, bot, args);
        if(!CommandChecks.checkAdminPermission(context, bot))
        {
            context.replyErrorEphemeral("You need Manage Server permission to use this command.");
            return;
        }
        command.doCommand(context);
    }

    private String getOptionalStringArg(SlashCommandInteractionEvent event, String name)
    {
        return event.getOption(name) == null ? "" : event.getOption(name).getAsString();
    }

    private String getOptionalLongArg(SlashCommandInteractionEvent event, String name)
    {
        return event.getOption(name) == null ? "" : String.valueOf(event.getOption(name).getAsLong());
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event)
    {
        try
        {
            if ("name".equals(event.getFocusedOption().getName()) && "playplaylist".equals(event.getName()))
            {
                event.replyChoices(getPlaylistNameChoices(event)).queue();
                return;
            }
            if ("query".equals(event.getFocusedOption().getName()) && isSongQueryCommand(event.getName()))
            {
                event.replyChoices(getSongQueryChoices(event)).queue();
                return;
            }
            event.replyChoices(Collections.emptyList()).queue();
        }
        catch (Exception ex)
        {
            LOG.warn("Failed to handle slash command autocomplete for /{}", event.getName(), ex);
            event.replyChoices(Collections.emptyList()).queue();
        }
    }

    private boolean isSongQueryCommand(String commandName)
    {
        return "play".equals(commandName) || "playtop".equals(commandName) || "playnext".equals(commandName);
    }

    private List<Command.Choice> getSongQueryChoices(CommandAutoCompleteInteractionEvent event)
    {
        List<Command.Choice> choices = new ArrayList<>();
        String query = event.getFocusedOption().getValue().trim();

        if (!query.isEmpty())
        {
            addChoice(choices, "Search YouTube: " + query, "ytsearch:" + query);
            addChoice(choices, "Search SoundCloud: " + query, "scsearch:" + query);
            addChoice(choices, "Play exactly: " + query, query);
        }

        Guild guild = event.getGuild();
        if (guild != null)
        {
            AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
            if (handler != null)
            {
                addTrackChoice(choices, "Current", handler.getPlayer().getPlayingTrack(), query);
                handler.getQueue().getList().forEach(track -> addTrackChoice(choices, "Queue", track.getTrack(), query));
            }
        }

        return choices;
    }

    private List<Command.Choice> getPlaylistNameChoices(CommandAutoCompleteInteractionEvent event)
    {
        String query = event.getFocusedOption().getValue().trim().toLowerCase(Locale.ROOT);
        List<Command.Choice> choices = new ArrayList<>();
        for (String name : bot.getPlaylistLoader().getPlaylistNames())
        {
            if (choices.size() >= MAX_AUTOCOMPLETE_CHOICES)
                break;
            if (name.length() > MAX_AUTOCOMPLETE_LENGTH)
                continue;
            if (query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query))
                choices.add(new Command.Choice(name, name));
        }
        return choices;
    }

    private void addTrackChoice(List<Command.Choice> choices, String source, AudioTrack track, String query)
    {
        if (track == null || choices.size() >= MAX_AUTOCOMPLETE_CHOICES)
            return;

        String title = track.getInfo().title;
        if (title == null || title.isBlank())
            return;

        String author = track.getInfo().author;
        String searchText = author == null || author.isBlank() ? title : title + " " + author;
        if (!query.isEmpty() && !searchText.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
            return;

        String display = author == null || author.isBlank() ? title : title + " - " + author;
        addChoice(choices, source + ": " + display, display);
    }

    private void addChoice(List<Command.Choice> choices, String name, String value)
    {
        if (choices.size() >= MAX_AUTOCOMPLETE_CHOICES || value == null || value.isBlank())
            return;

        String trimmedValue = value.trim();
        if (trimmedValue.length() > MAX_AUTOCOMPLETE_LENGTH)
            return;

        String truncatedName = truncate(name.trim(), MAX_AUTOCOMPLETE_LENGTH);
        boolean exists = choices.stream().anyMatch(choice -> choice.getName().equals(truncatedName) || choice.getAsString().equals(trimmedValue));
        if (!exists)
            choices.add(new Command.Choice(truncatedName, trimmedValue));
    }

    private String truncate(String value, int maxLength)
    {
        if (value.length() <= maxLength)
            return value;
        return value.substring(0, maxLength - 3) + "...";
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event)
    {
        String componentId = event.getComponentId();
        if (!componentId.startsWith(SEARCH_MENU_PREFIX))
            return;

        SearchMenuState state = searchMenus.get(componentId);
        if (state == null || state.isExpired())
        {
            searchMenus.remove(componentId);
            event.reply(bot.getConfig().getWarning() + " This search has expired. Please run the search again.").setEphemeral(true).queue();
            return;
        }
        if (event.getUser().getIdLong() != state.userId)
        {
            event.reply(bot.getConfig().getError() + " Only the user who ran the search can choose a result.").setEphemeral(true).queue();
            return;
        }
        if (event.getGuild() == null || event.getGuild().getIdLong() != state.guildId)
        {
            event.reply(bot.getConfig().getError() + " This search result cannot be used here.").setEphemeral(true).queue();
            return;
        }
        if (!isListeningInVoice(event))
        {
            event.reply(bot.getConfig().getError() + " You must be in a voice channel to choose a search result.").setEphemeral(true).queue();
            return;
        }

        int index;
        try
        {
            index = Integer.parseInt(event.getValues().get(0));
        }
        catch (RuntimeException ex)
        {
            event.reply(bot.getConfig().getError() + " Invalid search result selected.").setEphemeral(true).queue();
            return;
        }
        if (index < 0 || index >= state.tracks.size())
        {
            event.reply(bot.getConfig().getError() + " Invalid search result selected.").setEphemeral(true).queue();
            return;
        }

        AudioTrack track = state.tracks.get(index);
        if (bot.getConfig().isTooLong(track))
        {
            LOG.warn("Rejected selected slash search result in guild {} ({}): track too long; query='{}'; track={}",
                    event.getGuild().getName(), event.getGuild().getId(), state.query, describeTrack(track));
            event.reply(bot.getConfig().getWarning() + " This track (**" + track.getInfo().title + "**) is longer than the allowed maximum: `"
                    + TimeUtil.formatTime(track.getDuration()) + "` > `" + bot.getConfig().getMaxTime() + "`").setEphemeral(true).queue();
            return;
        }

        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (handler == null)
            handler = bot.getPlayerManager().setUpHandler(event.getGuild());
        int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), state.query, track))) + 1;
        LOG.info("Selected slash search result added in guild {} ({}); query='{}'; position={}; track={}",
                event.getGuild().getName(), event.getGuild().getId(), state.query, pos, describeTrack(track));
        searchMenus.remove(componentId);
        event.editMessage(FormatUtil.filter(bot.getConfig().getSuccess() + " Added **" + track.getInfo().title
                + "** (`" + TimeUtil.formatTime(track.getDuration()) + "`) " + (pos == 0 ? "to begin playing" : " to the queue at position " + pos)))
                .setComponents(Collections.emptyList())
                .queue();
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

    private boolean isListeningInVoice(StringSelectInteractionEvent event)
    {
        Member member = event.getMember();
        if (member == null || member.getVoiceState() == null || !member.getVoiceState().inAudioChannel())
            return false;

        AudioChannel current = event.getGuild().getSelfMember().getVoiceState().getChannel();
        return current == null || current.equals(member.getVoiceState().getChannel());
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
            try
            {
                LOG.info("Opening audio connection for slash command /{} in guild {} ({}) to channel {} ({})",
                        event.getName(), guild.getName(), guild.getId(), target.getName(), target.getId());
                guild.getAudioManager().openAudioConnection(target);
            }
            catch (PermissionException ex)
            {
                LOG.warn("Failed to open audio connection for slash command /{} in guild {} ({}) to channel {} ({})",
                        event.getName(), guild.getName(), guild.getId(), target.getName(), target.getId(), ex);
                event.reply(bot.getConfig().getError() + " I am unable to connect to " + target.getAsMention() + "!").setEphemeral(true).queue();
                return false;
            }
            catch (RuntimeException ex)
            {
                LOG.warn("Unexpected failure while opening audio connection for slash command /{} in guild {} ({}) to channel {} ({})",
                        event.getName(), guild.getName(), guild.getId(), target.getName(), target.getId(), ex);
                event.reply(bot.getConfig().getError() + " I could not connect to " + target.getAsMention() + ". Check the console logs for details.").setEphemeral(true).queue();
                return false;
            }
        }
        else
        {
            LOG.debug("Slash command /{} is already connected to requested voice channel {} ({}) in guild {} ({})",
                    event.getName(), current.getName(), current.getId(), guild.getName(), guild.getId());
        }
        return true;
    }

    // ========================
    // General Commands
    // ========================

    private void handleAbout(SlashCommandInteractionEvent event)
    {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(event.getGuild().getSelfMember().getColor())
                .setTitle(event.getJDA().getSelfUser().getName())
                .setDescription("A self-hosted music bot for Discord.")
                .addField("Version", OtherUtil.getCurrentVersion(), true)
                .addField("Servers", String.valueOf(event.getJDA().getGuilds().size()), true)
                .addField("Prefix", "`" + bot.getConfig().getPrefix() + "`", true);
        event.replyEmbeds(eb.build()).queue();
    }

    private void handlePing(SlashCommandInteractionEvent event)
    {
        long gatewayPing = event.getJDA().getGatewayPing();
        event.reply("Pong! Gateway: `" + gatewayPing + "ms`").queue(hook ->
                event.getJDA().getRestPing().queue(
                        restPing -> hook.editOriginal("Pong! Gateway: `" + gatewayPing + "ms`, REST: `" + restPing + "ms`").queue(),
                        err -> LOG.warn("Failed to retrieve REST ping", err)
                ));
    }

    private void handleHelp(SlashCommandInteractionEvent event)
    {
        String prefix = getPrefixForHelp(event);
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(event.getGuild().getSelfMember().getColor())
                .setTitle(event.getJDA().getSelfUser().getName() + " Commands")
                .setDescription("Use either the prefix command or the slash command shown. Owner-only maintenance commands remain prefix-only.");

        addHelpFields(eb, "General", new String[][]{
                {"help", "help", "Show this command list"},
                {"about", "about", "Show bot information"},
                {"ping", "ping", "Check bot latency"},
                {"settings", "settings", "Show bot settings"}
        }, prefix);
        addHelpFields(eb, "Music", new String[][]{
                {"play <title|URL>", "play query:<title|URL>", "Play a song or URL"},
                {"play playlist <name>", "playplaylist name:<name>", "Play a saved playlist"},
                {"playtop <title|URL>", "playtop query:<title|URL>", "Add a song to the top of the queue"},
                {"playlists", "playlists", "List saved playlists"},
                {"nowplaying", "nowplaying", "Show the current song"},
                {"queue [page]", "queue [page]", "Show the queue"},
                {"search <query>", "search query:<query>", "Search YouTube and choose a result"},
                {"scsearch <query>", "scsearch query:<query>", "Search SoundCloud and choose a result"},
                {"skip", "skip", "Vote to skip"},
                {"remove <position|all>", "remove position:<position|all>", "Remove queued songs"},
                {"shuffle", "shuffle", "Shuffle your queued songs"},
                {"seek <time>", "seek time:<time>", "Seek the current song"},
                {"lyrics [song]", "lyrics [query]", "Fetch lyrics"},
                {"correctlyrics <genius-url> | <song>", "correctlyrics url:<genius-url> query:<song>", "Correct cached lyrics for a song"}
        }, prefix);
        addHelpFields(eb, "DJ", new String[][]{
                {"forceskip", "forceskip", "Force skip"},
                {"pause", "pause", "Pause or resume playback"},
                {"pause", "resume", "Resume only if paused"},
                {"stop", "stop", "Stop playback and clear the queue"},
                {"volume [0-150]", "volume [level]", "Show or set volume"},
                {"repeat [off|all|single]", "repeat [mode]", "Set repeat mode"},
                {"loop [off|all|single]", "loop [mode]", "Alias for repeat"},
                {"skipto <position>", "skipto position:<position>", "Skip to a queue position"},
                {"movetrack <from> <to>", "move from:<from> to:<to>", "Move a queued track"},
                {"playnext <title|URL>", "playnext query:<title|URL>", "Play a song next"},
                {"forceremove <user>", "forceremove user:<user>", "Remove a user's queued songs"}
        }, prefix);
        addHelpFields(eb, "Admin", new String[][]{
                {"prefix <prefix|none>", "prefix prefix:<prefix|none>", "Set server prefix"},
                {"setdj <role|none>", "setdj [role]", "Set DJ role"},
                {"settc <channel|none>", "settc [channel]", "Restrict music text channel"},
                {"setvc <channel|none>", "setvc [channel]", "Restrict music voice channel"},
                {"setskip <0-100>", "setskip percent:<0-100>", "Set skip percentage"},
                {"", "skipratio [ratio]", "Show or set skip ratio"},
                {"queuetype [fair|linear]", "queuetype [type]", "Show or set queue type"}
        }, prefix);

        event.replyEmbeds(eb.build()).setEphemeral(true).queue();
    }

    private String getPrefixForHelp(SlashCommandInteractionEvent event)
    {
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        String prefix = settings.getPrefix() == null ? bot.getConfig().getPrefix() : settings.getPrefix();
        if ("@mention".equalsIgnoreCase(prefix))
            return "@" + event.getJDA().getSelfUser().getName() + " ";
        return prefix;
    }

    private void addHelpFields(EmbedBuilder eb, String category, String[][] commands, String prefix)
    {
        StringBuilder sb = new StringBuilder();
        int part = 1;
        for (String[] command : commands)
        {
            String line = (command[0].isEmpty() ? "" : "`" + prefix + command[0] + "` | ")
                    + "`/" + command[1] + "` - " + command[2] + "\n";
            if (sb.length() + line.length() > 1000)
            {
                eb.addField(part == 1 ? category : category + " " + part, sb.toString(), false);
                sb.setLength(0);
                part++;
            }
            sb.append(line);
        }
        if (sb.length() > 0)
            eb.addField(part == 1 ? category : category + " " + part, sb.toString(), false);
    }

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
        LOG.info("Loading slash /{} request in guild {} ({}); query='{}'",
                event.getName(), event.getGuild().getName(), event.getGuild().getId(), query);
        event.deferReply().queue(hook -> {
            bot.getPlayerManager().loadItemOrdered(event.getGuild(), query, new PlayResultHandler(hook, event, query, playTop, false));
        });
    }

    private void handlePlayPlaylist(SlashCommandInteractionEvent event)
    {
        if (!checkVoiceState(event, false)) return;
        if (!connectToVoiceChannel(event)) return;

        String name = event.getOption("name").getAsString();
        Playlist playlist = bot.getPlaylistLoader().getPlaylist(name);
        if (playlist == null && name.contains(" "))
        {
            name = name.replaceAll("\\s+", "_");
            playlist = bot.getPlaylistLoader().getPlaylist(name);
        }
        if (playlist == null)
        {
            event.reply(bot.getConfig().getError() + " I could not find `" + name + ".txt` in the Playlists folder.").setEphemeral(true).queue();
            return;
        }
        if (playlist.getItems().isEmpty())
        {
            event.reply(bot.getConfig().getWarning() + " Playlist `" + playlist.getName() + "` has no entries.").setEphemeral(true).queue();
            return;
        }

        Playlist selectedPlaylist = playlist;
        bot.getPlayerManager().setUpHandler(event.getGuild());
        event.deferReply().queue(hook -> {
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            int[] loaded = {0};
            selectedPlaylist.loadTracks(bot.getPlayerManager(),
                    track -> {
                        handler.addTrack(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), "playlist:" + selectedPlaylist.getName(), track)));
                        loaded[0]++;
                    },
                    () -> hook.editOriginal(bot.getConfig().getSuccess() + " Loaded playlist **" + selectedPlaylist.getName()
                            + "** with `" + loaded[0] + "` tracks"
                            + (selectedPlaylist.getErrors().isEmpty() ? "." : " (`" + selectedPlaylist.getErrors().size() + "` entries failed).")).queue());
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

    private void handleLyrics(SlashCommandInteractionEvent event)
    {
        LyricsService service = getLyricsService();
        if (service == null)
        {
            event.reply(bot.getConfig().getError() + " Lyrics service failed to initialize.").setEphemeral(true).queue();
            return;
        }

        String query;
        if (event.getOption("query") != null)
        {
            query = event.getOption("query").getAsString();
        }
        else
        {
            AudioHandler sendingHandler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            if (sendingHandler == null || !sendingHandler.isMusicPlaying(event.getJDA()))
            {
                event.reply(bot.getConfig().getError() + " There must be music playing, or you must provide a song name.").setEphemeral(true).queue();
                return;
            }
            query = sendingHandler.getPlayer().getPlayingTrack().getInfo().title;
        }

        String usedQuery = query;
        event.deferReply().queue(hook -> CompletableFuture
                .supplyAsync(() -> fetchLyrics(service, usedQuery))
                .thenAccept(opt -> {
                    if (opt.isEmpty())
                    {
                        hook.editOriginal(bot.getConfig().getError() + " Lyrics for `" + usedQuery + "` could not be found.").queue();
                        return;
                    }
                    sendLyricsResult(event, hook, opt.get());
                }));
    }

    private void handleCorrectLyrics(SlashCommandInteractionEvent event)
    {
        LyricsService service = getLyricsService();
        if (service == null)
        {
            event.reply(bot.getConfig().getError() + " Lyrics service unavailable.").setEphemeral(true).queue();
            return;
        }

        String url = event.getOption("url").getAsString().trim();
        String query = event.getOption("query").getAsString().trim();
        if (!url.startsWith("http"))
            url = "https://" + url;
        if (!InputValidator.isValidGeniusUrl(url))
        {
            event.reply(bot.getConfig().getError() + " That doesn't look like a valid Genius lyrics URL.").setEphemeral(true).queue();
            return;
        }
        if (InputValidator.sanitizeQuery(query) == null)
        {
            event.reply(bot.getConfig().getError() + " That song name is not valid.").setEphemeral(true).queue();
            return;
        }

        String finalUrl = url;
        String finalQuery = query;
        event.deferReply().queue(hook -> CompletableFuture
                .supplyAsync(() -> replaceLyrics(service, finalUrl, finalQuery))
                .thenAccept(opt -> {
                    if (opt.isEmpty())
                    {
                        hook.editOriginal(bot.getConfig().getError() + " Could not correct using that URL.").queue();
                        return;
                    }
                    hook.editOriginal(bot.getConfig().getSuccess() + " Updated lyrics cache for `" + finalQuery + "` to use URL: " + opt.get().sourceUrl()).queue();
                }));
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

    private void handleSearch(SlashCommandInteractionEvent event, String searchPrefix, String provider)
    {
        if (!checkVoiceState(event, false)) return;
        if (!connectToVoiceChannel(event)) return;

        String query = event.getOption("query").getAsString();
        bot.getPlayerManager().setUpHandler(event.getGuild());
        LOG.info("Loading slash /{} search in guild {} ({}); provider={}; query='{}'",
                event.getName(), event.getGuild().getName(), event.getGuild().getId(), provider, query);
        event.deferReply().queue(hook ->
                bot.getPlayerManager().loadItemOrdered(event.getGuild(), searchPrefix + query, new SearchResultHandler(hook, event, query, provider)));
    }

    private LyricsService getLyricsService()
    {
        if (lyricsService == null)
        {
            synchronized (this)
            {
                if (lyricsService == null)
                {
                    try
                    {
                        lyricsService = new LyricsService(Path.of("lyrics-cache.db"));
                    }
                    catch (Exception ex)
                    {
                        LOG.warn("Failed to initialize lyrics service", ex);
                    }
                }
            }
        }
        return lyricsService;
    }

    private Optional<LyricsCache.CachedLyrics> fetchLyrics(LyricsService service, String query)
    {
        try
        {
            return service.fetchAndCache(query, true);
        }
        catch (Exception ex)
        {
            LOG.warn("Failed to fetch lyrics for {}", query, ex);
            return Optional.empty();
        }
    }

    private Optional<LyricsCache.CachedLyrics> replaceLyrics(LyricsService service, String url, String query)
    {
        try
        {
            return service.replaceForQueryWithGeniusUrl(url, query);
        }
        catch (Exception ex)
        {
            LOG.warn("Failed to replace lyrics for {} with {}", query, url, ex);
            return Optional.empty();
        }
    }

    private void sendLyricsResult(SlashCommandInteractionEvent event, InteractionHook hook, LyricsCache.CachedLyrics lyrics)
    {
        String title = (lyrics.artist() == null || lyrics.artist().isBlank() ? "" : lyrics.artist() + " - ") + lyrics.title();
        String content = lyrics.lyrics();
        if (content.length() > 15000)
        {
            hook.editOriginal(bot.getConfig().getWarning() + " Lyrics found but seem unusually long: " + lyrics.sourceUrl()).queue();
            return;
        }

        List<String> chunks = splitLyrics(content);
        if (chunks.isEmpty())
        {
            hook.editOriginal(bot.getConfig().getError() + " Lyrics result was empty.").queue();
            return;
        }

        hook.editOriginalEmbeds(newLyricsEmbed(event, title, lyrics.sourceUrl(), chunks.get(0)).build()).queue();
        for (int i = 1; i < chunks.size(); i++)
            event.getChannel().sendMessageEmbeds(newLyricsEmbed(event, null, null, chunks.get(i)).build()).queue();
    }

    private EmbedBuilder newLyricsEmbed(SlashCommandInteractionEvent event, String title, String url, String description)
    {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(event.getGuild().getSelfMember().getColor())
                .setDescription(description);
        if (title != null)
            eb.setTitle(title, url);
        return eb;
    }

    private List<String> splitLyrics(String lyrics)
    {
        List<String> chunks = new ArrayList<>();
        String remaining = lyrics.trim();
        while (!remaining.isEmpty())
        {
            if (remaining.length() <= 2000)
            {
                chunks.add(remaining);
                break;
            }

            int index = remaining.lastIndexOf("\n\n", 2000);
            if (index == -1) index = remaining.lastIndexOf("\n", 2000);
            if (index == -1) index = remaining.lastIndexOf(" ", 2000);
            if (index == -1) index = 2000;
            chunks.add(remaining.substring(0, index).trim());
            remaining = remaining.substring(index).trim();
        }
        return chunks;
    }

    private void cleanupSearchMenus()
    {
        searchMenus.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    // ========================
    // DJ Commands
    // ========================

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

    private void handleResume(SlashCommandInteractionEvent event)
    {
        if (!checkDJPermission(event))
        {
            event.reply(bot.getConfig().getError() + " You need DJ permissions to use this command.").setEphemeral(true).queue();
            return;
        }
        if (!checkVoiceState(event, true)) return;

        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (!handler.getPlayer().isPaused())
        {
            event.reply(bot.getConfig().getWarning() + " The player is not paused.").setEphemeral(true).queue();
            return;
        }

        handler.getPlayer().setPaused(false);
        event.reply(bot.getConfig().getSuccess() + " Resumed **" + handler.getPlayer().getPlayingTrack().getInfo().title + "**").queue();
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

    // ========================
    // Audio Load Result Handlers
    // ========================

    private static class SearchMenuState
    {
        private final long userId;
        private final long guildId;
        private final String query;
        private final List<AudioTrack> tracks;
        private final long createdAt;

        SearchMenuState(long userId, long guildId, String query, List<AudioTrack> tracks)
        {
            this.userId = userId;
            this.guildId = guildId;
            this.query = query;
            this.tracks = tracks;
            this.createdAt = System.currentTimeMillis();
        }

        private boolean isExpired()
        {
            return System.currentTimeMillis() - createdAt > SEARCH_MENU_EXPIRATION_MS;
        }
    }

    private String describeTrack(AudioTrack track)
    {
        if (track == null)
            return "none";

        String source = track.getSourceManager() == null ? "unknown" : track.getSourceManager().getSourceName();
        return "'" + track.getInfo().title + "' by '" + track.getInfo().author + "'"
                + " [id=" + track.getIdentifier()
                + ", source=" + source
                + ", duration=" + TimeUtil.formatTime(track.getDuration()) + "]";
    }

    private class SearchResultHandler implements AudioLoadResultHandler
    {
        private final InteractionHook hook;
        private final SlashCommandInteractionEvent event;
        private final String query;
        private final String provider;

        SearchResultHandler(InteractionHook hook, SlashCommandInteractionEvent event, String query, String provider)
        {
            this.hook = hook;
            this.event = event;
            this.query = query;
            this.provider = provider;
        }

        @Override
        public void trackLoaded(AudioTrack track)
        {
            if (bot.getConfig().isTooLong(track))
            {
                LOG.warn("Rejected slash search track in guild {} ({}): track too long; provider={}; query='{}'; track={}",
                        event.getGuild().getName(), event.getGuild().getId(), provider, query, describeTrack(track));
                hook.editOriginal(bot.getConfig().getWarning() + " This track (**" + track.getInfo().title + "**) is longer than the allowed maximum: `"
                        + TimeUtil.formatTime(track.getDuration()) + "` > `" + bot.getConfig().getMaxTime() + "`").queue();
                return;
            }
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), query, track))) + 1;
            LOG.info("Slash search track loaded in guild {} ({}); provider={}; query='{}'; position={}; track={}",
                    event.getGuild().getName(), event.getGuild().getId(), provider, query, pos, describeTrack(track));
            hook.editOriginal(FormatUtil.filter(bot.getConfig().getSuccess() + " Added **" + track.getInfo().title
                    + "** (`" + TimeUtil.formatTime(track.getDuration()) + "`) " + (pos == 0 ? "to begin playing" : " to the queue at position " + pos))).queue();
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            if (playlist.getTracks().isEmpty())
            {
                LOG.info("Slash search returned empty playlist in guild {} ({}); provider={}; query='{}'",
                        event.getGuild().getName(), event.getGuild().getId(), provider, query);
                noMatches();
                return;
            }

            LOG.info("Slash search returned {} results in guild {} ({}); provider={}; query='{}'",
                    playlist.getTracks().size(), event.getGuild().getName(), event.getGuild().getId(), provider, query);

            cleanupSearchMenus();
            List<AudioTrack> results = new ArrayList<>();
            List<SelectOption> options = new ArrayList<>();
            int limit = Math.min(MAX_SEARCH_RESULTS, playlist.getTracks().size());
            for (int i = 0; i < limit; i++)
            {
                AudioTrack track = playlist.getTracks().get(i);
                results.add(track);
                String label = truncate((i + 1) + ". " + track.getInfo().title, MAX_AUTOCOMPLETE_LENGTH);
                String duration = TimeUtil.formatTime(track.getDuration());
                String author = track.getInfo().author == null || track.getInfo().author.isBlank() ? provider : track.getInfo().author;
                options.add(SelectOption.of(label, String.valueOf(i))
                        .withDescription(truncate(duration + " | " + author, MAX_AUTOCOMPLETE_LENGTH)));
            }

            String componentId = SEARCH_MENU_PREFIX + searchMenuCounter.incrementAndGet();
            searchMenus.put(componentId, new SearchMenuState(event.getUser().getIdLong(), event.getGuild().getIdLong(), query, results));
            StringSelectMenu menu = StringSelectMenu.create(componentId)
                    .setPlaceholder("Choose a " + provider + " result")
                    .addOptions(options)
                    .build();

            StringBuilder sb = new StringBuilder(bot.getConfig().getSuccess())
                    .append(" Search results for `").append(query).append("`:\n");
            for (int i = 0; i < results.size(); i++)
            {
                AudioTrack track = results.get(i);
                sb.append("`").append(i + 1).append(".` [`")
                        .append(TimeUtil.formatTime(track.getDuration())).append("`] **")
                        .append(FormatUtil.filter(track.getInfo().title)).append("**\n");
            }
            hook.editOriginal(sb.toString())
                    .setComponents(ActionRow.of(menu))
                    .queue();
        }

        @Override
        public void noMatches()
        {
            LOG.info("No slash search results in guild {} ({}); provider={}; query='{}'",
                    event.getGuild().getName(), event.getGuild().getId(), provider, query);
            hook.editOriginal(FormatUtil.filter(bot.getConfig().getWarning() + " No " + provider + " results found for `" + query + "`.")).queue();
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            LOG.warn("Slash search load failed in guild {} ({}); provider={}; query='{}'; severity={}; message={}",
                    event.getGuild().getName(), event.getGuild().getId(), provider, query, throwable.severity, throwable.getMessage(), throwable);
            if (throwable.severity == Severity.COMMON)
                hook.editOriginal(bot.getConfig().getError() + " Error loading: " + throwable.getMessage()).queue();
            else
                hook.editOriginal(bot.getConfig().getError() + " Error loading search results.").queue();
        }
    }

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
                LOG.warn("Rejected slash /{} track in guild {} ({}): track too long; query='{}'; track={}",
                        event.getName(), event.getGuild().getName(), event.getGuild().getId(), args, describeTrack(track));
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
            LOG.info("Slash /{} track loaded in guild {} ({}); playTop={}; query='{}'; position={}; track={}",
                    event.getName(), event.getGuild().getName(), event.getGuild().getId(), playTop, args, pos, describeTrack(track));
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
            LOG.info("Slash /{} playlist loaded in guild {} ({}); query='{}'; playlist='{}'; acceptedTracks={}; sourceTracks={}",
                    event.getName(), event.getGuild().getName(), event.getGuild().getId(), args,
                    playlist.getName(), count[0], playlist.getTracks().size());
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
                {
                    LOG.info("Slash /{} playlist result was empty in guild {} ({}); query='{}'",
                            event.getName(), event.getGuild().getName(), event.getGuild().getId(), args);
                    hook.editOriginal(bot.getConfig().getWarning() + " The playlist could not be loaded or contained 0 entries.").queue();
                }
                else if (count == 0)
                {
                    LOG.warn("Slash /{} playlist had no acceptable tracks in guild {} ({}); query='{}'; sourceTracks={}",
                            event.getName(), event.getGuild().getName(), event.getGuild().getId(), args, playlist.getTracks().size());
                    hook.editOriginal(bot.getConfig().getWarning() + " All entries in this playlist were too long.").queue();
                }
                else
                    hook.editOriginal(bot.getConfig().getSuccess() + " Loaded playlist **" + (playlist.getName() != null ? playlist.getName() : "Unknown") + "** with `" + count + "` entries!").queue();
            }
        }

        @Override
        public void noMatches()
        {
            if (ytsearch)
            {
                LOG.info("Slash /{} found no matches after YouTube fallback in guild {} ({}); query='{}'",
                        event.getName(), event.getGuild().getName(), event.getGuild().getId(), args);
                hook.editOriginal(bot.getConfig().getWarning() + " No results found for `" + args + "`.").queue();
            }
            else
            {
                LOG.info("Slash /{} found no direct matches in guild {} ({}); retrying as YouTube search; query='{}'",
                        event.getName(), event.getGuild().getName(), event.getGuild().getId(), args);
                bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:" + args, new PlayResultHandler(hook, event, args, playTop, true));
            }
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            LOG.warn("Slash /{} load failed in guild {} ({}); query='{}'; severity={}; message={}",
                    event.getName(), event.getGuild().getName(), event.getGuild().getId(), args, throwable.severity, throwable.getMessage(), throwable);
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
                LOG.warn("Rejected slash /playnext track in guild {} ({}): track too long; query='{}'; track={}",
                        event.getGuild().getName(), event.getGuild().getId(), args, describeTrack(track));
                hook.editOriginal(bot.getConfig().getWarning() + " This track is longer than the allowed maximum.").queue();
                return;
            }
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrackToFront(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), args, track))) + 1;
            LOG.info("Slash /playnext track loaded in guild {} ({}); query='{}'; position={}; track={}",
                    event.getGuild().getName(), event.getGuild().getId(), args, pos, describeTrack(track));
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
            LOG.info("Slash /playnext found no direct matches in guild {} ({}); retrying as YouTube search; query='{}'",
                    event.getGuild().getName(), event.getGuild().getId(), args);
            bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:" + args, new AudioLoadResultHandler()
            {
                @Override public void trackLoaded(AudioTrack track) { PlayNextResultHandler.this.trackLoaded(track); }
                @Override public void playlistLoaded(AudioPlaylist playlist) { PlayNextResultHandler.this.playlistLoaded(playlist); }
                @Override public void noMatches()
                {
                    LOG.info("Slash /playnext found no matches after YouTube fallback in guild {} ({}); query='{}'",
                            event.getGuild().getName(), event.getGuild().getId(), args);
                    hook.editOriginal(bot.getConfig().getWarning() + " No results found for `" + args + "`.").queue();
                }
                @Override public void loadFailed(FriendlyException throwable)
                {
                    LOG.warn("Slash /playnext YouTube fallback failed in guild {} ({}); query='{}'; severity={}; message={}",
                            event.getGuild().getName(), event.getGuild().getId(), args, throwable.severity, throwable.getMessage(), throwable);
                    hook.editOriginal(bot.getConfig().getError() + " Error loading track.").queue();
                }
            });
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            LOG.warn("Slash /playnext load failed in guild {} ({}); query='{}'; severity={}; message={}",
                    event.getGuild().getName(), event.getGuild().getId(), args, throwable.severity, throwable.getMessage(), throwable);
            hook.editOriginal(bot.getConfig().getError() + " Error loading: " + throwable.getMessage()).queue();
        }
    }
}
