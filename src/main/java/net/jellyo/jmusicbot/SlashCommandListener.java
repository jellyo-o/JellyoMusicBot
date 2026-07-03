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
import com.jagrosh.jmusicbot.audio.filter.AudioFilterPreset;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.commands.CommandChecks;
import com.jagrosh.jmusicbot.commands.SlashCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.commands.admin.PrefixCmd;
import com.jagrosh.jmusicbot.commands.admin.QueueTypeCmd;
import com.jagrosh.jmusicbot.commands.admin.SkipratioCmd;
import com.jagrosh.jmusicbot.commands.dj.AutoplayCmd;
import com.jagrosh.jmusicbot.commands.dj.AvoidCmd;
import com.jagrosh.jmusicbot.commands.dj.ForceskipCmd;
import com.jagrosh.jmusicbot.commands.dj.UnavoidCmd;
import com.jagrosh.jmusicbot.commands.dj.FilterCmd;
import com.jagrosh.jmusicbot.commands.dj.MoveTrackCmd;
import com.jagrosh.jmusicbot.commands.dj.RepeatCmd;
import com.jagrosh.jmusicbot.commands.dj.SkiptoCmd;
import com.jagrosh.jmusicbot.commands.dj.SleepCmd;
import com.jagrosh.jmusicbot.commands.dj.StopCmd;
import com.jagrosh.jmusicbot.commands.dj.VolumeCmd;
import com.jagrosh.jmusicbot.commands.economy.AchievementsCmd;
import com.jagrosh.jmusicbot.commands.economy.BalanceCmd;
import com.jagrosh.jmusicbot.commands.economy.BlackjackCmd;
import com.jagrosh.jmusicbot.commands.economy.CrashCmd;
import com.jagrosh.jmusicbot.commands.economy.DailyCmd;
import com.jagrosh.jmusicbot.commands.economy.DoubleCmd;
import com.jagrosh.jmusicbot.commands.economy.DuelCmd;
import com.jagrosh.jmusicbot.commands.economy.GambleCmd;
import com.jagrosh.jmusicbot.commands.economy.HiLoCmd;
import com.jagrosh.jmusicbot.commands.economy.KenoCmd;
import com.jagrosh.jmusicbot.commands.economy.LeaderboardCmd;
import com.jagrosh.jmusicbot.commands.economy.LotteryCmd;
import com.jagrosh.jmusicbot.commands.economy.MinesCmd;
import com.jagrosh.jmusicbot.commands.economy.PredictCmd;
import com.jagrosh.jmusicbot.commands.economy.RouletteCmd;
import com.jagrosh.jmusicbot.commands.economy.RpsCmd;
import com.jagrosh.jmusicbot.commands.economy.ScratchCmd;
import com.jagrosh.jmusicbot.commands.economy.StatsCmd;
import com.jagrosh.jmusicbot.commands.economy.TriviaCmd;
import com.jagrosh.jmusicbot.commands.economy.WheelCmd;
import com.jagrosh.jmusicbot.commands.economy.WorkCmd;
import com.jagrosh.jmusicbot.lyrics.InputValidator;
import com.jagrosh.jmusicbot.lyrics.LyricsCache;
import com.jagrosh.jmusicbot.lyrics.LyricsService;
import com.jagrosh.jmusicbot.guessmusic.GuessMusicService;
import com.jagrosh.jmusicbot.commands.music.AvoidedCmd;
import com.jagrosh.jmusicbot.commands.music.HistoryCmd;
import com.jagrosh.jmusicbot.commands.music.RestoreCmd;
import com.jagrosh.jmusicbot.commands.music.PlaylistViewPaginator;
import com.jagrosh.jmusicbot.commands.music.QueueCmd;
import com.jagrosh.jmusicbot.commands.music.RemoveCmd;
import com.jagrosh.jmusicbot.commands.music.SeekCmd;
import com.jagrosh.jmusicbot.commands.music.ShuffleCmd;
import com.jagrosh.jmusicbot.commands.music.SkipCmd;
import com.jagrosh.jmusicbot.playlist.PlaylistTrack;
import com.jagrosh.jmusicbot.playlist.PlaylistTrackLoader;
import com.jagrosh.jmusicbot.playlist.SpotifyPlaylistFallback;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.AddResult;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.PlaylistException;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.PlaylistSummary;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.Share;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.ShareMode;
import com.jagrosh.jmusicbot.settings.AutoplayMode;
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
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
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
    private final SkipCmd skipCmd;
    private final RemoveCmd removeCmd;
    private final ShuffleCmd shuffleCmd;
    private final SeekCmd seekCmd;
    private final ForceskipCmd forceskipCmd;
    private final FilterCmd filterCmd;
    private final StopCmd stopCmd;
    private final VolumeCmd volumeCmd;
    private final RepeatCmd repeatCmd;
    private final AutoplayCmd autoplayCmd;
    private final SkiptoCmd skiptoCmd;
    private final MoveTrackCmd moveTrackCmd;
    private final HistoryCmd historyCmd;
    private final QueueCmd queueCmd;
    private final PrefixCmd prefixCmd;
    private final SkipratioCmd skipratioCmd;
    private final QueueTypeCmd queueTypeCmd;
    private final com.jagrosh.jmusicbot.commands.admin.PreloadLyricsCmd preloadLyricsCmd;
    private final com.jagrosh.jmusicbot.commands.admin.AutoLyricsCmd autoLyricsCmd;
    private final StatsCmd statsCmd;
    private final BalanceCmd balanceCmd;
    private final DailyCmd dailyCmd;
    private final GambleCmd gambleCmd;
    private final PredictCmd predictCmd;
    private final RouletteCmd rouletteCmd;
    private final WheelCmd wheelCmd;
    private final KenoCmd kenoCmd;
    private final ScratchCmd scratchCmd;
    private final DoubleCmd doubleCmd;
    private final RpsCmd rpsCmd;
    private final HiLoCmd hiloCmd;
    private final CrashCmd crashCmd;
    private final MinesCmd minesCmd;
    private final BlackjackCmd blackjackCmd;
    private final WorkCmd workCmd;
    private final TriviaCmd triviaCmd;
    private final DuelCmd duelCmd;
    private final LotteryCmd lotteryCmd;
    private final LeaderboardCmd leaderboardCmd;
    private final AchievementsCmd achievementsCmd;
    private final AvoidCmd avoidCmd;
    private final UnavoidCmd unavoidCmd;
    private final AvoidedCmd avoidedCmd;
    private final SleepCmd sleepCmd;
    private final RestoreCmd restoreCmd;

    public SlashCommandListener(Bot bot)
    {
        this.bot = bot;
        this.skipCmd = new SkipCmd(bot);
        this.removeCmd = new RemoveCmd(bot);
        this.shuffleCmd = new ShuffleCmd(bot);
        this.seekCmd = new SeekCmd(bot);
        this.forceskipCmd = new ForceskipCmd(bot);
        this.filterCmd = new FilterCmd(bot);
        this.stopCmd = new StopCmd(bot);
        this.volumeCmd = new VolumeCmd(bot);
        this.repeatCmd = new RepeatCmd(bot);
        this.autoplayCmd = new AutoplayCmd(bot);
        this.skiptoCmd = new SkiptoCmd(bot);
        this.moveTrackCmd = new MoveTrackCmd(bot);
        this.historyCmd = new HistoryCmd(bot);
        this.queueCmd = new QueueCmd(bot);
        this.prefixCmd = new PrefixCmd(bot);
        this.skipratioCmd = new SkipratioCmd(bot);
        this.queueTypeCmd = new QueueTypeCmd(bot);
        this.preloadLyricsCmd = new com.jagrosh.jmusicbot.commands.admin.PreloadLyricsCmd(bot);
        this.autoLyricsCmd = new com.jagrosh.jmusicbot.commands.admin.AutoLyricsCmd(bot);
        this.statsCmd = new StatsCmd(bot);
        this.balanceCmd = new BalanceCmd(bot);
        this.dailyCmd = new DailyCmd(bot);
        this.gambleCmd = new GambleCmd(bot);
        this.predictCmd = new PredictCmd(bot);
        this.rouletteCmd = new RouletteCmd(bot);
        this.wheelCmd = new WheelCmd(bot);
        this.kenoCmd = new KenoCmd(bot);
        this.scratchCmd = new ScratchCmd(bot);
        this.doubleCmd = new DoubleCmd(bot);
        this.rpsCmd = new RpsCmd(bot);
        this.hiloCmd = new HiLoCmd(bot);
        this.crashCmd = new CrashCmd(bot);
        this.minesCmd = new MinesCmd(bot);
        this.blackjackCmd = new BlackjackCmd(bot);
        this.workCmd = new WorkCmd(bot);
        this.triviaCmd = new TriviaCmd(bot);
        this.duelCmd = new DuelCmd(bot);
        this.lotteryCmd = new LotteryCmd(bot);
        this.leaderboardCmd = new LeaderboardCmd(bot);
        this.achievementsCmd = new AchievementsCmd(bot);
        this.avoidCmd = new AvoidCmd(bot);
        this.unavoidCmd = new UnavoidCmd(bot);
        this.avoidedCmd = new AvoidedCmd(bot);
        this.sleepCmd = new SleepCmd(bot);
        this.restoreCmd = new RestoreCmd(bot);
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

        LOG.info("Registering slash commands per guild to avoid Discord's global command overwrite rate limit");
        registerGuildSlashCommands(jda, commands);
    }

    private void registerGuildSlashCommands(JDA jda, List<SlashCommandData> desiredCommands)
    {
        jda.getGuilds().forEach(guild -> guild.retrieveCommands().queue(
                existing ->
                {
                    if(!commandsNeedUpdate(desiredCommands, existing))
                    {
                        LOG.info("Slash commands are already up to date for guild {} ({})",
                                guild.getName(), guild.getId());
                        return;
                    }

                    LOG.info("Slash commands differ for guild {} ({}); updating {} commands",
                            guild.getName(), guild.getId(), desiredCommands.size());
                    guild.updateCommands().addCommands(desiredCommands).queue(
                            cmds -> LOG.info("Registered {} slash commands for guild {} ({})",
                                    cmds.size(), guild.getName(), guild.getId()),
                            err -> LOG.error("Failed to register slash commands for guild {} ({})",
                                    guild.getName(), guild.getId(), err)
                    );
                },
                err -> LOG.warn("Failed to retrieve slash commands for guild {} ({})",
                        guild.getName(), guild.getId(), err)
        ));
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
        commands.add(slashCommand("about", "Shows information about the bot"));
        commands.add(slashCommand("help", "Shows available commands"));
        commands.add(slashCommand("ping", "Checks the bot latency"));
        commands.add(slashCommand("settings", "Shows the bot settings"));

        // Music commands (anyone can use)
        commands.add(slashCommand("play", "Play a song")
                .addOptions(songQueryOption()));
        commands.add(slashCommand("playtop", "Play a song at the top of the queue")
                .addOptions(songQueryOption()));
        commands.add(slashCommand("playplaylist", "Play one of your playlists")
                .addOptions(playlistNameOption()));
        commands.add(slashCommand("playlist", "Manage your playlists")
                .addSubcommands(
                        new SubcommandData("list", "List your playlists"),
                        new SubcommandData("create", "Create a playlist")
                                .addOptions(new OptionData(OptionType.STRING, "name", "Playlist name", true)),
                        new SubcommandData("rename", "Rename one of your playlists")
                                .addOptions(playlistNameOption(), new OptionData(OptionType.STRING, "new_name", "New playlist name", true)),
                        new SubcommandData("delete", "Delete one of your playlists")
                                .addOptions(playlistNameOption()),
                        new SubcommandData("view", "View playlist songs by index")
                                .addOptions(playlistNameOption()),
                        new SubcommandData("play", "Add a playlist to the queue")
                                .addOptions(playlistNameOption()),
                        new SubcommandData("add", "Add a song or URL to a playlist")
                                .addOptions(playlistNameOption(), songQueryOption()),
                        new SubcommandData("addcurrent", "Add the current song to a playlist")
                                .addOptions(playlistNameOption()),
                        new SubcommandData("addqueue", "Add the current and queued songs to a playlist")
                                .addOptions(playlistNameOption()),
                        new SubcommandData("remove", "Remove a song from a playlist by index")
                                .addOptions(playlistNameOption(), new OptionData(OptionType.INTEGER, "index", "Song index", true)),
                        new SubcommandData("move", "Move a playlist song to another index")
                                .addOptions(playlistNameOption(),
                                        new OptionData(OptionType.INTEGER, "from", "Current index", true),
                                        new OptionData(OptionType.INTEGER, "to", "New index", true)),
                        new SubcommandData("clear", "Remove every song from a playlist")
                                .addOptions(playlistNameOption()),
                        new SubcommandData("share", "Create a share code")
                                .addOptions(playlistNameOption(), shareModeOption()),
                        new SubcommandData("addshared", "Add a shared playlist by code")
                                .addOptions(new OptionData(OptionType.STRING, "code", "Share code", true),
                                        new OptionData(OptionType.STRING, "name", "Name in your library", false)),
                        new SubcommandData("unshare", "Revoke one of your share codes")
                                .addOptions(new OptionData(OptionType.STRING, "code", "Share code", true)),
                        new SubcommandData("unfollow", "Remove a followed playlist from your library")
                                .addOptions(playlistNameOption()),
                        new SubcommandData("copy", "Copy a visible playlist into an editable playlist")
                                .addOptions(playlistNameOption(), new OptionData(OptionType.STRING, "new_name", "New playlist name", true))));
        commands.add(slashCommand("like", "Add music to your Liked Songs")
                .addOptions(new OptionData(OptionType.STRING, "source", "What to like", true)
                                .addChoice("current", "current")
                                .addChoice("queue", "queue")
                                .addChoice("query", "query"),
                        songQueryOption().setRequired(false)));
        commands.add(slashCommand("unlike", "Remove music from your Liked Songs")
                .addOptions(unlikeTargetOption()));
        commands.add(slashCommand("liked", "View or play your Liked Songs")
                .addOptions(new OptionData(OptionType.STRING, "action", "Action", true)
                                .addChoice("view", "view")
                                .addChoice("play", "play")));
        commands.add(slashCommand("nowplaying", "Shows the currently playing song"));
        commands.add(slashCommand("queue", "Shows the current queue"));
        commands.add(slashCommand("history", "Shows the played song history")
                .addSubcommands(
                        new SubcommandData("session", "Shows songs played since the bot joined this voice session"),
                        new SubcommandData("guild", "Shows all songs played on this server")));
        commands.add(slashCommand("skip", "Vote to skip the current song"));
        commands.add(slashCommand("remove", "Remove a song from the queue")
                .addOptions(new OptionData(OptionType.STRING, "position", "Position in queue or 'all'", true)));
        commands.add(slashCommand("shuffle", "Shuffle your songs in the queue"));
        commands.add(slashCommand("seek", "Seek to a position in the current song")
                .addOptions(new OptionData(OptionType.STRING, "time", "Time to seek to (e.g., 1:30, +30, -15)", true)));
        commands.add(slashCommand("lyrics", "Search for lyrics")
                .addOptions(new OptionData(OptionType.STRING, "query", "Song to search lyrics for", false)));
        commands.add(slashCommand("correctlyrics", "Correct cached lyrics for a song")
                .addOptions(
                        new OptionData(OptionType.STRING, "url", "Genius lyrics URL", true),
                        new OptionData(OptionType.STRING, "query", "Song to correct", true)));
        commands.add(slashCommand("guess", "Play or answer a guess the music game")
                .addSubcommands(
                        new SubcommandData("start", "Start a guess the music lobby")
                                .addOptions(guessModeOption(), guessWinOption(), guessInputOption(), guessMatchOption(),
                                        new OptionData(OptionType.INTEGER, "rounds", "Rounds for rounds mode", false).setRequiredRange(1, 50),
                                        new OptionData(OptionType.INTEGER, "points", "Target points for points mode", false).setRequiredRange(1, 200),
                                        new OptionData(OptionType.INTEGER, "seconds", "Clip seconds for custom mode", false).setRequiredRange(1, 60),
                                        guessClipPositionOption(),
                                        new OptionData(OptionType.INTEGER, "timeout", "Seconds before an unanswered round reveals; omitted uses auto", false).setRequiredRange(5, 600),
                                        new OptionData(OptionType.INTEGER, "guesses", "Guesses per user per round; 0 means unlimited", false).setRequiredRange(0, 20),
                                        new OptionData(OptionType.INTEGER, "winners", "Correct guesses before a round ends", false).setRequiredRange(1, 10),
                                        new OptionData(OptionType.INTEGER, "known_percent", "Percent of rounds from known songs", false).setRequiredRange(0, 100),
                                        new OptionData(OptionType.BOOLEAN, "hints", "Progressively replay longer clips until someone guesses", false),
                                        new OptionData(OptionType.INTEGER, "hint_interval", "Seconds between progressive hints", false).setRequiredRange(5, 60),
                                        new OptionData(OptionType.INTEGER, "hint_seconds", "Seconds added by each hint", false).setRequiredRange(1, 30),
                                        new OptionData(OptionType.INTEGER, "hint_replays", "Number of progressive hint replays", false).setRequiredRange(0, 8),
                                        new OptionData(OptionType.INTEGER, "replay_interval", "Seconds between same-length replays after a correct guess", false).setRequiredRange(5, 60),
                                        new OptionData(OptionType.INTEGER, "buffer", "Seconds after the final replay before reveal in auto timeout", false).setRequiredRange(5, 60),
                                        new OptionData(OptionType.STRING, "playlist", "Optional host playlist name", false).setAutoComplete(true),
                                        new OptionData(OptionType.STRING, "artist", "Only use songs by these artists; separate names with commas", false)),
                        new SubcommandData("submit", "Privately guess the current song")
                                .addOptions(new OptionData(OptionType.STRING, "answer", "Song title", true)),
                        new SubcommandData("join", "Join the current guess the music game"),
                        new SubcommandData("leave", "Leave the current guess the music game"),
                        new SubcommandData("status", "Show the current guess the music game"),
                        new SubcommandData("settings", "Show interactive game settings"),
                        new SubcommandData("reveal", "Reveal the current round"),
                        new SubcommandData("stop", "Stop the current guess the music game"),
                        new SubcommandData("hints", "Configure progressive hints")
                                .addOptions(new OptionData(OptionType.BOOLEAN, "enabled", "Enable progressive hints", false),
                                        new OptionData(OptionType.INTEGER, "hint_interval", "Seconds between progressive hints", false).setRequiredRange(5, 60),
                                        new OptionData(OptionType.INTEGER, "hint_seconds", "Seconds added by each hint", false).setRequiredRange(1, 30),
                                        new OptionData(OptionType.INTEGER, "hint_replays", "Number of progressive hint replays", false).setRequiredRange(0, 8)),
                        new SubcommandData("highlight", "Set this round's answer reveal highlight")
                                .addOptions(new OptionData(OptionType.STRING, "timestamp", "Optional timestamp like 1:05; omitted uses current playback", false))));
        commands.add(slashCommand("g", "Fast private guess for guess the music")
                .addOptions(new OptionData(OptionType.STRING, "answer", "Song title", true)));
        commands.add(slashCommand("idk", "Toggle passing the current guess the music round"));
        commands.add(slashCommand("hostgame", "Host a guess the music game where you pick the songs")
                .addSubcommands(
                        new SubcommandData("start", "Start a hosted lobby — you privately pick every song")
                                .addOptions(guessModeOption(), guessWinOption(), guessInputOption(), guessMatchOption(),
                                        guessClipPositionOption(),
                                        new OptionData(OptionType.INTEGER, "seconds", "Clip seconds for custom mode", false).setRequiredRange(1, 60),
                                        new OptionData(OptionType.INTEGER, "timeout", "Seconds before an unanswered round reveals; omitted uses auto", false).setRequiredRange(5, 600),
                                        new OptionData(OptionType.INTEGER, "idle", "Seconds to wait for a new song before the game ends", false).setRequiredRange(15, 1800),
                                        new OptionData(OptionType.INTEGER, "rounds", "Rounds for rounds mode", false).setRequiredRange(1, 50),
                                        new OptionData(OptionType.INTEGER, "points", "Target points for points mode", false).setRequiredRange(1, 200),
                                        new OptionData(OptionType.INTEGER, "guesses", "Guesses per user per round; 0 means unlimited", false).setRequiredRange(0, 20),
                                        new OptionData(OptionType.INTEGER, "winners", "Correct guesses before a round ends", false).setRequiredRange(1, 10),
                                        new OptionData(OptionType.BOOLEAN, "hints", "Progressively replay longer clips until someone guesses", false),
                                        new OptionData(OptionType.STRING, "playlist", "Optional: pre-load songs from one of your playlists (private)", false).setAutoComplete(true)),
                        new SubcommandData("add", "Privately add songs to your hosted game"),
                        new SubcommandData("status", "Show the current hosted game"),
                        new SubcommandData("join", "Join the current hosted game"),
                        new SubcommandData("leave", "Leave the current hosted game"),
                        new SubcommandData("reveal", "Reveal the current round"),
                        new SubcommandData("stop", "Stop the current hosted game")));
        commands.add(slashCommand("playlists", "Shows your playlists"));
        commands.add(slashCommand("search", "Search YouTube and choose a result")
                .addOptions(new OptionData(OptionType.STRING, "query", "Search query", true)));
        commands.add(slashCommand("scsearch", "Search SoundCloud and choose a result")
                .addOptions(new OptionData(OptionType.STRING, "query", "Search query", true)));

        // DJ commands
        commands.add(slashCommand("forceskip", "Force skip the current song"));
        commands.add(slashCommand("pause", "Pause or resume the current song"));
        commands.add(slashCommand("resume", "Resume the current song if paused"));
        commands.add(slashCommand("stop", "Stop playback and clear the queue"));
        commands.add(slashCommand("volume", "Set or show the volume")
                .addOptions(new OptionData(OptionType.INTEGER, "level", "Volume level (0-150)", false)));
        commands.add(slashCommand("filter", "Set or show the audio filter")
                .addOptions(audioFilterPresetOption()));
        commands.add(slashCommand("repeat", "Toggle repeat mode")
                .addOptions(new OptionData(OptionType.STRING, "mode", "Repeat mode", false)
                        .addChoice("off", "off")
                        .addChoice("all", "all")
                        .addChoice("single", "single")));
        commands.add(slashCommand("loop", "Toggle repeat mode")
                .addOptions(new OptionData(OptionType.STRING, "mode", "Repeat mode", false)
                        .addChoice("off", "off")
                        .addChoice("all", "all")
                        .addChoice("single", "single")));
        commands.add(slashCommand("autoplay", "Set automatic radio playback")
                .addOptions(autoplayModeOption()));
        commands.add(slashCommand("radio", "Set automatic radio playback")
                .addOptions(autoplayModeOption()));
        commands.add(slashCommand("skipto", "Skip to a specific position in the queue")
                .addOptions(new OptionData(OptionType.INTEGER, "position", "Position to skip to", true)));
        commands.add(slashCommand("move", "Move a track in the queue")
                .addOptions(new OptionData(OptionType.INTEGER, "from", "Current position", true))
                .addOptions(new OptionData(OptionType.INTEGER, "to", "New position", true)));
        commands.add(slashCommand("playnext", "Play a song next in queue")
                .addOptions(songQueryOption()));
        commands.add(slashCommand("forceremove", "Force remove a user's songs from queue")
                .addOptions(new OptionData(OptionType.USER, "user", "User to remove songs from", true)));

        // Admin commands
        commands.add(slashCommand("prefix", "Set the command prefix")
                .addOptions(new OptionData(OptionType.STRING, "prefix", "New prefix or 'none'", true))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(slashCommand("setdj", "Set the DJ role")
                .addOptions(new OptionData(OptionType.ROLE, "role", "DJ role (leave empty to clear)", false))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(slashCommand("settc", "Set the text channel for music commands")
                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Text channel (leave empty to clear)", false))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(slashCommand("setvc", "Set the voice channel for music")
                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Voice channel (leave empty to clear)", false))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(slashCommand("skipratio", "Set the skip vote ratio")
                .addOptions(new OptionData(OptionType.NUMBER, "ratio", "Skip ratio (0-1)", false))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(slashCommand("setskip", "Set the skip vote percentage")
                .addOptions(new OptionData(OptionType.INTEGER, "percent", "Skip percentage (0-100)", true)
                        .setRequiredRange(0, 100))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(slashCommand("queuetype", "Set the queue type")
                .addOptions(new OptionData(OptionType.STRING, "type", "Queue type", false)
                        .addChoice("fair", "fair")
                        .addChoice("linear", "linear"))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(slashCommand("preloadlyrics", "Toggle preloading lyrics for upcoming songs")
                .addOptions(new OptionData(OptionType.STRING, "state", "on or off", false)
                        .addChoice("on", "on")
                        .addChoice("off", "off"))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
        commands.add(slashCommand("autolyrics", "Toggle auto-posting lyrics when a song starts")
                .addOptions(new OptionData(OptionType.STRING, "state", "on or off", false)
                        .addChoice("on", "on")
                        .addChoice("off", "off"))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));

        // Economy, XP, achievements and games (global per-user)
        commands.add(slashCommand("stats", "Show your global XP, coins and achievements")
                .addOptions(new OptionData(OptionType.USER, "user", "Whose stats to show", false)));
        commands.add(slashCommand("balance", "Show your coin balance")
                .addOptions(new OptionData(OptionType.USER, "user", "Whose balance to show", false)));
        commands.add(slashCommand("daily", "Claim your daily coin chest"));
        commands.add(slashCommand("gamble", "Bet coins on a game of chance")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Amount to bet", true).setRequiredRange(10, 1_000_000),
                        new OptionData(OptionType.STRING, "game", "Which game to play", false)
                                .addChoice("coinflip", "coinflip")
                                .addChoice("dice", "dice")
                                .addChoice("slots", "slots")));
        commands.add(slashCommand("predict", "Predict a dice roll for coins")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Amount to bet", true).setRequiredRange(10, 1_000_000),
                        new OptionData(OptionType.STRING, "prediction", "Your call", true)
                                .addChoice("1", "1").addChoice("2", "2").addChoice("3", "3")
                                .addChoice("4", "4").addChoice("5", "5").addChoice("6", "6")
                                .addChoice("even", "even").addChoice("odd", "odd")
                                .addChoice("high (4-6)", "high").addChoice("low (1-3)", "low")));
        commands.add(slashCommand("roulette", "Spin the roulette wheel")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Amount to bet", true).setRequiredRange(10, 1_000_000),
                        new OptionData(OptionType.STRING, "bet", "What to bet on", true)
                                .addChoice("red", "red").addChoice("black", "black")
                                .addChoice("even", "even").addChoice("odd", "odd")
                                .addChoice("low (1-18)", "low").addChoice("high (19-36)", "high")
                                .addChoice("1st dozen", "dozen1").addChoice("2nd dozen", "dozen2")
                                .addChoice("3rd dozen", "dozen3").addChoice("single number", "number"),
                        new OptionData(OptionType.INTEGER, "number", "The number for a straight-up bet", false)
                                .setRequiredRange(0, 36)));
        commands.add(slashCommand("wheel", "Spin the wheel of fortune")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Amount to bet", true).setRequiredRange(10, 1_000_000)));
        commands.add(slashCommand("keno", "Pick numbers and match the draw")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Amount to bet", true).setRequiredRange(10, 1_000_000),
                        new OptionData(OptionType.STRING, "numbers", "Four numbers 1-40, e.g. \"3 8 21 40\" (omit to quick-pick)", false)));
        commands.add(slashCommand("scratch", "Scratch a card for prizes")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Amount to bet", true).setRequiredRange(10, 1_000_000)));
        commands.add(slashCommand("double", "Flip to double your coins, again and again")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Amount to bet", true).setRequiredRange(10, 1_000_000)));
        commands.add(slashCommand("rps", "Rock paper scissors for coins")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Amount to bet", true).setRequiredRange(10, 1_000_000)));
        commands.add(slashCommand("hilo", "Guess higher or lower to build a streak")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Amount to bet", true).setRequiredRange(10, 1_000_000)));
        commands.add(slashCommand("crash", "Cash out before the rocket crashes")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Amount to bet", true).setRequiredRange(10, 1_000_000),
                        new OptionData(OptionType.NUMBER, "target", "Auto-cash-out multiplier, e.g. 2.5", false)));
        commands.add(slashCommand("mines", "Reveal safe tiles and dodge the mines")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Amount to bet", true).setRequiredRange(10, 1_000_000),
                        new OptionData(OptionType.INTEGER, "bombs", "Number of mines (1-10)", false).setRequiredRange(1, 10)));
        commands.add(slashCommand("blackjack", "Play blackjack against the dealer")
                .addOptions(new OptionData(OptionType.INTEGER, "amount", "Amount to bet", true).setRequiredRange(10, 1_000_000)));
        commands.add(slashCommand("work", "Earn coins with a quick job (short cooldown)"));
        commands.add(slashCommand("trivia", "Answer a trivia question for coins (short cooldown)"));
        commands.add(slashCommand("duel", "Challenge another player to a coinflip duel")
                .addOptions(new OptionData(OptionType.USER, "opponent", "Who to duel", true),
                        new OptionData(OptionType.INTEGER, "amount", "The ante", true).setRequiredRange(10, 1_000_000)));
        commands.add(slashCommand("lottery", "Buy tickets or check the server lottery")
                .addOptions(new OptionData(OptionType.STRING, "action", "Buy tickets or view the pot", false)
                                .addChoice("info", "info").addChoice("buy", "buy"),
                        new OptionData(OptionType.INTEGER, "tickets", "How many tickets to buy", false)
                                .setRequiredRange(1, 100)));
        commands.add(slashCommand("leaderboard", "Show the global leaderboard")
                .addOptions(new OptionData(OptionType.STRING, "metric", "Ranking metric", false)
                        .addChoice("coins", "coins")
                        .addChoice("xp", "xp")
                        .addChoice("time", "time")
                        .addChoice("songs", "songs")
                        .addChoice("wins", "wins")));
        commands.add(slashCommand("achievements", "Show earned and locked achievements")
                .addOptions(new OptionData(OptionType.USER, "user", "Whose achievements to show", false)));

        // Avoid list (per-guild)
        commands.add(slashCommand("avoid", "Block a song from autoplay (skips it if playing)")
                .addOptions(new OptionData(OptionType.STRING, "song", "Song to avoid; leave empty for the current song", false)));
        commands.add(slashCommand("unavoid", "Remove a song from the server's avoid list")
                .addOptions(new OptionData(OptionType.STRING, "song", "Song name to unavoid", true)));
        commands.add(slashCommand("avoided", "Show the songs avoided on this server"));
        commands.add(slashCommand("sleep", "Stop playback after a time or a number of songs")
                .addOptions(new OptionData(OptionType.STRING, "when",
                        "e.g. 30m, 1h30m, track, 3 tracks, status, off", false)));
        commands.add(slashCommand("restore", "Restore the queue saved before a crash, restart or everyone leaving"));

        return commands;
    }

    private static SlashCommandData slashCommand(String name, String description)
    {
        return Commands.slash(name, description)
                .setContexts(InteractionContextType.GUILD);
    }

    private static OptionData songQueryOption()
    {
        return new OptionData(OptionType.STRING, "query", "Song name or URL", true)
                .setAutoComplete(true);
    }

    private static OptionData unlikeTargetOption()
    {
        return new OptionData(OptionType.STRING, "target", "current, index from /liked, or song query", true)
                .setAutoComplete(true);
    }

    private static OptionData playlistNameOption()
    {
        return new OptionData(OptionType.STRING, "name", "Playlist name", true)
                .setAutoComplete(true);
    }

    private static OptionData shareModeOption()
    {
        return new OptionData(OptionType.STRING, "mode", "Share mode", true)
                .addChoice("copy", "copy")
                .addChoice("follow", "follow");
    }

    private static OptionData audioFilterPresetOption()
    {
        OptionData option = new OptionData(OptionType.STRING, "preset", "Audio filter preset", false);
        for(AudioFilterPreset preset : AudioFilterPreset.values())
            option.addChoice(preset.getDisplayName(), preset.getId());
        return option;
    }

    private static OptionData autoplayModeOption()
    {
        return new OptionData(OptionType.STRING, "mode", "Autoplay mode", false)
                .addChoice("off", "off")
                .addChoice("smart", "smart")
                .addChoice("related", "related")
                .addChoice("artist", "artist")
                .addChoice("playlist", "playlist")
                .addChoice("server", "server");
    }

    private static OptionData guessModeOption()
    {
        return new OptionData(OptionType.STRING, "mode", "Game mode", false)
                .addChoice("classic", "classic")
                .addChoice("hardcore", "hardcore")
                .addChoice("impossible", "impossible")
                .addChoice("custom", "custom");
    }

    private static OptionData guessWinOption()
    {
        return new OptionData(OptionType.STRING, "win", "Win condition", false)
                .addChoice("rounds", "rounds")
                .addChoice("points", "points")
                .addChoice("endless", "endless");
    }

    private static OptionData guessInputOption()
    {
        return new OptionData(OptionType.STRING, "input", "Guess input mode", false)
                .addChoice("private", "private")
                .addChoice("chat", "chat")
                .addChoice("both", "both");
    }

    private static OptionData guessMatchOption()
    {
        return new OptionData(OptionType.STRING, "match", "Guess matching strictness", false)
                .addChoice("forgiving", "forgiving")
                .addChoice("strict", "strict");
    }

    private static OptionData guessClipPositionOption()
    {
        return new OptionData(OptionType.STRING, "clip_position", "Where the clip starts", false)
                .addChoice("intro", "intro")
                .addChoice("outro", "outro")
                .addChoice("random", "random")
                .addChoice("first audible", "first_audible");
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
            case "playlist": handlePlaylist(event); break;
            case "like": handleLike(event); break;
            case "unlike": handleUnlike(event); break;
            case "liked": handleLiked(event); break;
            case "nowplaying": handleNowPlaying(event); break;
            case "queue": handleSharedMusicCommand(event, queueCmd, "", false, true, false); break;
            case "history": handleHistory(event); break;
            case "skip": handleSharedMusicCommand(event, skipCmd, "", false, true, true); break;
            case "remove": handleSharedMusicCommand(event, removeCmd, event.getOption("position").getAsString(), false, true, true); break;
            case "shuffle": handleSharedMusicCommand(event, shuffleCmd, "", false, true, true); break;
            case "seek": handleSharedMusicCommand(event, seekCmd, event.getOption("time").getAsString(), false, true, true); break;
            case "lyrics": handleLyrics(event); break;
            case "correctlyrics": handleCorrectLyrics(event); break;
            case "guess": handleGuess(event); break;
            case "hostgame": handleHostGame(event); break;
            case "g": bot.getGuessMusicService().guess(event, event.getOption("answer").getAsString()); break;
            case "idk": bot.getGuessMusicService().idk(event); break;
            case "playlists": handlePlaylists(event); break;
            case "search": handleSearch(event, "ytsearch:", "YouTube"); break;
            case "scsearch": handleSearch(event, "scsearch:", "SoundCloud"); break;
            case "forceskip": handleSharedMusicCommand(event, forceskipCmd, "", true, true, false); break;
            case "pause": handlePause(event); break;
            case "resume": handleResume(event); break;
            case "stop": handleSharedMusicCommand(event, stopCmd, "", true, false, false); break;
            case "volume": handleSharedMusicCommand(event, volumeCmd, getOptionalLongArg(event, "level"), true, false, false); break;
            case "filter": handleSharedMusicCommand(event, filterCmd, getOptionalStringArg(event, "preset"), true, false, false); break;
            case "repeat": handleSharedDJCommand(event, repeatCmd, getOptionalStringArg(event, "mode")); break;
            case "loop": handleSharedDJCommand(event, repeatCmd, getOptionalStringArg(event, "mode")); break;
            case "autoplay": handleSharedDJCommand(event, autoplayCmd, getOptionalStringArg(event, "mode")); break;
            case "radio": handleSharedDJCommand(event, autoplayCmd, getOptionalStringArg(event, "mode")); break;
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
            case "preloadlyrics": handleSharedAdminCommand(event, preloadLyricsCmd, getOptionalStringArg(event, "state")); break;
            case "autolyrics": handleSharedAdminCommand(event, autoLyricsCmd, getOptionalStringArg(event, "state")); break;
            case "stats": handleEconomyCommand(event, statsCmd, userArg(event)); break;
            case "balance": handleEconomyCommand(event, balanceCmd, userArg(event)); break;
            case "daily": handleEconomyCommand(event, dailyCmd, ""); break;
            case "gamble": handleEconomyCommand(event, gambleCmd,
                    event.getOption("amount").getAsLong() + " " + getOptionalStringArg(event, "game")); break;
            case "predict": handleEconomyCommand(event, predictCmd,
                    event.getOption("amount").getAsLong() + " " + getOptionalStringArg(event, "prediction")); break;
            case "roulette": handleEconomyCommand(event, rouletteCmd,
                    event.getOption("amount").getAsLong() + " " + getOptionalStringArg(event, "bet")
                            + optionalLongSuffix(event, "number")); break;
            case "wheel": handleEconomyCommand(event, wheelCmd,
                    String.valueOf(event.getOption("amount").getAsLong())); break;
            case "keno": handleEconomyCommand(event, kenoCmd,
                    event.getOption("amount").getAsLong() + " " + getOptionalStringArg(event, "numbers")); break;
            case "scratch": handleEconomyCommand(event, scratchCmd,
                    String.valueOf(event.getOption("amount").getAsLong())); break;
            case "double": handleEconomyCommand(event, doubleCmd,
                    String.valueOf(event.getOption("amount").getAsLong())); break;
            case "rps": handleEconomyCommand(event, rpsCmd,
                    String.valueOf(event.getOption("amount").getAsLong())); break;
            case "hilo": handleEconomyCommand(event, hiloCmd,
                    String.valueOf(event.getOption("amount").getAsLong())); break;
            case "crash": handleEconomyCommand(event, crashCmd,
                    event.getOption("amount").getAsLong()
                            + (event.getOption("target") != null ? " " + event.getOption("target").getAsDouble() : "")); break;
            case "mines": handleEconomyCommand(event, minesCmd,
                    event.getOption("amount").getAsLong() + optionalLongSuffix(event, "bombs")); break;
            case "blackjack": handleEconomyCommand(event, blackjackCmd,
                    String.valueOf(event.getOption("amount").getAsLong())); break;
            case "work": handleEconomyCommand(event, workCmd, ""); break;
            case "trivia": handleEconomyCommand(event, triviaCmd, ""); break;
            case "duel": handleEconomyCommand(event, duelCmd,
                    event.getOption("opponent").getAsUser().getId() + " " + event.getOption("amount").getAsLong()); break;
            case "lottery": handleEconomyCommand(event, lotteryCmd,
                    getOptionalStringArg(event, "action") + optionalLongSuffix(event, "tickets")); break;
            case "leaderboard": handleEconomyCommand(event, leaderboardCmd, getOptionalStringArg(event, "metric")); break;
            case "achievements": handleEconomyCommand(event, achievementsCmd, userArg(event)); break;
            case "avoid": handleSharedDJCommand(event, avoidCmd, getOptionalStringArg(event, "song")); break;
            case "unavoid": handleSharedDJCommand(event, unavoidCmd, event.getOption("song").getAsString()); break;
            case "avoided": handleEconomyCommand(event, avoidedCmd, ""); break;
            case "sleep": handleSharedDJCommand(event, sleepCmd, getOptionalStringArg(event, "when")); break;
            case "restore": handleSharedMusicCommand(event, restoreCmd, "", false, false, true); break;
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

    private void handleHistory(SlashCommandInteractionEvent event)
    {
        handleSharedMusicCommand(event, historyCmd,
                event.getSubcommandName() == null ? "" : event.getSubcommandName(),
                false, false, false);
    }

    private void handleGuess(SlashCommandInteractionEvent event)
    {
        String subcommand = event.getSubcommandName();
        if(subcommand == null)
        {
            event.reply(bot.getConfig().getError() + " Unknown guess command.").setEphemeral(true).queue();
            return;
        }

        if("submit".equals(subcommand))
        {
            bot.getGuessMusicService().guess(event, event.getOption("answer").getAsString());
            return;
        }

        SlashCommandContext context = new SlashCommandContext(event, bot, "");
        switch(subcommand)
        {
            case "start":
                if(!CommandChecks.checkMusicCommand(bot, context, false, true))
                    return;
                bot.getGuessMusicService().start(context, bot.getGuessMusicService().optionsFromSlash(event));
                break;
            case "join":
                bot.getGuessMusicService().join(context);
                break;
            case "leave":
                bot.getGuessMusicService().leave(context);
                break;
            case "status":
                bot.getGuessMusicService().status(context);
                break;
            case "settings":
                bot.getGuessMusicService().settings(context);
                break;
            case "reveal":
                bot.getGuessMusicService().reveal(context);
                break;
            case "stop":
                bot.getGuessMusicService().stop(context);
                break;
            case "hints":
                bot.getGuessMusicService().setHints(context,
                        event.getOption("enabled") == null ? null : event.getOption("enabled").getAsBoolean(),
                        event.getOption("hint_interval") == null ? null : (int)event.getOption("hint_interval").getAsLong(),
                        event.getOption("hint_seconds") == null ? null : (int)event.getOption("hint_seconds").getAsLong(),
                        event.getOption("hint_replays") == null ? null : (int)event.getOption("hint_replays").getAsLong());
                break;
            case "highlight":
                bot.getGuessMusicService().setHighlight(context,
                        event.getOption("timestamp") == null ? null : event.getOption("timestamp").getAsString());
                break;
            default:
                event.reply(bot.getConfig().getError() + " Unknown guess command.").setEphemeral(true).queue();
                break;
        }
    }

    private void handleHostGame(SlashCommandInteractionEvent event)
    {
        String subcommand = event.getSubcommandName();
        if(subcommand == null)
        {
            event.reply(bot.getConfig().getError() + " Unknown host command.").setEphemeral(true).queue();
            return;
        }
        if("add".equals(subcommand))
        {
            // Opens a private modal; the host's song text never appears in the channel.
            bot.getGuessMusicService().openHostAddModal(event);
            return;
        }

        SlashCommandContext context = new SlashCommandContext(event, bot, "");
        switch(subcommand)
        {
            case "start":
                if(!CommandChecks.checkMusicCommand(bot, context, false, true))
                    return;
                bot.getGuessMusicService().start(context, bot.getGuessMusicService().hostOptionsFromSlash(event));
                break;
            case "status":
                bot.getGuessMusicService().status(context);
                break;
            case "join":
                bot.getGuessMusicService().join(context);
                break;
            case "leave":
                bot.getGuessMusicService().leave(context);
                break;
            case "reveal":
                bot.getGuessMusicService().reveal(context);
                break;
            case "stop":
                bot.getGuessMusicService().stop(context);
                break;
            default:
                event.reply(bot.getConfig().getError() + " Unknown host command.").setEphemeral(true).queue();
                break;
        }
    }

    private void handleEconomyCommand(SlashCommandInteractionEvent event, UnifiedCommand command, String args)
    {
        command.doCommand(new SlashCommandContext(event, bot, args));
    }

    private String userArg(SlashCommandInteractionEvent event)
    {
        return event.getOption("user") == null ? "" : event.getOption("user").getAsUser().getId();
    }

    private String getOptionalStringArg(SlashCommandInteractionEvent event, String name)
    {
        return event.getOption(name) == null ? "" : event.getOption(name).getAsString();
    }

    private String getOptionalLongArg(SlashCommandInteractionEvent event, String name)
    {
        return event.getOption(name) == null ? "" : String.valueOf(event.getOption(name).getAsLong());
    }

    /** Returns {@code " <value>"} for a present integer option, or {@code ""} when absent. */
    private String optionalLongSuffix(SlashCommandInteractionEvent event, String name)
    {
        return event.getOption(name) == null ? "" : " " + event.getOption(name).getAsLong();
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event)
    {
        try
        {
            if (isPlaylistNameOption(event))
            {
                event.replyChoices(getPlaylistNameChoices(event)).queue();
                return;
            }
            if ("query".equals(event.getFocusedOption().getName()) && isSongQueryCommand(event))
            {
                event.replyChoices(getSongQueryChoices(event)).queue();
                return;
            }
            if ("target".equals(event.getFocusedOption().getName()) && "unlike".equals(event.getName()))
            {
                event.replyChoices(getUnlikeTargetChoices(event)).queue();
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

    private boolean isPlaylistNameCommand(CommandAutoCompleteInteractionEvent event)
    {
        return "playplaylist".equals(event.getName())
                || ("guess".equals(event.getName()) && "start".equals(event.getSubcommandName()))
                || ("playlist".equals(event.getName()) && !"create".equals(event.getSubcommandName())
                        && !"addshared".equals(event.getSubcommandName()));
    }

    private boolean isPlaylistNameOption(CommandAutoCompleteInteractionEvent event)
    {
        String optionName = event.getFocusedOption().getName();
        return ("name".equals(optionName) && isPlaylistNameCommand(event))
                || ("playlist".equals(optionName) && "guess".equals(event.getName())
                && "start".equals(event.getSubcommandName()))
                || ("playlist".equals(optionName) && "hostgame".equals(event.getName())
                && "start".equals(event.getSubcommandName()));
    }

    private boolean isSongQueryCommand(CommandAutoCompleteInteractionEvent event)
    {
        String commandName = event.getName();
        if("playlist".equals(commandName) && "add".equals(event.getSubcommandName()))
            return true;
        if("like".equals(commandName))
            return true;
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
        for (PlaylistSummary playlist : bot.getUserPlaylistService().listPlaylists(event.getUser().getIdLong()))
        {
            String name = playlist.getName();
            if (choices.size() >= MAX_AUTOCOMPLETE_CHOICES)
                break;
            if (name.length() > MAX_AUTOCOMPLETE_LENGTH)
                continue;
            if (query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query))
                choices.add(new Command.Choice(name, name));
        }
        return choices;
    }

    private List<Command.Choice> getUnlikeTargetChoices(CommandAutoCompleteInteractionEvent event)
    {
        String query = event.getFocusedOption().getValue().trim().toLowerCase(Locale.ROOT);
        List<Command.Choice> choices = new ArrayList<>();
        if("current".contains(query))
            addChoice(choices, "Current song", "current");

        bot.getUserPlaylistService().resolveVisible(event.getUser().getIdLong(), UserPlaylistService.LIKED_SONGS)
                .ifPresent(playlist -> {
                    List<PlaylistTrack> items = bot.getUserPlaylistService().listItems(playlist.getId());
                    for(int i = 0; i < items.size() && choices.size() < MAX_AUTOCOMPLETE_CHOICES; i++)
                    {
                        PlaylistTrack item = items.get(i);
                        String index = String.valueOf(i + 1);
                        String author = item.getAuthor() == null || item.getAuthor().isBlank() ? "" : " - " + item.getAuthor();
                        String display = index + ". " + item.getDisplayTitle() + author;
                        if(query.isEmpty() || index.startsWith(query) || display.toLowerCase(Locale.ROOT).contains(query))
                            addChoice(choices, display, index);
                    }
                });

        if(!query.isEmpty())
            getSongQueryChoices(event).forEach(choice -> addChoice(choices, choice.getName(), choice.getAsString()));
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
        if (blockIfGuessMusicActive(event))
            return;
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
        int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), state.query, track, event.getChannel().getIdLong()))) + 1;
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

    private boolean blockIfGuessMusicActive(SlashCommandInteractionEvent event)
    {
        if(!bot.getGuessMusicService().isActive(event.getGuild()))
            return false;
        event.reply(bot.getGuessMusicService().activeGameBlockMessage()).setEphemeral(true).queue();
        return true;
    }

    private boolean blockIfGuessMusicActive(StringSelectInteractionEvent event)
    {
        if(!bot.getGuessMusicService().isActive(event.getGuild()))
            return false;
        event.reply(bot.getGuessMusicService().activeGameBlockMessage()).setEphemeral(true).queue();
        return true;
    }

    private boolean editIfGuessMusicActive(InteractionHook hook, Guild guild)
    {
        if(!bot.getGuessMusicService().isActive(guild))
            return false;
        hook.editOriginal(bot.getGuessMusicService().activeGameBlockMessage()).queue();
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
                {"play playlist <name>", "playplaylist name:<name>", "Play one of your playlists"},
                {"", "playlist <list|create|rename|delete|view|play|add|addcurrent|addqueue|remove|move|clear|share|addshared|unshare|unfollow|copy>", "Manage playlists"},
                {"", "like source:<current|queue|query>", "Add music to Liked Songs"},
                {"", "unlike target:<current|index|query>", "Remove music from Liked Songs"},
                {"", "liked action:<view|play>", "View or play Liked Songs"},
                {"playtop <title|URL>", "playtop query:<title|URL>", "Add a song to the top of the queue"},
                {"playlists", "playlists", "List your playlists, including followed and liked lists"},
                {"nowplaying", "nowplaying", "Open or reuse the persistent music panel"},
                {"queue", "queue", "Show the queue"},
                {"history <session|guild>", "history <session|guild>", "Show played song history"},
                {"search <query>", "search query:<query>", "Search YouTube and choose a result"},
                {"scsearch <query>", "scsearch query:<query>", "Search SoundCloud and choose a result"},
                {"skip", "skip", "Vote to skip"},
                {"remove <position|all>", "remove position:<position|all>", "Remove queued songs"},
                {"shuffle", "shuffle", "Shuffle your queued songs; DJs shuffle the full queue"},
                {"seek <time>", "seek time:<time>", "Seek the current song"},
                {"lyrics [song]", "lyrics [query]", "Fetch lyrics"},
                {"correctlyrics <genius-url> | <song>", "correctlyrics url:<genius-url> query:<song>", "Correct cached lyrics for a song"},
                {"guess [start|status|join|leave|reveal|stop|hints|highlight]", "guess <start|settings|join|leave|status|reveal|stop|hints|highlight>", "Play a guess the music game"},
                {"", "g", "Fast private guess for the active guess the music round"},
                {"", "idk", "Toggle pass for the active guess the music round"}
        }, prefix);
        addHelpFields(eb, "DJ", new String[][]{
                {"forceskip", "forceskip", "Force skip"},
                {"pause", "pause", "Pause playback; /pause toggles"},
                {"play", "resume", "Resume only if paused"},
                {"stop", "stop", "Stop playback and clear the queue"},
                {"volume [0-150]", "volume [level]", "Show or set volume"},
                {"filter [off|bassboost|nightcore|8d|vaporwave|tremolo|karaoke|list]", "filter [preset]", "Show or set audio filter"},
                {"repeat [off|all|single]", "repeat [mode]", "Set repeat mode"},
                {"loop [off|all|single]", "loop [mode]", "Alias for repeat"},
                {"autoplay [off|smart|related|artist|playlist|server]", "autoplay [mode]", "Set autoplay mode"},
                {"radio [off|smart|related|artist|playlist|server]", "radio [mode]", "Alias for autoplay"},
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
                {"queuetype [fair|linear]", "queuetype [type]", "Show or set queue type"},
                {"preloadlyrics [on|off]", "preloadlyrics [state]", "Preload lyrics for upcoming songs"},
                {"autolyrics [on|off]", "autolyrics [state]", "Auto-post lyrics when a song starts"}
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
                        "\n**Autoplay Mode:** " + formatAutoplayMode(s.getAutoplayMode()) +
                        "\n**Queue Type:** " + s.getQueueType().getUserFriendlyName() +
                        "\n**Preload Lyrics:** " + (s.isAutoPreloadLyrics() ? "On" : "Off") +
                        "\n**Auto Lyrics:** " + (s.isAutoShowLyrics() ? "On" : "Off"))
                .setFooter(event.getJDA().getGuilds().size() + " servers");
        event.replyEmbeds(eb.build()).queue();
    }

    private static String formatAutoplayMode(AutoplayMode mode)
    {
        return mode == null ? AutoplayMode.OFF.getUserFriendlyName() : mode.getUserFriendlyName();
    }

    // ========================
    // Music Commands
    // ========================

    private void handlePlay(SlashCommandInteractionEvent event, boolean playTop)
    {
        if (blockIfGuessMusicActive(event)) return;
        if (!checkVoiceState(event, false)) return;
        if (!connectToVoiceChannel(event)) return;
        // Play the request, and if a saved queue is waiting, offer to restore it alongside.
        RestoreCmd.sendOfferIfPending(bot, event.getGuild(), event.getChannel());
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
        try
        {
            String name = event.getOption("name").getAsString();
            handlePlaylistPlay(event, name);
        }
        catch(PlaylistException ex)
        {
            event.reply(bot.getConfig().getError() + " " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handlePlaylist(SlashCommandInteractionEvent event)
    {
        String subcommand = event.getSubcommandName();
        if(subcommand == null)
        {
            event.reply(bot.getConfig().getError() + " Unknown playlist command.").setEphemeral(true).queue();
            return;
        }

        try
        {
            switch(subcommand)
            {
                case "list": handlePlaylistList(event); break;
                case "create": handlePlaylistCreate(event); break;
                case "rename": handlePlaylistRename(event); break;
                case "delete": handlePlaylistDelete(event); break;
                case "view": handlePlaylistView(event, event.getOption("name").getAsString()); break;
                case "play": handlePlaylistPlay(event, event.getOption("name").getAsString()); break;
                case "add": handlePlaylistAddQuery(event); break;
                case "addcurrent": handlePlaylistAddCurrent(event, event.getOption("name").getAsString()); break;
                case "addqueue": handlePlaylistAddQueue(event, event.getOption("name").getAsString()); break;
                case "remove": handlePlaylistRemove(event); break;
                case "move": handlePlaylistMove(event); break;
                case "clear": handlePlaylistClear(event); break;
                case "share": handlePlaylistShare(event); break;
                case "addshared": handlePlaylistAddShared(event); break;
                case "unshare": handlePlaylistUnshare(event); break;
                case "unfollow": handlePlaylistUnfollow(event); break;
                case "copy": handlePlaylistCopy(event); break;
                default:
                    event.reply(bot.getConfig().getError() + " Unknown playlist command.").setEphemeral(true).queue();
            }
        }
        catch(PlaylistException ex)
        {
            event.reply(bot.getConfig().getError() + " " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleLike(SlashCommandInteractionEvent event)
    {
        String source = event.getOption("source").getAsString();
        String liked = UserPlaylistService.LIKED_SONGS;
        try
        {
            if("queue".equals(source))
            {
                handlePlaylistAddQueue(event, liked);
            }
            else if("current".equals(source))
            {
                bot.getUserPlaylistService().getOrCreateLikedPlaylist(event.getUser().getIdLong());
                handlePlaylistAddCurrent(event, liked);
            }
            else
            {
                bot.getUserPlaylistService().getOrCreateLikedPlaylist(event.getUser().getIdLong());
                if(event.getOption("query") == null || event.getOption("query").getAsString().trim().isEmpty())
                {
                    event.reply(bot.getConfig().getError() + " Please provide a query when liking by query.").setEphemeral(true).queue();
                    return;
                }
                addQueryToPlaylist(event, liked, event.getOption("query").getAsString(), true);
            }
        }
        catch(PlaylistException ex)
        {
            event.reply(bot.getConfig().getError() + " " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleUnlike(SlashCommandInteractionEvent event)
    {
        String target = event.getOption("target").getAsString().trim();
        if(target.isEmpty())
        {
            event.reply(bot.getConfig().getError() + " Please provide `current`, an index from `/liked`, or a song query.").setEphemeral(true).queue();
            return;
        }

        try
        {
            bot.getUserPlaylistService().getOrCreateLikedPlaylist(event.getUser().getIdLong());
            if("current".equalsIgnoreCase(target))
            {
                handleUnlikeCurrent(event);
                return;
            }

            Optional<Integer> index = parsePlaylistIndex(target);
            if(index.isPresent())
            {
                bot.getUserPlaylistService().removeItem(event.getUser().getIdLong(), UserPlaylistService.LIKED_SONGS, index.get());
                event.reply(bot.getConfig().getSuccess() + " Removed liked song `" + index.get() + "`.").setEphemeral(true).queue();
                return;
            }

            Optional<PlaylistTrack> removed = bot.getUserPlaylistService().removeFirstMatchingTrack(
                    event.getUser().getIdLong(), UserPlaylistService.LIKED_SONGS, target);
            if(removed.isPresent())
                event.reply(bot.getConfig().getSuccess() + " Removed " + formatTrackName(removed.get()) + " from Liked Songs.").setEphemeral(true).queue();
            else
                event.reply(bot.getConfig().getWarning() + " No liked song matched `" + target + "`.").setEphemeral(true).queue();
        }
        catch(PlaylistException ex)
        {
            event.reply(bot.getConfig().getError() + " " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleUnlikeCurrent(SlashCommandInteractionEvent event)
    {
        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        if(handler == null || handler.getPlayer().getPlayingTrack() == null)
        {
            event.reply(bot.getConfig().getWarning() + " Nothing is currently playing.").setEphemeral(true).queue();
            return;
        }

        AudioTrack current = handler.getPlayer().getPlayingTrack();
        PlaylistTrack track = PlaylistTrack.fromAudioTrack(current, current.getInfo().uri);
        Optional<PlaylistTrack> removed = bot.getUserPlaylistService().removeTrack(event.getUser().getIdLong(), UserPlaylistService.LIKED_SONGS, track);
        if(removed.isPresent())
            event.reply(bot.getConfig().getSuccess() + " Removed " + formatTrackName(removed.get()) + " from Liked Songs.").setEphemeral(true).queue();
        else
            event.reply(bot.getConfig().getWarning() + " The current song is not in Liked Songs.").setEphemeral(true).queue();
    }

    private Optional<Integer> parsePlaylistIndex(String value)
    {
        try
        {
            long parsed = Long.parseLong(value);
            if(parsed > 0 && parsed <= Integer.MAX_VALUE)
                return Optional.of((int)parsed);
        }
        catch(NumberFormatException ignored)
        {
            // Not an index; treat it as a query.
        }
        return Optional.empty();
    }

    private String formatTrackName(PlaylistTrack track)
    {
        String author = track.getAuthor() == null || track.getAuthor().isBlank() ? "" : " - " + track.getAuthor();
        return "**" + track.getDisplayTitle() + "**" + author;
    }

    private void handleLiked(SlashCommandInteractionEvent event)
    {
        try
        {
            bot.getUserPlaylistService().getOrCreateLikedPlaylist(event.getUser().getIdLong());
            String action = event.getOption("action").getAsString();
            if("play".equals(action))
                handlePlaylistPlay(event, UserPlaylistService.LIKED_SONGS);
            else
                handlePlaylistView(event, UserPlaylistService.LIKED_SONGS);
        }
        catch(PlaylistException ex)
        {
            event.reply(bot.getConfig().getError() + " " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handlePlaylistList(SlashCommandInteractionEvent event)
    {
        List<PlaylistSummary> playlists = bot.getUserPlaylistService().listPlaylists(event.getUser().getIdLong());
        if(playlists.isEmpty())
        {
            event.reply(bot.getConfig().getWarning() + " You do not have any playlists yet.").setEphemeral(true).queue();
            return;
        }

        StringBuilder sb = new StringBuilder(bot.getConfig().getSuccess()).append(" Your playlists:\n");
        for(PlaylistSummary playlist : playlists)
        {
            sb.append("`").append(playlist.getName()).append("`")
                    .append(" - ").append(playlist.getItemCount()).append(" songs");
            if(playlist.isFollowed())
                sb.append(" (followed, read-only)");
            if(playlist.isLiked())
                sb.append(" (liked)");
            sb.append('\n');
        }
        event.reply(truncateMessage(sb.toString())).setEphemeral(true).queue();
    }

    private void handlePlaylistCreate(SlashCommandInteractionEvent event)
    {
        PlaylistSummary playlist = bot.getUserPlaylistService().createPlaylist(event.getUser().getIdLong(), event.getOption("name").getAsString());
        event.reply(bot.getConfig().getSuccess() + " Created playlist `" + playlist.getName() + "`.").setEphemeral(true).queue();
    }

    private void handlePlaylistRename(SlashCommandInteractionEvent event)
    {
        String name = event.getOption("name").getAsString();
        String newName = event.getOption("new_name").getAsString();
        bot.getUserPlaylistService().renamePlaylist(event.getUser().getIdLong(), name, newName);
        event.reply(bot.getConfig().getSuccess() + " Renamed `" + name + "` to `" + UserPlaylistService.sanitizeName(newName) + "`.").setEphemeral(true).queue();
    }

    private void handlePlaylistDelete(SlashCommandInteractionEvent event)
    {
        String name = event.getOption("name").getAsString();
        bot.getUserPlaylistService().deletePlaylist(event.getUser().getIdLong(), name);
        event.reply(bot.getConfig().getSuccess() + " Deleted playlist `" + name + "`.").setEphemeral(true).queue();
    }

    private void handlePlaylistView(SlashCommandInteractionEvent event, String name)
    {
        PlaylistSummary playlist = bot.getUserPlaylistService().resolveVisible(event.getUser().getIdLong(), name)
                .orElseThrow(() -> new PlaylistException("Playlist `" + name + "` does not exist."));
        List<PlaylistTrack> items = bot.getUserPlaylistService().listItems(playlist.getId());
        if(items.isEmpty())
        {
            event.reply(bot.getConfig().getWarning() + " Playlist `" + playlist.getName() + "` is empty.").setEphemeral(true).queue();
            return;
        }

        event.reply(PlaylistViewPaginator.buildCreateMessage(bot, event.getGuild(), event.getUser().getIdLong(),
                playlist, items, 1)).setEphemeral(true).queue();
    }

    private void handlePlaylistPlay(SlashCommandInteractionEvent event, String name)
    {
        if (blockIfGuessMusicActive(event)) return;
        if (!checkVoiceState(event, false)) return;
        if (!connectToVoiceChannel(event)) return;
        RestoreCmd.sendOfferIfPending(bot, event.getGuild(), event.getChannel());

        PlaylistSummary playlist = bot.getUserPlaylistService().resolveVisible(event.getUser().getIdLong(), name)
                .orElseThrow(() -> new PlaylistException("Playlist `" + name + "` does not exist."));
        List<PlaylistTrack> items = new ArrayList<>(bot.getUserPlaylistService().listItems(playlist.getId()));
        if(items.isEmpty())
        {
            event.reply(bot.getConfig().getWarning() + " Playlist `" + playlist.getName() + "` is empty.").setEphemeral(true).queue();
            return;
        }
        if(playlist.isLegacyShuffle())
            Collections.shuffle(items);

        bot.getPlayerManager().setUpHandler(event.getGuild());
        event.deferReply().queue(hook -> queuePlaylistItems(event, hook, playlist, items));
    }

    private void handlePlaylistAddQuery(SlashCommandInteractionEvent event)
    {
        addQueryToPlaylist(event, event.getOption("name").getAsString(), event.getOption("query").getAsString(), false);
    }

    private void handlePlaylistAddCurrent(SlashCommandInteractionEvent event, String name)
    {
        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        if(handler == null || handler.getPlayer().getPlayingTrack() == null)
        {
            event.reply(bot.getConfig().getWarning() + " Nothing is currently playing.").setEphemeral(true).queue();
            return;
        }
        PlaylistTrack track = PlaylistTrack.fromAudioTrack(handler.getPlayer().getPlayingTrack(), handler.getPlayer().getPlayingTrack().getInfo().uri);
        AddResult result = bot.getUserPlaylistService().addTrack(event.getUser().getIdLong(), name, track);
        event.reply(formatPlaylistAddResult(name, result, UserPlaylistService.LIKED_SONGS.equals(name))).setEphemeral(true).queue();
    }

    private void handlePlaylistAddQueue(SlashCommandInteractionEvent event, String name)
    {
        event.deferReply(true).queue(hook ->
        {
            try
            {
                AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
                if(handler == null)
                {
                    hook.editOriginal(bot.getConfig().getWarning() + " There is no music in the queue.").queue();
                    return;
                }

                List<PlaylistTrack> tracks = new ArrayList<>();
                AudioTrack current = handler.getPlayer().getPlayingTrack();
                if(current != null)
                    tracks.add(PlaylistTrack.fromAudioTrack(current, current.getInfo().uri));
                tracks.addAll(handler.getQueue().getList().stream()
                        .map(queued -> PlaylistTrack.fromAudioTrack(queued.getTrack(), queued.getTrack().getInfo().uri))
                        .collect(Collectors.toList()));
                if(tracks.isEmpty())
                {
                    hook.editOriginal(bot.getConfig().getWarning() + " There is no music in the queue.").queue();
                    return;
                }

                if(UserPlaylistService.LIKED_SONGS.equals(name))
                    bot.getUserPlaylistService().getOrCreateLikedPlaylist(event.getUser().getIdLong());
                AddResult result = bot.getUserPlaylistService().addTracksToOwned(event.getUser().getIdLong(), name, tracks);
                hook.editOriginal(formatPlaylistAddResult(name, result, UserPlaylistService.LIKED_SONGS.equals(name))).queue();
            }
            catch(PlaylistException ex)
            {
                hook.editOriginal(bot.getConfig().getError() + " " + ex.getMessage()).queue();
            }
        });
    }

    private String formatPlaylistAddResult(String name, AddResult result, boolean liked)
    {
        String target = liked ? "Liked Songs" : "`" + name + "`";
        if(result.getAdded() == 0 && result.getSkippedDuplicates() > 0)
            return bot.getConfig().getWarning() + " No new songs were added to " + target
                    + "; `" + result.getSkippedDuplicates() + "` duplicate(s) skipped.";

        String message = bot.getConfig().getSuccess() + " Added `" + result.getAdded() + "` song(s) to " + target + ".";
        if(result.getSkippedDuplicates() > 0)
            message += " Skipped `" + result.getSkippedDuplicates() + "` duplicate(s).";
        return message;
    }

    private void handlePlaylistRemove(SlashCommandInteractionEvent event)
    {
        String name = event.getOption("name").getAsString();
        int index = (int)event.getOption("index").getAsLong();
        bot.getUserPlaylistService().removeItem(event.getUser().getIdLong(), name, index);
        event.reply(bot.getConfig().getSuccess() + " Removed song `" + index + "` from `" + name + "`.").setEphemeral(true).queue();
    }

    private void handlePlaylistMove(SlashCommandInteractionEvent event)
    {
        String name = event.getOption("name").getAsString();
        int from = (int)event.getOption("from").getAsLong();
        int to = (int)event.getOption("to").getAsLong();
        bot.getUserPlaylistService().moveItem(event.getUser().getIdLong(), name, from, to);
        event.reply(bot.getConfig().getSuccess() + " Moved song `" + from + "` to `" + to + "` in `" + name + "`.").setEphemeral(true).queue();
    }

    private void handlePlaylistClear(SlashCommandInteractionEvent event)
    {
        String name = event.getOption("name").getAsString();
        int count = bot.getUserPlaylistService().clearPlaylist(event.getUser().getIdLong(), name);
        event.reply(bot.getConfig().getSuccess() + " Removed `" + count + "` songs from `" + name + "`.").setEphemeral(true).queue();
    }

    private void handlePlaylistShare(SlashCommandInteractionEvent event)
    {
        String name = event.getOption("name").getAsString();
        ShareMode mode = ShareMode.valueOf(event.getOption("mode").getAsString().toUpperCase(Locale.ROOT));
        Share share = bot.getUserPlaylistService().createShare(event.getUser().getIdLong(), name, mode);
        event.reply(bot.getConfig().getSuccess() + " Share code for `" + share.getPlaylistName() + "`: `" + share.getCode()
                + "` (" + share.getMode().name().toLowerCase(Locale.ROOT) + ").").setEphemeral(true).queue();
    }

    private void handlePlaylistAddShared(SlashCommandInteractionEvent event)
    {
        String code = event.getOption("code").getAsString();
        String name = event.getOption("name") == null ? null : event.getOption("name").getAsString();
        PlaylistSummary playlist = bot.getUserPlaylistService().addShared(event.getUser().getIdLong(), code, name);
        event.reply(bot.getConfig().getSuccess() + " Added shared playlist as `" + playlist.getName() + "`"
                + (playlist.isFollowed() ? " (followed read-only)." : ".")).setEphemeral(true).queue();
    }

    private void handlePlaylistUnshare(SlashCommandInteractionEvent event)
    {
        int count = bot.getUserPlaylistService().revokeShare(event.getUser().getIdLong(), event.getOption("code").getAsString());
        if(count == 0)
            event.reply(bot.getConfig().getWarning() + " No active share code was found for your playlists.").setEphemeral(true).queue();
        else
            event.reply(bot.getConfig().getSuccess() + " Revoked that share code.").setEphemeral(true).queue();
    }

    private void handlePlaylistUnfollow(SlashCommandInteractionEvent event)
    {
        String name = event.getOption("name").getAsString();
        bot.getUserPlaylistService().unfollow(event.getUser().getIdLong(), name);
        event.reply(bot.getConfig().getSuccess() + " Unfollowed `" + name + "`.").setEphemeral(true).queue();
    }

    private void handlePlaylistCopy(SlashCommandInteractionEvent event)
    {
        PlaylistSummary playlist = bot.getUserPlaylistService().copyVisible(event.getUser().getIdLong(),
                event.getOption("name").getAsString(), event.getOption("new_name").getAsString());
        event.reply(bot.getConfig().getSuccess() + " Copied playlist to `" + playlist.getName() + "`.").setEphemeral(true).queue();
    }

    private void addQueryToPlaylist(SlashCommandInteractionEvent event, String playlistName, String query, boolean liked)
    {
        PlaylistSummary playlist = bot.getUserPlaylistService().resolveVisible(event.getUser().getIdLong(), playlistName)
                .orElseThrow(() -> new PlaylistException("Playlist `" + playlistName + "` does not exist."));
        if(!playlist.isEditable())
            throw new PlaylistException("`" + playlist.getName() + "` is followed read-only. Copy it before editing.");

        event.deferReply(true).queue(hook -> bot.getPlayerManager().loadItemOrdered(
                "playlist-add:" + event.getUser().getId(),
                query,
                new AudioLoadResultHandler()
                {
                    private void store(List<AudioTrack> loadedTracks)
                    {
                        List<PlaylistTrack> tracks = loadedTracks.stream()
                                .filter(track -> !bot.getConfig().isTooLong(track))
                                .map(track -> PlaylistTrack.fromAudioTrack(track, track.getInfo().uri))
                                .collect(Collectors.toList());
                        if(tracks.isEmpty())
                        {
                            hook.editOriginal(bot.getConfig().getWarning() + " No acceptable tracks were found.").queue();
                            return;
                        }
                        try
                        {
                            AddResult result = bot.getUserPlaylistService().addTracksToOwned(event.getUser().getIdLong(), playlist.getName(), tracks);
                            hook.editOriginal(formatPlaylistAddResult(playlist.getName(), result, liked)).queue();
                        }
                        catch(PlaylistException ex)
                        {
                            hook.editOriginal(bot.getConfig().getError() + " " + ex.getMessage()).queue();
                        }
                    }

                    @Override
                    public void trackLoaded(AudioTrack track)
                    {
                        store(List.of(track));
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist)
                    {
                        if(playlist.isSearchResult())
                        {
                            store(playlist.getTracks().isEmpty() ? Collections.emptyList() : List.of(playlist.getTracks().get(0)));
                        }
                        else if(playlist.getSelectedTrack() != null)
                        {
                            store(List.of(playlist.getSelectedTrack()));
                        }
                        else
                        {
                            store(playlist.getTracks());
                        }
                    }

                    @Override
                    public void noMatches()
                    {
                        hook.editOriginal(bot.getConfig().getWarning() + " No results found for `" + query + "`.").queue();
                    }

                    @Override
                    public void loadFailed(FriendlyException exception)
                    {
                        LOG.warn("Failed to load playlist add query '{}'", query, exception);
                        hook.editOriginal(bot.getConfig().getError() + " Error loading track.").queue();
                    }
                }));
    }

    private void queuePlaylistItems(SlashCommandInteractionEvent event, InteractionHook hook,
                                    PlaylistSummary playlist, List<PlaylistTrack> items)
    {
        if(editIfGuessMusicActive(hook, event.getGuild()))
            return;
        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        PlaylistTrackLoader.load(bot.getPlayerManager(), bot.getThreadpool(), playlist.getName(), items,
                bot.getConfig()::isTooLong, result ->
                {
                    if(editIfGuessMusicActive(hook, event.getGuild()))
                        return;
                    List<QueuedTrack> queuedTracks = new ArrayList<>();
                    for(List<AudioTrack> itemTracks : result.getTracksByItem())
                        for(AudioTrack track : itemTracks)
                            queuedTracks.add(new QueuedTrack(track, RequestMetadata.fromPlaylist(event.getUser(), playlist.getId(),
                                    playlist.getName(), track, event.getChannel().getIdLong())));
                    handler.addTracks(queuedTracks);
                    LOG.info("Slash playlist '{}' loaded in guild {} ({}); loadedTracks={}; errors={}; retries={}; elapsedMs={}",
                            playlist.getName(), event.getGuild().getName(), event.getGuild().getId(),
                            queuedTracks.size(), result.getFailed(), result.getRetries(), result.getElapsedMillis());
                    hook.editOriginal(bot.getConfig().getSuccess() + " Loaded playlist **" + playlist.getName()
                            + "** with `" + queuedTracks.size() + "` tracks"
                            + (result.getFailed() == 0 ? "." : " (`" + result.getFailed() + "` entries failed).")).queue();
                });
    }

    private String truncateMessage(String message)
    {
        if(message.length() <= 2000)
            return message;
        return message.substring(0, 1994) + " (...)";
    }

    private void handleNowPlaying(SlashCommandInteractionEvent event)
    {
        event.deferReply(true).queue(hook -> bot.getNowplayingHandler().showPanel(event.getGuild(), event.getChannel(), event.getMember(), true,
                result -> hook.editOriginal(bot.getConfig().getSuccess() + (result.isPosted()
                        ? " Music panel posted: " : " Music panel is already active: ") + result.getJumpUrl()).queue(),
                error -> hook.editOriginal(bot.getConfig().getError() + " I could not post the music panel in this channel.").queue()));
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
            query = com.jagrosh.jmusicbot.lyrics.LyricsQuery.forTrack(sendingHandler.getPlayer().getPlayingTrack());
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
        try
        {
            handlePlaylistList(event);
        }
        catch(PlaylistException ex)
        {
            event.reply(bot.getConfig().getError() + " " + ex.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleSearch(SlashCommandInteractionEvent event, String searchPrefix, String provider)
    {
        if (blockIfGuessMusicActive(event)) return;
        if (!checkVoiceState(event, false)) return;
        if (!connectToVoiceChannel(event)) return;
        RestoreCmd.sendOfferIfPending(bot, event.getGuild(), event.getChannel());

        String query = event.getOption("query").getAsString();
        bot.getPlayerManager().setUpHandler(event.getGuild());
        LOG.info("Loading slash /{} search in guild {} ({}); provider={}; query='{}'",
                event.getName(), event.getGuild().getName(), event.getGuild().getId(), provider, query);
        event.deferReply().queue(hook ->
                bot.getPlayerManager().loadItemOrdered(event.getGuild(), searchPrefix + query, new SearchResultHandler(hook, event, query, provider)));
    }

    private LyricsService getLyricsService()
    {
        return bot.getLyricsService();
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
            handler.updateMusicPanels();
            event.reply(bot.getConfig().getSuccess() + " Resumed **" + handler.getPlayer().getPlayingTrack().getInfo().title + "**").queue();
        }
        else
        {
            handler.getPlayer().setPaused(true);
            handler.updateMusicPanels();
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
        handler.updateMusicPanels();
        event.reply(bot.getConfig().getSuccess() + " Resumed **" + handler.getPlayer().getPlayingTrack().getInfo().title + "**").queue();
    }

    private void handlePlayNext(SlashCommandInteractionEvent event)
    {
        if (blockIfGuessMusicActive(event)) return;
        if (!checkDJPermission(event))
        {
            event.reply(bot.getConfig().getError() + " You need DJ permissions to use this command.").setEphemeral(true).queue();
            return;
        }
        if (!checkVoiceState(event, false)) return;
        if (!connectToVoiceChannel(event)) return;
        RestoreCmd.sendOfferIfPending(bot, event.getGuild(), event.getChannel());
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

    private void loadSpotifyPlaylistFallback(InteractionHook hook, SlashCommandInteractionEvent event,
                                             String args, boolean playTop)
    {
        hook.editOriginal(FormatUtil.filter(SpotifyPlaylistFallback.fallbackNotice(bot.getConfig().getWarning()))).queue();
        bot.getBlockingThreadpool().submit(() ->
        {
            SpotifyPlaylistFallback.PublicPlaylist playlist;
            try
            {
                playlist = SpotifyPlaylistFallback.fetch(args);
            }
            catch(Exception ex)
            {
                LOG.warn("Failed to fetch Spotify public playlist fallback for slash /{} in guild {} ({}); query='{}'",
                        event.getName(), event.getGuild().getName(), event.getGuild().getId(), args, ex);
                hook.editOriginal(FormatUtil.filter(bot.getConfig().getWarning()
                        + " Spotify did not expose this playlist through the API, and I could not read the public page fallback.")).queue();
                return;
            }

            List<PlaylistTrack> items = playlist.toPlaylistTracks();
            if(items.isEmpty())
            {
                LOG.warn("Spotify public playlist fallback found no tracks for slash /{} in guild {} ({}); query='{}'",
                        event.getName(), event.getGuild().getName(), event.getGuild().getId(), args);
                hook.editOriginal(FormatUtil.filter(bot.getConfig().getWarning()
                        + " Spotify did not expose this playlist through the API, and the public page had no readable tracks.")).queue();
                return;
            }

            hook.editOriginal(FormatUtil.filter(bot.getConfig().getLoading() + " Found `" + items.size() + "` tracks from **"
                    + playlist.getName() + "**. Loading them now...")).queue();
            PlaylistTrackLoader.load(bot.getPlayerManager(), bot.getThreadpool(), playlist.getName(), items,
                    bot.getConfig()::isTooLong, result ->
                    {
                        if(editIfGuessMusicActive(hook, event.getGuild()))
                            return;

                        List<QueuedTrack> queuedTracks = new ArrayList<>();
                        for(List<AudioTrack> itemTracks : result.getTracksByItem())
                            for(AudioTrack track : itemTracks)
                                queuedTracks.add(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(),
                                        args, track, event.getChannel().getIdLong())));
                        if(queuedTracks.isEmpty())
                        {
                            hook.editOriginal(FormatUtil.filter(bot.getConfig().getWarning()
                                    + " I found `" + items.size() + "` public Spotify tracks, but none could be loaded.")).queue();
                            return;
                        }

                        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
                        if(playTop)
                            handler.addTracksToFront(queuedTracks);
                        else
                            handler.addTracks(queuedTracks);

                        LOG.info("Spotify public playlist fallback loaded for slash /{} in guild {} ({}); playTop={}; query='{}'; playlist='{}'; loadedTracks={}; failed={}; retries={}; elapsedMs={}",
                                event.getName(), event.getGuild().getName(), event.getGuild().getId(), playTop, args,
                                playlist.getName(), queuedTracks.size(), result.getFailed(), result.getRetries(), result.getElapsedMillis());
                        String message = bot.getConfig().getSuccess()
                                + (playTop ? " Added `" : " Loaded `") + queuedTracks.size()
                                + "` tracks from **" + playlist.getName() + "**"
                                + (playTop ? " to the top of the queue." : ".")
                                + (result.getFailed() == 0 ? "" : "\n" + bot.getConfig().getWarning()
                                + " `" + result.getFailed() + "` entries failed.")
                                + "\n\n" + SpotifyPlaylistFallback.fallbackFootnote(bot.getConfig().getWarning());
                        hook.editOriginal(FormatUtil.filter(message)).queue();
                    });
        });
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
            if(editIfGuessMusicActive(hook, event.getGuild()))
                return;
            if (bot.getConfig().isTooLong(track))
            {
                LOG.warn("Rejected slash search track in guild {} ({}): track too long; provider={}; query='{}'; track={}",
                        event.getGuild().getName(), event.getGuild().getId(), provider, query, describeTrack(track));
                hook.editOriginal(bot.getConfig().getWarning() + " This track (**" + track.getInfo().title + "**) is longer than the allowed maximum: `"
                        + TimeUtil.formatTime(track.getDuration()) + "` > `" + bot.getConfig().getMaxTime() + "`").queue();
                return;
            }
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), query, track, event.getChannel().getIdLong()))) + 1;
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
            if(editIfGuessMusicActive(hook, event.getGuild()))
                return;
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
                pos = handler.addTrackToFront(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), args, track, event.getChannel().getIdLong()))) + 1;
            else
                pos = handler.addTrack(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), args, track, event.getChannel().getIdLong()))) + 1;
            LOG.info("Slash /{} track loaded in guild {} ({}); playTop={}; query='{}'; position={}; track={}",
                    event.getName(), event.getGuild().getName(), event.getGuild().getId(), playTop, args, pos, describeTrack(track));
            String addMsg = FormatUtil.filter(bot.getConfig().getSuccess() + " Added **" + track.getInfo().title +
                    "** (`" + TimeUtil.formatTime(track.getDuration()) + "`) " + (pos == 0 ? "to begin playing" : "to the queue at position " + pos));
            hook.editOriginal(addMsg).queue();
        }

        private int loadPlaylist(AudioPlaylist playlist, AudioTrack exclude)
        {
            if(editIfGuessMusicActive(hook, event.getGuild()))
                return 0;
            List<QueuedTrack> tracks = new ArrayList<>();
            playlist.getTracks().forEach(track -> {
                if (!bot.getConfig().isTooLong(track) && !track.equals(exclude))
                {
                    tracks.add(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), args, track, event.getChannel().getIdLong())));
                }
            });
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            if(playTop)
                handler.addTracksToFront(tracks);
            else
                handler.addTracks(tracks);
            LOG.info("Slash /{} playlist loaded in guild {} ({}); query='{}'; playlist='{}'; acceptedTracks={}; sourceTracks={}",
                    event.getName(), event.getGuild().getName(), event.getGuild().getId(), args,
                    playlist.getName(), tracks.size(), playlist.getTracks().size());
            return tracks.size();
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
                if(SpotifyPlaylistFallback.isSpotifyPlaylistUrl(args))
                {
                    loadSpotifyPlaylistFallback(hook, event, args, playTop);
                    return;
                }
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
            if(!ytsearch && SpotifyPlaylistFallback.isSpotifyPlaylistUrl(args))
            {
                loadSpotifyPlaylistFallback(hook, event, args, playTop);
                return;
            }
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
            if(editIfGuessMusicActive(hook, event.getGuild()))
                return;
            if (bot.getConfig().isTooLong(track))
            {
                LOG.warn("Rejected slash /playnext track in guild {} ({}): track too long; query='{}'; track={}",
                        event.getGuild().getName(), event.getGuild().getId(), args, describeTrack(track));
                hook.editOriginal(bot.getConfig().getWarning() + " This track is longer than the allowed maximum.").queue();
                return;
            }
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrackToFront(new QueuedTrack(track, RequestMetadata.fromSlash(event.getUser(), args, track, event.getChannel().getIdLong()))) + 1;
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
