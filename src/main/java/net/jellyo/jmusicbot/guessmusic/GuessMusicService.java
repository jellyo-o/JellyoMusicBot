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
package com.jagrosh.jmusicbot.guessmusic;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.PlaybackHistoryStore;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.guessmusic.GuessMusicHighlightStore.Highlight;
import com.jagrosh.jmusicbot.guessmusic.GuessMusicTitleMatcher.MatchMode;
import com.jagrosh.jmusicbot.guessmusic.GuessMusicTitleMatcher.ParsedTitle;
import com.jagrosh.jmusicbot.playlist.PlaylistTrack;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.PlaylistException;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.PlaylistSummary;
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.jagrosh.jmusicbot.utils.TrackIdentity;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GuessMusicService
{
    private static final Logger LOG = LoggerFactory.getLogger(GuessMusicService.class);
    public static final String START_BUTTON = "jmb-guess:start";
    public static final String CANCEL_BUTTON = "jmb-guess:cancel";
    public static final String JOIN_BUTTON = "jmb-guess:join";
    public static final String LEAVE_CONFIRM_PREFIX = "jmb-guess:leave-confirm:";
    public static final String LEAVE_CANCEL_PREFIX = "jmb-guess:leave-cancel:";
    public static final String GUESS_BUTTON = "jmb-guess:guess";
    public static final String IDK_BUTTON = "jmb-guess:idk";
    public static final String REPLAY_BUTTON = "jmb-guess:replay";
    public static final String CLIP_LONGER_BUTTON = "jmb-guess:clip-longer";
    public static final String CLIP_SHORTER_BUTTON = "jmb-guess:clip-shorter";
    public static final String REVEAL_BUTTON = "jmb-guess:reveal";
    public static final String STOP_BUTTON = "jmb-guess:stop";
    public static final String SETTINGS_PREFIX = "jmb-guess:settings:";
    public static final String GUESS_MODAL = "jmb-guess:modal";
    public static final String GUESS_INPUT = "guess";
    // Hosted-game: a host-only button + modal for privately feeding songs into the pool.
    public static final String ADD_SONG_BUTTON = "jmb-guess:host-add";
    public static final String ADD_SONG_MODAL = "jmb-guess:host-modal";
    public static final String ADD_SONG_INPUT = "host-songs";

    private static final int DEFAULT_ROUNDS = 10;
    private static final int DEFAULT_TARGET_POINTS = 15;
    private static final int DEFAULT_GUESSES_PER_USER = 0;
    private static final int DEFAULT_KNOWN_PERCENT = 70;
    private static final int DEFAULT_HINT_INTERVAL_SECONDS = 10;
    private static final int DEFAULT_HINT_STEP_SECONDS = 5;
    private static final int DEFAULT_HINT_REPLAYS = 3;
    private static final int DEFAULT_REPLAY_INTERVAL_SECONDS = 5;
    private static final int DEFAULT_FINAL_GUESS_SECONDS = 15;
    // Hosted games end if the host adds no new song within this window once the pool runs dry.
    private static final int DEFAULT_HOST_IDLE_SECONDS = 120;
    private static final int MAX_HOST_SONGS_PER_ADD = 25;
    private static final int MAX_REFERENCES = 2000;
    private static final int MAX_LOAD_ATTEMPTS = 12;
    private static final long BETWEEN_ROUNDS_SECONDS = 5L;
    private static final long POST_REVEAL_PLAYBACK_SECONDS = 10L;
    private static final long POST_REVEAL_FADE_MS = 1500L;
    private static final int COUNTDOWN_INTERVAL_SECONDS = 15;
    private static final int COUNTDOWN_FINAL_SECONDS = 10;
    // How many seed artists to enrich with their real Spotify catalogue per game (bounds API calls).
    private static final int SPOTIFY_ENRICH_ARTIST_LIMIT = 15;
    // Cover skips don't count as load failures, but are bounded per round so a cover-heavy discovery
    // pool can't spin indefinitely on MusicBrainz lookups.
    private static final int MAX_COVER_SKIPS_PER_ROUND = 10;

    private final Bot bot;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final GuessMusicHighlightStore highlightStore;
    private final SpotifyArtistCatalog spotifyCatalog;
    private final CoverDetector coverDetector;
    private final ExecutorService highlightExecutor;
    private final ExecutorService scanExecutor;
    private final ExecutorService enrichExecutor;

    public GuessMusicService(Bot bot)
    {
        this.bot = bot;
        this.highlightExecutor = Executors.newSingleThreadExecutor(r ->
        {
            Thread thread = new Thread(r, "guess-music-highlight-scanner");
            thread.setDaemon(true);
            return thread;
        });
        // The first-audible scan is a blocking audio-decode loop (up to ~15s). It MUST NOT run on
        // bot.getThreadpool() (a single shared scheduler that drives sleep timers, fades, round
        // timeouts, etc.) nor on highlightExecutor (so a scan never queues behind highlight analysis).
        this.scanExecutor = Executors.newSingleThreadExecutor(r ->
        {
            Thread thread = new Thread(r, "guess-music-scan");
            thread.setDaemon(true);
            return thread;
        });
        // Spotify catalog enrichment is blocking HTTP; keep it on its own thread so a slow/unresponsive
        // Spotify can never starve bot.getBlockingThreadpool() (which serves user-facing playback I/O).
        this.enrichExecutor = Executors.newSingleThreadExecutor(r ->
        {
            Thread thread = new Thread(r, "guess-music-spotify-enrich");
            thread.setDaemon(true);
            return thread;
        });

        GuessMusicHighlightStore store = new GuessMusicHighlightStore(com.jagrosh.jmusicbot.database.Database.defaultPath());
        try
        {
            store.init();
        }
        catch(Exception ex)
        {
            LOG.warn("Failed to initialize guess music highlight cache", ex);
            store = null;
        }
        this.highlightStore = store;
        this.spotifyCatalog = new SpotifyArtistCatalog(bot.getConfig().getSpotifyID(), bot.getConfig().getSpotifySecret());
        this.coverDetector = new CoverDetector();
        if(spotifyCatalog.isEnabled())
            LOG.info("Guess music discovery will enrich artists from the Spotify catalog");
        if(coverDetector.isEnabled())
            LOG.info("Guess music discovery rounds will be cover-filtered via MusicBrainz");
    }

    public void close()
    {
        highlightExecutor.shutdownNow();
        scanExecutor.shutdownNow();
        enrichExecutor.shutdownNow();
        if(highlightStore != null)
            highlightStore.close();
    }

    public void start(CommandContext event, Options options)
    {
        Guild guild = event.getGuild();
        if(guild == null)
        {
            event.replyErrorEphemeral("Guess the music can only be played in a server.");
            return;
        }

        Session existing = sessions.get(guild.getIdLong());
        if(existing != null && !existing.isFinished())
        {
            event.replyErrorEphemeral("A guess the music game is already active in this server.");
            return;
        }

        Options cleanOptions = options == null ? Options.defaults() : options.sanitize();
        Session session = new Session(guild.getIdLong(), event.getChannel().getIdLong(), event.getAuthor().getIdLong(), cleanOptions);
        session.ensurePlayer(event.getAuthor());
        sessions.put(guild.getIdLong(), session);
        event.reply(session.lobbyMessage(guild), message -> session.setLobbyMessage(message.getIdLong()));
    }

    public void status(CommandContext event)
    {
        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished())
        {
            event.replyWarning("There is no active guess the music game.");
            return;
        }
        event.reply(session.statusMessage());
    }

    public void stop(CommandContext event)
    {
        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished())
        {
            event.replyWarning("There is no active guess the music game to stop.");
            return;
        }
        if(!session.canControl(event.getMember(), event.getAuthor()))
        {
            event.replyErrorEphemeral("Only the host, DJs, Manage Server members, or the bot owner can stop the game.");
            return;
        }
        event.replySuccess("Stopping guess the music.");
        session.finish("Game stopped by " + event.getAuthor().getAsMention() + ".");
    }

    public void reveal(CommandContext event)
    {
        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished())
        {
            event.replyWarning("There is no active round to reveal.");
            return;
        }
        if(!session.canControl(event.getMember(), event.getAuthor()))
        {
            event.replyErrorEphemeral("Only the host, DJs, Manage Server members, or the bot owner can reveal the round.");
            return;
        }
        event.replySuccess("Revealing the current round.");
        session.reveal("Round revealed by " + event.getAuthor().getAsMention() + ".");
    }

    public void join(CommandContext event)
    {
        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished())
        {
            event.replyWarning("There is no active guess the music game to join.");
            return;
        }
        boolean joined = session.ensurePlayer(event.getAuthor());
        if(joined)
            session.refreshLobbyMessage();
        event.replySuccess(joined
                ? "You joined guess the music. Use `/g` when a round is active."
                : "You are already in this guess the music game.");
    }

    public void leave(CommandContext event)
    {
        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished())
        {
            event.replyWarning("There is no active guess the music game to leave.");
            return;
        }
        session.requestLeave(event);
    }

    public void settings(CommandContext event)
    {
        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished())
        {
            event.replyWarning("There is no active guess the music game. Start one with `/guess start` first.");
            return;
        }
        if(!session.canControl(event.getMember(), event.getAuthor()))
        {
            event.replyErrorEphemeral("Only the host, DJs, Manage Server members, or the bot owner can change guess settings.");
            return;
        }
        event.reply(session.settingsMessage());
    }

    public void setHints(CommandContext event, Boolean enabled, Integer intervalSeconds, Integer stepSeconds,
                         Integer hintReplays)
    {
        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished())
        {
            event.replyWarning("There is no active guess the music game.");
            return;
        }
        if(!session.canControl(event.getMember(), event.getAuthor()))
        {
            event.replyErrorEphemeral("Only the host, DJs, Manage Server members, or the bot owner can configure hints.");
            return;
        }

        session.configureHints(enabled, intervalSeconds, stepSeconds, hintReplays);
        event.replySuccess("Guess the music hints are now " + session.hintSettingsSummary() + ".");
    }

    public void setHighlight(CommandContext event, String timestamp)
    {
        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished())
        {
            event.replyWarning("There is no active guess the music game.");
            return;
        }
        if(!session.canControl(event.getMember(), event.getAuthor()))
        {
            event.replyErrorEphemeral("Only the host, DJs, Manage Server members, or the bot owner can set the reveal highlight.");
            return;
        }

        session.setManualHighlight(event, timestamp);
    }

    public void onVoiceAvailabilityChanged(Guild guild, boolean alone)
    {
        if(guild == null)
            return;
        Session session = sessions.get(guild.getIdLong());
        if(session == null || session.isFinished())
            return;
        if(alone)
            session.pauseForNoListeners();
        else
            session.resumeForListeners();
    }

    public void onVoiceTimeout(Guild guild)
    {
        if(guild == null)
            return;
        Session session = sessions.get(guild.getIdLong());
        if(session == null || session.isFinished())
            return;
        session.finish("Game ended because nobody was listening in voice.");
    }

    public void guess(SlashCommandInteractionEvent event, String answer)
    {
        if(event.getGuild() == null)
        {
            event.reply("Guess the music can only be played in a server.").setEphemeral(true).queue();
            return;
        }
        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished())
        {
            event.reply(bot.getConfig().getWarning() + " There is no active guess the music game here.")
                    .setEphemeral(true).queue();
            return;
        }
        session.submitGuess(event.getUser(), event.getMember(), event.getChannel().getIdLong(), answer,
                response -> event.reply(response).setEphemeral(true).queue());
    }

    public void idk(SlashCommandInteractionEvent event)
    {
        if(event.getGuild() == null)
        {
            event.reply("Guess the music can only be played in a server.").setEphemeral(true).queue();
            return;
        }
        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished())
        {
            event.reply(bot.getConfig().getWarning() + " There is no active guess the music game here.")
                    .setEphemeral(true).queue();
            return;
        }
        session.pass(event.getUser(), event.getChannel().getIdLong(),
                response -> event.reply(response).setEphemeral(true).queue());
    }

    public boolean isActive(Guild guild)
    {
        if(guild == null)
            return false;
        Session session = sessions.get(guild.getIdLong());
        return session != null && !session.isFinished();
    }

    public String activeGameBlockMessage()
    {
        return bot.getConfig().getWarning()
                + " A guess the music game is active, so I can't play music right now.";
    }

    /** True while a started, un-paused HOSTED game is running in this guild (listeners earn listening XP). */
    public boolean isHostedListeningActive(Guild guild)
    {
        if(guild == null)
            return false;
        Session session = sessions.get(guild.getIdLong());
        return session != null && session.isHostedListening();
    }

    /** Reads only the options a hosted game exposes; defaults to an endless game the host controls. */
    public Options hostOptionsFromSlash(SlashCommandInteractionEvent event)
    {
        Options options = Options.defaults();
        options.hosted = true;
        options.winMode = event.getOption("win") == null ? WinMode.ENDLESS : WinMode.parse(stringOption(event, "win"));
        options.mode = GuessMode.parse(stringOption(event, "mode"));
        options.inputMode = InputMode.parse(stringOption(event, "input"));
        options.matchMode = MatchMode.parse(stringOption(event, "match"));
        options.clipPosition = ClipPosition.parse(stringOption(event, "clip_position"));
        options.clipSeconds = intOption(event, "seconds", options.clipSeconds);
        options.roundTimeSeconds = intOption(event, "timeout", options.roundTimeSeconds);
        options.guessesPerUser = intOption(event, "guesses", options.guessesPerUser);
        options.winnersPerRound = intOption(event, "winners", options.winnersPerRound);
        options.hintsEnabled = boolOption(event, "hints", options.hintsEnabled);
        options.hostIdleSeconds = intOption(event, "idle", options.hostIdleSeconds);
        options.rounds = intOption(event, "rounds", options.rounds);
        options.targetPoints = intOption(event, "points", options.targetPoints);
        options.playlistName = stringOption(event, "playlist");
        return options.sanitize();
    }

    public Options hostOptionsFromPrefix(String args)
    {
        // optionsFromPrefix already honors win-mode tokens (bare `points`/`rounds`/`endless` and the
        // `points=`/`rounds=` forms); only parse the host-specific extras here and default to an endless
        // game (the host ends it) when the host gave no win-mode token at all.
        Options options = optionsFromPrefix(args == null ? "" : args);
        options.hosted = true;
        boolean winSpecified = false;
        if(args != null && !args.isBlank())
        {
            for(String token : args.trim().split("\\s+"))
            {
                String[] parts = token.split("=", 2);
                String key = parts[0].toLowerCase(Locale.ROOT);
                String value = parts.length == 2 ? parts[1] : "";
                switch(key)
                {
                    case "points":
                    case "rounds":
                    case "endless":
                        winSpecified = true;
                        break;
                    case "idle":
                    case "idletimeout":
                        if(parts.length == 2)
                            options.hostIdleSeconds = parseInt(value, options.hostIdleSeconds);
                        break;
                    case "playlist":
                        if(parts.length == 2)
                            options.playlistName = value.replace('_', ' ');
                        break;
                    default:
                        break;
                }
            }
        }
        if(!winSpecified)
            options.winMode = WinMode.ENDLESS;
        return options.sanitize();
    }

    /** Opens the private add-songs modal directly from a slash interaction (used by {@code /hostgame add}). */
    public void openHostAddModal(SlashCommandInteractionEvent event)
    {
        Guild guild = event.getGuild();
        Session session = guild == null ? null : sessions.get(guild.getIdLong());
        if(session == null || session.isFinished())
        {
            event.reply(bot.getConfig().getWarning() + " There is no active hosted guess the music game.")
                    .setEphemeral(true).queue();
            return;
        }
        if(!session.isHosted())
        {
            event.reply(bot.getConfig().getWarning() + " This is not a hosted game, so songs can't be added.")
                    .setEphemeral(true).queue();
            return;
        }
        if(!session.canControl(event.getMember(), event.getUser()))
        {
            event.reply(bot.getConfig().getError() + " Only the host can add songs to this game.")
                    .setEphemeral(true).queue();
            return;
        }
        event.replyModal(hostAddModal()).queue();
    }

    /** Posts a host-only button that opens the private add-songs modal (used by the prefix command). */
    public void promptHostAdd(CommandContext event)
    {
        Guild guild = event.getGuild();
        Session session = guild == null ? null : sessions.get(guild.getIdLong());
        if(session == null || session.isFinished())
        {
            event.replyWarning("There is no active hosted guess the music game.");
            return;
        }
        if(!session.isHosted())
        {
            event.replyWarning("This is not a hosted game, so songs can't be added.");
            return;
        }
        if(!session.canControl(event.getMember(), event.getAuthor()))
        {
            event.replyErrorEphemeral("Only the host can add songs to this game.");
            return;
        }
        event.reply(new MessageCreateBuilder()
                .setContent(bot.getConfig().getSuccess() + " Tap to add songs privately — only you will see them:")
                .setComponents(ActionRow.of(Button.secondary(ADD_SONG_BUTTON, "➕ Add song")))
                .build());
    }

    /**
     * Resolves each host-supplied query (off-thread via the player manager) and appends the playable
     * ones to the active hosted game's private queue, then replies once with a summary. The song text
     * is never echoed publicly — callers reply ephemerally.
     */
    public void addHostSongs(Guild guild, User user, Member member, List<String> queries,
                             java.util.function.Consumer<String> reply)
    {
        Session session = guild == null ? null : sessions.get(guild.getIdLong());
        if(session == null || session.isFinished())
        {
            reply.accept(bot.getConfig().getWarning() + " There is no active hosted guess the music game.");
            return;
        }
        if(!session.isHosted())
        {
            reply.accept(bot.getConfig().getWarning() + " This is not a hosted game, so songs can't be added.");
            return;
        }
        if(!session.canControl(member, user))
        {
            reply.accept(bot.getConfig().getError() + " Only the host can add songs to this game.");
            return;
        }
        List<String> cleaned = queries.stream()
                .map(query -> query == null ? "" : query.trim())
                .filter(query -> !query.isEmpty())
                .distinct()
                .limit(MAX_HOST_SONGS_PER_ADD)
                .collect(Collectors.toList());
        if(cleaned.isEmpty())
        {
            reply.accept(bot.getConfig().getError() + " Add at least one song — a title or URL per line.");
            return;
        }
        new HostSongLoader(session, cleaned, reply).start();
    }

    private Modal hostAddModal()
    {
        TextInput input = TextInput.create(ADD_SONG_INPUT, TextInputStyle.PARAGRAPH)
                .setPlaceholder("One song per line — a title or a URL")
                .setRequiredRange(1, 1000)
                .build();
        return Modal.create(ADD_SONG_MODAL, "Add Songs (private)")
                .addComponents(Label.of("Songs (one per line)", input))
                .build();
    }

    private TrackReference hostReferenceFor(String query, String title, String author)
    {
        if(query == null || query.isBlank())
            return null;
        ParsedTitle parsed = GuessMusicTitleMatcher.parse(title, author);
        List<String> queries = new ArrayList<>();
        queries.add(query.trim());
        // Fall back to title/artist searches if the exact identifier can't be reloaded at round time.
        for(String alternative : preferredAudioQueries(parsed, null))
            if(!queries.contains(alternative))
                queries.add(alternative);
        String refTitle = parsed.getTitle() == null || parsed.getTitle().isBlank() ? null : parsed.getTitle();
        return new TrackReference(queries, refTitle, parsed.getArtist(), false, "host", true);
    }

    // Loads a batch of host queries concurrently (off the event thread) and replies once all resolve.
    // Each query gets its own load handler so the success/failure summary stays correctly associated.
    private final class HostSongLoader
    {
        private final Session session;
        private final List<String> queries;
        private final java.util.function.Consumer<String> reply;
        private final AtomicInteger remaining;
        private final AtomicInteger succeeded = new AtomicInteger();
        private final List<String> failed = Collections.synchronizedList(new ArrayList<>());

        private HostSongLoader(Session session, List<String> queries, java.util.function.Consumer<String> reply)
        {
            this.session = session;
            this.queries = queries;
            this.reply = reply;
            this.remaining = new AtomicInteger(queries.size());
        }

        private void start()
        {
            for(String query : queries)
                load(query);
        }

        private void load(String query)
        {
            bot.getPlayerManager().loadItemOrdered("hostguess-" + session.guildId, query, new AudioLoadResultHandler()
            {
                @Override
                public void trackLoaded(AudioTrack track)
                {
                    accept(track, query);
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist)
                {
                    AudioTrack pick = playlist.getSelectedTrack();
                    if(pick == null && !playlist.getTracks().isEmpty())
                        pick = playlist.getTracks().get(0);
                    accept(pick, query);
                }

                @Override
                public void noMatches()
                {
                    accept(null, query);
                }

                @Override
                public void loadFailed(FriendlyException throwable)
                {
                    LOG.debug("Hosted guess music could not load '{}': {}", query, throwable.getMessage());
                    accept(null, query);
                }
            });
        }

        private void accept(AudioTrack track, String query)
        {
            boolean ok = false;
            if(track != null && track.getInfo() != null && !track.getInfo().isStream && !bot.getConfig().isTooLong(track))
            {
                TrackReference reference = hostReferenceFor(track.getInfo().uri, track.getInfo().title,
                        track.getInfo().author);
                ok = reference != null && session.enqueueHostSong(reference);
            }
            if(ok)
                succeeded.incrementAndGet();
            else
                failed.add(query);
            finishOne();
        }

        private void finishOne()
        {
            if(remaining.decrementAndGet() > 0)
                return;
            int ok = succeeded.get();
            StringBuilder summary = new StringBuilder();
            if(ok > 0)
                summary.append(bot.getConfig().getSuccess()).append(" Added `").append(ok)
                        .append("` song").append(ok == 1 ? "" : "s").append(" privately to the game.");
            else
                summary.append(bot.getConfig().getWarning()).append(" No songs could be added.");
            if(!failed.isEmpty())
                summary.append(" Couldn't find: ").append(failed.stream().limit(5)
                        .map(query -> "`" + query + "`").collect(Collectors.joining(", ")));
            reply.accept(summary.toString());
        }
    }

    public boolean handleButtonInteraction(ButtonInteractionEvent event)
    {
        String componentId = event.getComponentId();
        if(!componentId.startsWith("jmb-guess:"))
            return false;
        if(event.getGuild() == null)
        {
            event.reply(bot.getConfig().getError() + " Guess the music can only be used in a server.")
                    .setEphemeral(true).queue();
            return true;
        }

        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished())
        {
            event.reply(bot.getConfig().getWarning() + " This guess the music game is no longer active.")
                    .setEphemeral(true).queue();
            return true;
        }

        if(componentId.startsWith(SETTINGS_PREFIX))
        {
            session.handleSettingsButton(event, componentId.substring(SETTINGS_PREFIX.length()));
            return true;
        }
        if(componentId.startsWith(LEAVE_CONFIRM_PREFIX) || componentId.startsWith(LEAVE_CANCEL_PREFIX))
        {
            session.handleLeaveConfirmation(event);
            return true;
        }

        switch(componentId)
        {
            case START_BUTTON:
                session.confirmStart(event);
                return true;
            case JOIN_BUTTON:
                session.joinFromButton(event);
                return true;
            case CANCEL_BUTTON:
                if(!session.canControl(event.getMember(), event.getUser()))
                {
                    event.reply(bot.getConfig().getError() + " Only the host can cancel this lobby.")
                            .setEphemeral(true).queue();
                    return true;
                }
                event.editMessage(bot.getConfig().getWarning() + " Guess the music lobby cancelled.")
                        .setComponents(Collections.emptyList()).queue();
                session.finishWithoutRestore();
                return true;
            case GUESS_BUTTON:
                session.ensurePlayer(event.getUser());
                openGuessModal(event);
                return true;
            case ADD_SONG_BUTTON:
                if(!session.isHosted())
                {
                    event.reply(bot.getConfig().getWarning() + " This isn't a hosted game.").setEphemeral(true).queue();
                    return true;
                }
                if(!session.canControl(event.getMember(), event.getUser()))
                {
                    event.reply(bot.getConfig().getError() + " Only the host can add songs to this game.")
                            .setEphemeral(true).queue();
                    return true;
                }
                event.replyModal(hostAddModal()).queue();
                return true;
            case IDK_BUTTON:
                session.pass(event.getUser(), event.getChannel().getIdLong(),
                        response -> event.reply(response).setEphemeral(true).queue());
                return true;
            case REPLAY_BUTTON:
                if(!session.canControl(event.getMember(), event.getUser()))
                {
                    event.reply(bot.getConfig().getError() + " Only the host, DJs, Manage Server members, or the bot owner can replay the clip.")
                            .setEphemeral(true).queue();
                    return true;
                }
                event.editMessage(MessageEditData.fromCreateData(session.replayNow())).queue();
                return true;
            case CLIP_LONGER_BUTTON:
                if(!session.canControl(event.getMember(), event.getUser()))
                {
                    event.reply(bot.getConfig().getError() + " Only the host, DJs, Manage Server members, or the bot owner can change the clip length.")
                            .setEphemeral(true).queue();
                    return true;
                }
                event.editMessage(MessageEditData.fromCreateData(session.adjustClipLength(true))).queue();
                return true;
            case CLIP_SHORTER_BUTTON:
                if(!session.canControl(event.getMember(), event.getUser()))
                {
                    event.reply(bot.getConfig().getError() + " Only the host, DJs, Manage Server members, or the bot owner can change the clip length.")
                            .setEphemeral(true).queue();
                    return true;
                }
                event.editMessage(MessageEditData.fromCreateData(session.adjustClipLength(false))).queue();
                return true;
            case REVEAL_BUTTON:
                if(!session.canControl(event.getMember(), event.getUser()))
                {
                    event.reply(bot.getConfig().getError() + " Only the host, DJs, Manage Server members, or the bot owner can reveal.")
                            .setEphemeral(true).queue();
                    return true;
                }
                event.deferEdit().queue();
                session.reveal("Round revealed by " + event.getUser().getAsMention() + ".");
                return true;
            case STOP_BUTTON:
                if(!session.canControl(event.getMember(), event.getUser()))
                {
                    event.reply(bot.getConfig().getError() + " Only the host, DJs, Manage Server members, or the bot owner can stop.")
                            .setEphemeral(true).queue();
                    return true;
                }
                event.deferEdit().queue();
                session.finish("Game stopped by " + event.getUser().getAsMention() + ".");
                return true;
            default:
                event.deferEdit().queue();
                return true;
        }
    }

    public boolean handleModalInteraction(ModalInteractionEvent event)
    {
        String modalId = event.getModalId();
        if(GUESS_MODAL.equals(modalId))
            return handleGuessModal(event);
        if(ADD_SONG_MODAL.equals(modalId))
            return handleHostAddModal(event);
        return false;
    }

    private boolean handleGuessModal(ModalInteractionEvent event)
    {
        if(event.getGuild() == null)
        {
            event.reply(bot.getConfig().getError() + " Guess the music can only be used in a server.")
                    .setEphemeral(true).queue();
            return true;
        }

        ModalMapping value = event.getValue(GUESS_INPUT);
        String answer = value == null ? "" : value.getAsString();
        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished())
        {
            event.reply(bot.getConfig().getWarning() + " There is no active guess the music game here.")
                    .setEphemeral(true).queue();
            return true;
        }
        session.submitGuess(event.getUser(), event.getMember(), event.getChannel().getIdLong(), answer,
                response -> event.reply(response).setEphemeral(true).queue());
        return true;
    }

    private boolean handleHostAddModal(ModalInteractionEvent event)
    {
        if(event.getGuild() == null)
        {
            event.reply(bot.getConfig().getError() + " Guess the music can only be used in a server.")
                    .setEphemeral(true).queue();
            return true;
        }
        ModalMapping value = event.getValue(ADD_SONG_INPUT);
        String raw = value == null ? "" : value.getAsString();
        List<String> lines = Arrays.stream(raw.split("\\r?\\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());
        Guild guild = event.getGuild();
        User user = event.getUser();
        Member member = event.getMember();
        // The host's song text never hits the channel: the modal input is private and the reply is ephemeral.
        event.deferReply(true).queue(
                hook -> addHostSongs(guild, user, member, lines, response -> hook.editOriginal(response).queue()),
                failure -> LOG.debug("Failed to defer hosted add-song modal", failure));
        return true;
    }

    public boolean handleMessageReceived(MessageReceivedEvent event)
    {
        if(!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage())
            return false;
        Session session = sessions.get(event.getGuild().getIdLong());
        if(session == null || session.isFinished() || !session.acceptsChatGuesses(event.getChannel().getIdLong()))
            return false;

        String raw = event.getMessage().getContentRaw();
        if(raw == null || raw.isBlank())
            return false;
        session.submitGuess(event.getAuthor(), event.getMember(), event.getChannel().getIdLong(), raw,
                response -> event.getChannel().sendMessage(response).queue());
        return true;
    }

    public Options optionsFromSlash(SlashCommandInteractionEvent event)
    {
        Options options = Options.defaults();
        options.mode = GuessMode.parse(stringOption(event, "mode"));
        options.winMode = WinMode.parse(stringOption(event, "win"));
        options.inputMode = InputMode.parse(stringOption(event, "input"));
        options.matchMode = MatchMode.parse(stringOption(event, "match"));
        options.clipPosition = ClipPosition.parse(stringOption(event, "clip_position"));
        options.rounds = intOption(event, "rounds", options.rounds);
        options.targetPoints = intOption(event, "points", options.targetPoints);
        options.clipSeconds = intOption(event, "seconds", options.clipSeconds);
        options.roundTimeSeconds = intOption(event, "timeout", intOption(event, "time", options.roundTimeSeconds));
        options.guessesPerUser = intOption(event, "guesses", options.guessesPerUser);
        options.winnersPerRound = intOption(event, "winners", options.winnersPerRound);
        options.knownPercent = intOption(event, "known_percent", options.knownPercent);
        options.hintsEnabled = boolOption(event, "hints", options.hintsEnabled);
        options.hintIntervalSeconds = intOption(event, "hint_interval", options.hintIntervalSeconds);
        options.hintStepSeconds = intOption(event, "hint_seconds", options.hintStepSeconds);
        options.hintReplays = intOption(event, "hint_replays", options.hintReplays);
        options.replayIntervalSeconds = intOption(event, "replay_interval", options.replayIntervalSeconds);
        options.finalGuessSeconds = intOption(event, "buffer", options.finalGuessSeconds);
        options.playlistName = stringOption(event, "playlist");
        options.artistName = stringOption(event, "artist");
        return options.sanitize();
    }

    public Options optionsFromPrefix(String args)
    {
        Options options = Options.defaults();
        if(args == null || args.isBlank())
            return options;

        String[] tokens = args.trim().split("\\s+");
        for(String token : tokens)
        {
            String[] parts = token.split("=", 2);
            String key = parts[0].toLowerCase(Locale.ROOT);
            String value = parts.length == 2 ? parts[1] : parts[0];
            switch(key)
            {
                case "classic":
                case "hardcore":
                case "impossible":
                    options.mode = GuessMode.parse(key);
                    break;
                case "rounds":
                    options.rounds = parseInt(value, options.rounds);
                    break;
                case "points":
                    options.winMode = WinMode.POINTS;
                    options.targetPoints = parseInt(value, options.targetPoints);
                    break;
                case "endless":
                    options.winMode = WinMode.ENDLESS;
                    break;
                case "seconds":
                    options.clipSeconds = parseInt(value, options.clipSeconds);
                    options.mode = GuessMode.CUSTOM;
                    break;
                case "timeout":
                case "time":
                    options.roundTimeSeconds = "auto".equalsIgnoreCase(value) ? 0 : parseInt(value, options.roundTimeSeconds);
                    break;
                case "input":
                    options.inputMode = InputMode.parse(value);
                    break;
                case "match":
                    options.matchMode = MatchMode.parse(value);
                    break;
                case "strict":
                    options.matchMode = MatchMode.STRICT;
                    break;
                case "forgiving":
                    options.matchMode = MatchMode.FORGIVING;
                    break;
                case "clip":
                case "position":
                case "clip_position":
                    options.clipPosition = ClipPosition.parse(value);
                    break;
                case "intro":
                case "outro":
                case "random":
                case "first_audible":
                case "firstaudible":
                    options.clipPosition = ClipPosition.parse(key);
                    break;
                case "known":
                case "known_percent":
                    options.knownPercent = parseInt(value, options.knownPercent);
                    break;
                case "guesses":
                case "guesslimit":
                case "guess_limit":
                case "attempts":
                    options.guessesPerUser = parseGuessLimit(value, options.guessesPerUser);
                    break;
                case "hints":
                    options.hintsEnabled = parseBoolean(value, options.hintsEnabled);
                    break;
                case "nohints":
                    options.hintsEnabled = false;
                    break;
                case "hintinterval":
                case "hint_interval":
                case "interval":
                    options.hintIntervalSeconds = parseInt(value, options.hintIntervalSeconds);
                    break;
                case "hintseconds":
                case "hint_seconds":
                case "hintstep":
                    options.hintStepSeconds = parseInt(value, options.hintStepSeconds);
                    break;
                case "hintreplays":
                case "hint_replays":
                case "replays":
                    options.hintReplays = parseInt(value, options.hintReplays);
                    break;
                case "replayinterval":
                case "replay_interval":
                case "playagain":
                    options.replayIntervalSeconds = parseInt(value, options.replayIntervalSeconds);
                    break;
                case "buffer":
                case "final":
                case "finalguess":
                    options.finalGuessSeconds = parseInt(value, options.finalGuessSeconds);
                    break;
                case "artist":
                case "artist_mode":
                case "artists":
                    options.artistName = value.replace('_', ' ');
                    break;
                default:
                    break;
            }
        }
        return options.sanitize();
    }

    private MessageCreateData buildLobbyMessage(Guild guild, long hostId, Options options, int playerCount)
    {
        String description = options.hosted
                ? "The host privately picks every song. Current music pauses while the game runs and resumes afterward."
                : "Current music will pause while the game runs and resume afterward.";
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(guild.getSelfMember().getColor())
                .setTitle(options.hosted ? "Guess The Music — Hosted" : "Guess The Music")
                .setDescription(description)
                .addField("Host", "<@" + hostId + ">", true)
                .addField("Players", "`" + Math.max(1, playerCount) + "` joined", true)
                .addField("Mode", options.mode.displayName + " | " + options.winMode.displayName, true)
                .addField("Guessing", options.inputMode.displayName + " | use `/g` | " + options.guessLimitSummary(), false)
                .addField("Rounds", options.roundSummary(), true)
                .addField("Clip", options.clipSummary(), true)
                .addField("Hints", options.hintSummary(), true)
                .addField("Pool", options.poolSummary(), false);
        ActionRow controls = options.hosted
                ? ActionRow.of(
                        Button.success(START_BUTTON, "Start"),
                        Button.primary(JOIN_BUTTON, "Join"),
                        Button.secondary(ADD_SONG_BUTTON, "➕ Add song"),
                        Button.secondary(CANCEL_BUTTON, "Cancel"))
                : ActionRow.of(
                        Button.success(START_BUTTON, "Start"),
                        Button.primary(JOIN_BUTTON, "Join"),
                        Button.secondary(CANCEL_BUTTON, "Cancel"));
        return new MessageCreateBuilder()
                .setEmbeds(embed.build())
                .setComponents(controls)
                .build();
    }

    private void openGuessModal(ButtonInteractionEvent event)
    {
        TextInput input = TextInput.create(GUESS_INPUT, TextInputStyle.SHORT)
                .setPlaceholder("Song title")
                .setRequiredRange(1, 100)
                .build();
        Modal modal = Modal.create(GUESS_MODAL, "Guess The Song")
                .addComponents(Label.of("Your guess", input))
                .build();
        event.replyModal(modal).queue();
    }

    private List<TrackReference> buildKnownReferences(Guild guild, long hostId, AudioHandler handler, Options options)
    {
        LinkedHashMap<String, TrackReference> refs = new LinkedHashMap<>();
        String artistFilter = options.artistName;

        AudioTrack current = handler.getPlayer().getPlayingTrack();
        addTrackReference(refs, current, "current", artistFilter);
        handler.getQueue().getList().forEach(qt -> addTrackReference(refs, qt.getTrack(), "queue", artistFilter));

        PlaybackHistoryStore playbackHistory = bot.getPlaybackHistoryStore();
        if(playbackHistory != null)
        {
            try
            {
                for(PlaybackHistoryStore.Entry entry : playbackHistory.list(guild.getIdLong(), 0, 100))
                    if(!entry.isStream())
                        addStoredReference(refs, entry.getUri(), entry.getTitle(), entry.getAuthor(), entry.getDuration(),
                                "server history", artistFilter);
            }
            catch(RuntimeException ex)
            {
                LOG.warn("Failed to read playback history for guess music in guild {}", guild.getId(), ex);
            }
        }

        try
        {
            for(PlaylistSummary playlist : bot.getUserPlaylistService().listPlaylists(hostId))
            {
                if(options.playlistName != null && !options.playlistName.isBlank()
                        && !playlist.getName().equalsIgnoreCase(options.playlistName))
                    continue;
                for(PlaylistTrack item : bot.getUserPlaylistService().listItems(playlist.getId()))
                    addStoredReference(refs, item.getLoadQuery(), item.getTitle(), item.getAuthor(), item.getDuration(),
                            "playlist:" + playlist.getName(), artistFilter);
                if(refs.size() >= MAX_REFERENCES)
                    break;
            }
        }
        catch(PlaylistException ex)
        {
            LOG.debug("Failed to read host playlists for guess music in guild {}: {}", guild.getId(), ex.getMessage());
        }

        return refs.values().stream()
                .limit(MAX_REFERENCES)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<String> discoverySeedArtists(List<TrackReference> known, Options options)
    {
        if(options.hasArtistFilter())
            return new ArrayList<>(options.artistFilters());

        List<String> artists = known.stream()
                .map(ref -> GuessMusicTitleMatcher.cleanArtist(ref.author))
                .filter(artist -> artist != null && !artist.isBlank())
                .distinct()
                .limit(40)
                .collect(Collectors.toList());
        Collections.shuffle(artists, random);
        return artists;
    }

    private List<TrackReference> buildDiscoveryReferences(List<String> seedArtists, Options options)
    {
        LinkedHashMap<String, TrackReference> refs = new LinkedHashMap<>();
        boolean artistFilter = options.hasArtistFilter();
        for(String artist : seedArtists)
        {
            if(artistFilter)
            {
                addArtistDiscoveryReferences(refs, artist);
            }
            else
            {
                addDiscoveryReference(refs, artist + " lyric video", artist);
                addDiscoveryReference(refs, artist + " official audio", artist);
                addDiscoveryReference(refs, artist + " topic", artist);
            }
        }
        return new ArrayList<>(refs.values());
    }

    private void addArtistDiscoveryReferences(Map<String, TrackReference> refs, String artist)
    {
        addDiscoveryReference(refs, artist + " official audio", artist);
        addDiscoveryReference(refs, artist + " official lyric video", artist);
        addDiscoveryReference(refs, artist + " lyric video", artist);
        addDiscoveryReference(refs, artist + " lyrics", artist);
        addDiscoveryReference(refs, artist + " topic", artist);
        addDiscoveryReference(refs, artist + " vevo", artist);
        addDiscoveryReference(refs, artist + " songs official audio", artist);
        addDiscoveryReference(refs, artist + " top tracks", artist);
        addDiscoveryReference(refs, artist + " album audio", artist);
        addDiscoveryReference(refs, artist + " official visualizer", artist);
        addDiscoveryReference(refs, artist + " romanized title official audio", artist);
        addDiscoveryReference(refs, artist + " english title official audio", artist);
        addDiscoveryReference(refs, artist + " singles official audio", artist);
        addDiscoveryReference(refs, artist + " greatest hits official audio", artist);
        addDiscoveryReference(refs, artist + " older songs official audio", artist);
        addDiscoveryReference(refs, artist + " 2000s official audio", artist);
        addDiscoveryReference(refs, artist + " 2010s official audio", artist);
        addDiscoveryReference(refs, artist + " 2020s official audio", artist);
        addDiscoveryReference(refs, artist + " debut album official audio", artist);
        addDiscoveryReference(refs, artist + " album tracks official audio", artist);
    }

    private void addTrackReference(Map<String, TrackReference> refs, AudioTrack track, String source, String artistFilter)
    {
        if(track == null || track.getInfo() == null || track.getInfo().isStream)
            return;
        addStoredReference(refs, track.getInfo().uri, track.getInfo().title, track.getInfo().author, track.getDuration(),
                source, artistFilter);
    }

    private void addStoredReference(Map<String, TrackReference> refs, String query, String title, String author,
                                    long duration, String source, String artistFilter)
    {
        if(refs.size() >= MAX_REFERENCES || duration == Long.MAX_VALUE)
            return;
        ParsedTitle parsed = GuessMusicTitleMatcher.parse(title, author);
        if(!hasUsableTitle(parsed))
            return;
        if(!matchesArtistFilter(artistFilter, parsed, query))
            return;
        List<String> loadQueries = preferredAudioQueries(parsed, query);
        if(loadQueries.isEmpty())
            return;
        String key = duplicateKey(loadQueries.get(0), parsed.getTitle(), parsed.getArtist());
        refs.putIfAbsent(key, new TrackReference(loadQueries, parsed.getTitle(), parsed.getArtist(), false, source));
    }

    private void addDiscoveryReference(Map<String, TrackReference> refs, String query, String artist)
    {
        String key = GuessMusicTitleMatcher.normalize(query);
        refs.putIfAbsent(key, new TrackReference(Collections.singletonList("ytsearch:" + query), null, artist, true, "discovery"));
    }

    static List<String> preferredAudioQueries(ParsedTitle parsed, String fallbackQuery)
    {
        List<String> queries = new ArrayList<>();
        String base = searchQuery(parsed.getTitle(), parsed.getArtist());
        if(!base.isBlank())
        {
            queries.add("ytsearch:" + base + " official audio");
            queries.add("ytsearch:" + base + " official lyric video");
            queries.add("ytsearch:" + base + " lyric video");
            queries.add("ytsearch:" + base + " lyrics");
            queries.add("ytsearch:" + base + " topic");
        }
        for(String alias : parsed.getAliases())
        {
            String aliasBase = searchQuery(alias, parsed.getArtist());
            if(!aliasBase.isBlank())
            {
                queries.add("ytsearch:" + aliasBase + " official audio");
                queries.add("ytsearch:" + aliasBase + " lyric video");
            }
        }
        if(GuessMusicTitleMatcher.hasNonLatin(parsed.getTitle()) && parsed.getArtist() != null && !parsed.getArtist().isBlank())
        {
            queries.add("ytsearch:" + base + " romanized title");
            queries.add("ytsearch:" + base + " english title");
        }
        if(fallbackQuery != null && !fallbackQuery.isBlank())
        {
            String cleanFallback = fallbackQuery.trim();
            if(!queries.contains(cleanFallback))
                queries.add(cleanFallback);
        }
        return queries.stream().distinct().collect(Collectors.toList());
    }

    static String duplicateKey(String query, String title, String author)
    {
        String songKey = songDuplicateKey(title, author);
        if(songKey != null)
            return songKey;
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    static Set<String> songIdentityKeys(ParsedTitle parsed)
    {
        Set<String> keys = new HashSet<>();
        if(parsed == null)
            return keys;
        for(String normalizedTitle : parsed.getNormalizedTitles())
            if(!normalizedTitle.isEmpty())
                keys.add(normalizedTitle);

        String titleKey = duplicateTitleKey(parsed.getTitle());
        if(!titleKey.isEmpty())
        {
            keys.add("guess:title:" + titleKey);
            String artistKey = compactArtist(parsed.getArtist());
            if(!artistKey.isEmpty())
                keys.add("guess:song:" + artistKey + ":" + titleKey);
        }
        return keys;
    }

    private static boolean hasUsableTitle(ParsedTitle parsed)
    {
        if(parsed == null || parsed.getNormalizedTitle().isEmpty())
            return false;
        return parsed.getNormalizedTitle().length() >= 3 || GuessMusicTitleMatcher.hasNonLatin(parsed.getNormalizedTitle());
    }

    private static Set<String> trackIdentityKeys(AudioTrack track)
    {
        Set<String> keys = new HashSet<>(TrackIdentity.keys(track));
        if(track != null && track.getInfo() != null)
            keys.addAll(songIdentityKeys(GuessMusicTitleMatcher.parse(track.getInfo().title, track.getInfo().author)));
        return keys;
    }

    private static Set<String> trackSongKeys(AudioTrack track)
    {
        if(track == null || track.getInfo() == null)
            return Collections.emptySet();
        return songOnlyKeys(GuessMusicTitleMatcher.parse(track.getInfo().title, track.getInfo().author));
    }

    // Only the artist-qualified "guess:song:<artist>:<title>" keys — never the artist-independent
    // "guess:title:" key, so two different songs that merely share a base title across artists
    // (e.g. "Closer" by different artists) are not wrongly treated as duplicates.
    private static Set<String> songOnlyKeys(ParsedTitle parsed)
    {
        return songIdentityKeys(parsed).stream()
                .filter(key -> key.startsWith("guess:song:"))
                .collect(Collectors.toSet());
    }

    private static boolean containsAny(Set<String> haystack, Set<String> needles)
    {
        if(haystack == null || haystack.isEmpty() || needles == null || needles.isEmpty())
            return false;
        for(String needle : needles)
            if(haystack.contains(needle))
                return true;
        return false;
    }

    private static String songDuplicateKey(String title, String author)
    {
        if(title == null || title.isBlank())
            return null;
        ParsedTitle parsed = GuessMusicTitleMatcher.parse(title, author);
        String titleKey = duplicateTitleKey(parsed.getTitle());
        if(titleKey.isEmpty())
            return null;
        String artistKey = compactArtist(parsed.getArtist());
        return "guess:song:" + artistKey + ":" + titleKey;
    }

    private static String duplicateTitleKey(String title)
    {
        String normalized = GuessMusicTitleMatcher.normalize(title);
        if(normalized.isEmpty())
            return "";

        normalized = stripDuplicateVersionSuffixes(normalized);
        if(normalized.isEmpty())
            normalized = GuessMusicTitleMatcher.normalize(title);
        return normalized.replace(" ", "");
    }

    private static String stripDuplicateVersionSuffixes(String normalized)
    {
        String clean = normalized == null ? "" : normalized.trim();
        clean = clean.replaceAll("\\s+from\\s+.+$", "");
        clean = clean.replaceAll("\\s+(live\\s+(at|from|in|on|version|performance|session)\\b.*)$", "");
        // Speed/pitch/spatial edits: "(Sped Up)", "(Slowed + Reverb)", "(Nightcore)", "(8D Audio)", etc.
        // (normalize() has already turned punctuation such as "+" and "'" into spaces).
        clean = clean.replaceAll("\\s+(sped\\s+up|speed(?:ed)?\\s+up|slowed(?:\\s+(?:down|reverb|and\\s+reverb))?"
                + "|reverb|nightcore|daycore|8d\\s+audio|spatial\\s+audio|chopped(?:\\s+(?:and|n)\\s+screwed)?"
                + "|screwed|tiktok(?:\\s+(?:version|edit))?)\\b.*$", "");
        clean = clean.replaceAll("\\s+(acoustic|piano|demo|radio\\s+edit|video\\s+edit|album\\s+version|single\\s+version"
                + "|clean\\s+version|explicit\\s+version|bonus\\s+track|deluxe\\s+edition|remaster(?:ed)?"
                + "|remix(?:ed)?|revisited|reimagined|re\\s+recorded|rerecorded|anniversary\\s+edition"
                + "|taylor\\s+s\\s+version|instrumental|mix|edit|version)\\b.*$", "");
        return clean.trim().replaceAll("\\s+", " ");
    }

    static boolean matchesArtistFilter(String artistFilter, ParsedTitle parsed, String query)
    {
        List<String> filters = parseArtistFilters(artistFilter);
        if(filters.isEmpty())
            return true;
        return parsed != null && filters.stream().anyMatch(artist -> artistMetadataMatches(artist, parsed.getArtist()));
    }

    static List<String> parseArtistFilters(String artistFilter)
    {
        if(artistFilter == null || artistFilter.isBlank())
            return Collections.emptyList();
        return java.util.Arrays.stream(artistFilter.split("[,;|]"))
                .map(String::trim)
                .map(GuessMusicTitleMatcher::cleanArtist)
                .filter(artist -> artist != null && !artist.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    static int calculateRoundPoints(int playerCount, int rank)
    {
        int players = Math.max(1, playerCount);
        double multiplier;
        if(rank <= 1)
            multiplier = 1.0;
        else if(rank == 2)
            multiplier = 0.75;
        else if(rank == 3)
            multiplier = 0.50;
        else
            multiplier = 0.25;
        int floorPoints = (int)Math.floor(players * multiplier);
        int minimum = Math.max(1, (int)Math.floor(players * 0.25));
        return Math.max(minimum, floorPoints);
    }

    private static String searchQuery(String title, String author)
    {
        StringBuilder builder = new StringBuilder();
        if(author != null && !author.isBlank())
            builder.append(author.trim()).append(' ');
        if(title != null && !title.isBlank())
            builder.append(title.trim());
        return builder.toString().trim();
    }

    private static String stringOption(SlashCommandInteractionEvent event, String name)
    {
        OptionMapping option = event.getOption(name);
        return option == null ? null : option.getAsString();
    }

    private static int intOption(SlashCommandInteractionEvent event, String name, int fallback)
    {
        OptionMapping option = event.getOption(name);
        return option == null ? fallback : (int)option.getAsLong();
    }

    private static boolean boolOption(SlashCommandInteractionEvent event, String name, boolean fallback)
    {
        OptionMapping option = event.getOption(name);
        return option == null ? fallback : option.getAsBoolean();
    }

    private static int parseInt(String value, int fallback)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch(NumberFormatException ex)
        {
            return fallback;
        }
    }

    private static int parseGuessLimit(String value, int fallback)
    {
        if(value == null || value.isBlank())
            return fallback;
        switch(value.toLowerCase(Locale.ROOT))
        {
            case "0":
            case "off":
            case "none":
            case "no":
            case "unlimited":
                return 0;
            default:
                return parseInt(value, fallback);
        }
    }

    private static boolean parseBoolean(String value, boolean fallback)
    {
        if(value == null || value.isBlank())
            return fallback;
        switch(value.toLowerCase(Locale.ROOT))
        {
            case "on":
            case "true":
            case "yes":
            case "1":
            case "enabled":
                return true;
            case "off":
            case "false":
            case "no":
            case "0":
            case "disabled":
                return false;
            default:
                return fallback;
        }
    }

    private final class Session
    {
        private final long guildId;
        private final long channelId;
        private final long hostId;
        private final Options options;
        private final Map<Long, PlayerScore> scores = new HashMap<>();
        private final Set<String> usedKeys = new HashSet<>();
        private final Set<String> failedKeys = new HashSet<>();
        private final Set<String> knownSongKeys = new HashSet<>();
        private int coverSkipsThisRound = 0;
        private PreparedRound preparedRound;     // next round loaded/scanned ahead of time, ready to play
        private boolean awaitingRound = false;   // a round is needed now and will play as soon as it is prepared
        private boolean preparing = false;       // a load/prepare pipeline is currently in flight
        private final List<TrackReference> knownReferences = new ArrayList<>();
        private final List<TrackReference> discoveryReferences = new ArrayList<>();
        // Hosted mode: songs the host privately queued, played in the order they were added (FIFO).
        private final Deque<TrackReference> hostQueue = new ArrayDeque<>();
        private ScheduledFuture<?> hostIdleTimeout;
        private boolean hostWaitActive = false;
        private boolean lobby = true;
        private boolean finished = false;
        private long lobbyMessageId;
        private int roundNumber = 0;
        private Round currentRound;
        private ScheduledFuture<?> roundTimeout;
        private ScheduledFuture<?> snippetStopper;
        private ScheduledFuture<?> hintTask;
        private ScheduledFuture<?> replayTask;
        private ScheduledFuture<?> countdownTask;
        private ScheduledFuture<?> answerStopper;
        private long snippetWindowSequence = 0L;
        private long activeSnippetWindowId = 0L;
        private boolean pausedForNoListeners = false;
        private long roundDeadlineMillis = 0L;
        private long snippetStopAtMillis = 0L;
        private long hintDeadlineMillis = 0L;
        private long replayDeadlineMillis = 0L;
        private long countdownDeadlineMillis = 0L;
        private int lastCountdownSeconds = Integer.MAX_VALUE;
        private long remainingRoundMillis = 0L;
        private long remainingSnippetMillis = 0L;
        private long remainingHintMillis = 0L;
        private long remainingReplayMillis = 0L;
        private long remainingCountdownMillis = 0L;

        private Session(long guildId, long channelId, long hostId, Options options)
        {
            this.guildId = guildId;
            this.channelId = channelId;
            this.hostId = hostId;
            this.options = options;
        }

        private synchronized void setLobbyMessage(long lobbyMessageId)
        {
            this.lobbyMessageId = lobbyMessageId;
        }

        private synchronized boolean isFinished()
        {
            return finished;
        }

        private synchronized MessageCreateData lobbyMessage(Guild guild)
        {
            return buildLobbyMessage(guild, hostId, options, scores.size());
        }

        private synchronized void confirmStart(ButtonInteractionEvent event)
        {
            if(event.getUser().getIdLong() != hostId && !canControl(event.getMember(), event.getUser()))
            {
                event.reply(bot.getConfig().getError() + " Only the host can start this lobby.")
                        .setEphemeral(true).queue();
                return;
            }
            if(!lobby)
            {
                event.reply(bot.getConfig().getWarning() + " This game has already started.")
                        .setEphemeral(true).queue();
                return;
            }

            Guild guild = event.getGuild();
            AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);

            if(options.hosted)
            {
                // The bot never builds a pool for a hosted game: the host feeds every song privately.
                // Songs added during the lobby are already in hostQueue; an optional playlist seeds more.
                seedHostQueueFromPlaylist(guild);
                handler.beginGuessMusicMode();
                lobby = false;
                ensurePlayer(event.getUser());
                String lead = hostQueue.isEmpty()
                        ? " Hosted guess the music is starting. Use **➕ Add song** to add songs privately — the round begins once you add one."
                        : " Hosted guess the music is starting with `" + hostQueue.size() + "` song"
                                + (hostQueue.size() == 1 ? "" : "s") + " queued. Use **➕ Add song** to add more privately.";
                event.editMessage(bot.getConfig().getSuccess() + lead)
                        .setComponents(Collections.emptyList()).queue();
                nextRound("Game started.");
                return;
            }

            knownReferences.clear();
            knownReferences.addAll(buildKnownReferences(guild, hostId, handler, options));
            List<String> seedArtists = discoverySeedArtists(knownReferences, options);
            discoveryReferences.clear();
            discoveryReferences.addAll(buildDiscoveryReferences(seedArtists, options));
            knownSongKeys.clear();
            for(TrackReference known : knownReferences)
                if(known.hasKnownTitle())
                    knownSongKeys.addAll(songOnlyKeys(GuessMusicTitleMatcher.parse(known.title, known.author)));

            if(knownReferences.size() < 2 && discoveryReferences.isEmpty())
            {
                event.editMessage(bot.getConfig().getWarning()
                        + " I could not find enough playable known songs. Add songs to the queue, play some music, or save songs to playlists first.")
                        .setComponents(Collections.emptyList()).queue();
                finishWithoutRestore();
                return;
            }

            handler.beginGuessMusicMode();
            lobby = false;
            ensurePlayer(event.getUser());
            scheduleSpotifyDiscoveryEnrichment(seedArtists);
            event.editMessage(bot.getConfig().getSuccess() + " Guess the music is starting. Use `/g` to guess privately.")
                    .setComponents(Collections.emptyList()).queue();
            nextRound("Game started.");
        }

        // Bootstraps discovery with quick keyword searches, then asynchronously replaces each seed
        // artist's freeform searches with their real Spotify catalogue so discovery rounds stop
        // surfacing fan covers and other artists' songs. Runs on the blocking pool (Spotify HTTP).
        private void scheduleSpotifyDiscoveryEnrichment(List<String> seedArtists)
        {
            if(!spotifyCatalog.isEnabled() || seedArtists.isEmpty())
                return;
            List<String> targets = new ArrayList<>(
                    seedArtists.subList(0, Math.min(seedArtists.size(), SPOTIFY_ENRICH_ARTIST_LIMIT)));
            enrichExecutor.submit(() -> enrichDiscoveryFromSpotify(targets));
        }

        private void enrichDiscoveryFromSpotify(List<String> artists)
        {
            for(String artist : artists)
            {
                if(isFinished() || Thread.currentThread().isInterrupted())
                    return;
                List<SpotifyArtistCatalog.Song> songs = spotifyCatalog.originalSongs(artist);
                if(songs.isEmpty())
                    continue;

                LinkedHashMap<String, TrackReference> titled = new LinkedHashMap<>();
                for(SpotifyArtistCatalog.Song song : songs)
                {
                    ParsedTitle parsed = GuessMusicTitleMatcher.parse(song.getTitle(), song.getArtist());
                    if(!hasUsableTitle(parsed))
                        continue;
                    List<String> queries = preferredAudioQueries(parsed, null);
                    if(queries.isEmpty())
                        continue;
                    String key = duplicateKey(queries.get(0), parsed.getTitle(), parsed.getArtist());
                    titled.putIfAbsent(key, new TrackReference(queries, parsed.getTitle(), parsed.getArtist(),
                            true, "spotify"));
                }
                if(!titled.isEmpty())
                    mergeSpotifyDiscovery(artist, new ArrayList<>(titled.values()));
            }
        }

        private synchronized void mergeSpotifyDiscovery(String artist, List<TrackReference> titled)
        {
            if(finished)
                return;
            Set<String> existing = discoveryReferences.stream()
                    .map(TrackReference::normalizedKey)
                    .collect(Collectors.toSet());
            List<TrackReference> toAdd = titled.stream()
                    .filter(ref -> !existing.contains(ref.normalizedKey())
                            && !usedKeys.contains(ref.normalizedKey())
                            && Collections.disjoint(knownSongKeys, songOnlyKeys(
                                    GuessMusicTitleMatcher.parse(ref.title, ref.author))))
                    .collect(Collectors.toList());
            // Only drop this artist's freeform keyword refs if real catalogue refs survive to replace
            // them — otherwise the artist would be left with no discovery coverage at all.
            if(toAdd.isEmpty())
                return;
            String normArtist = GuessMusicTitleMatcher.normalize(GuessMusicTitleMatcher.cleanArtist(artist));
            discoveryReferences.removeIf(ref -> !ref.hasKnownTitle() && normArtist.equals(
                    GuessMusicTitleMatcher.normalize(GuessMusicTitleMatcher.cleanArtist(ref.author))));
            discoveryReferences.addAll(toAdd);
        }

        private synchronized boolean ensurePlayer(User user)
        {
            boolean joined = !scores.containsKey(user.getIdLong());
            scores.computeIfAbsent(user.getIdLong(), id -> new PlayerScore(user.getIdLong(), user.getName()));
            if(joined && !lobby)
                refreshRoundMessage();
            return joined;
        }

        private synchronized void joinFromButton(ButtonInteractionEvent event)
        {
            boolean joined = ensurePlayer(event.getUser());
            event.reply(joined
                            ? bot.getConfig().getSuccess() + " You joined guess the music. Use `/g` when a round is active."
                            : bot.getConfig().getWarning() + " You are already in this guess the music game.")
                    .setEphemeral(true).queue();
            if(joined)
                refreshLobbyMessage();
        }

        private synchronized void requestLeave(CommandContext event)
        {
            if(!scores.containsKey(event.getAuthor().getIdLong()))
            {
                event.replyWarning("You are not in this guess the music game.");
                return;
            }

            event.reply(leaveConfirmationMessage(event.getAuthor()));
        }

        private synchronized MessageCreateData leaveConfirmationMessage(User user)
        {
            String confirmId = LEAVE_CONFIRM_PREFIX + guildId + ":" + user.getIdLong();
            String cancelId = LEAVE_CANCEL_PREFIX + guildId + ":" + user.getIdLong();
            return new MessageCreateBuilder()
                    .setContent(bot.getConfig().getWarning()
                            + " Leave guess the music? This removes you from the leaderboard.")
                    .setComponents(ActionRow.of(
                            Button.danger(confirmId, "Leave"),
                            Button.secondary(cancelId, "Cancel")))
                    .build();
        }

        private synchronized void handleLeaveConfirmation(ButtonInteractionEvent event)
        {
            if(!event.getComponentId().endsWith(":" + event.getUser().getId()))
            {
                event.reply(bot.getConfig().getError() + " This leave confirmation is not for you.")
                        .setEphemeral(true).queue();
                return;
            }

            if(event.getComponentId().startsWith(LEAVE_CANCEL_PREFIX))
            {
                event.editMessage(bot.getConfig().getWarning() + " Leave cancelled.")
                        .setComponents(Collections.emptyList()).queue();
                return;
            }

            if(!removePlayer(event.getUser()))
            {
                event.editMessage(bot.getConfig().getWarning() + " You are not in this guess the music game.")
                        .setComponents(Collections.emptyList()).queue();
                return;
            }

            event.editMessage(bot.getConfig().getSuccess() + " You left guess the music.")
                    .setComponents(Collections.emptyList()).queue();

            if(scores.isEmpty())
            {
                if(lobby)
                {
                    closeLobbyMessage(bot.getConfig().getWarning()
                            + " Guess the music lobby ended because all players left.");
                    finishWithoutRestore();
                }
                else
                    finish("All players left guess the music.");
                return;
            }

            refreshLobbyMessage();
            refreshRoundMessage();
            if(!lobby)
                endRoundIfResolved("Everyone either guessed correctly or passed.");
        }

        private synchronized boolean removePlayer(User user)
        {
            if(user == null || scores.remove(user.getIdLong()) == null)
                return false;

            if(currentRound != null)
            {
                currentRound.correctUsers.remove(user.getIdLong());
                currentRound.idkUsers.remove(user.getIdLong());
                currentRound.guessesByUser.remove(user.getIdLong());
            }
            return true;
        }

        private synchronized void refreshLobbyMessage()
        {
            if(!lobby || lobbyMessageId <= 0L)
                return;
            Guild guild = guild();
            MessageChannel channel = channel();
            if(guild == null || channel == null)
                return;

            channel.editMessageById(lobbyMessageId, MessageEditData.fromCreateData(lobbyMessage(guild)))
                    .queue(message -> {}, failure -> LOG.debug("Failed to refresh guess music lobby message", failure));
        }

        private synchronized void closeLobbyMessage(String message)
        {
            if(lobbyMessageId <= 0L)
                return;
            MessageChannel channel = channel();
            if(channel == null)
                return;

            MessageEditData closed = new MessageEditBuilder()
                    .setContent(message)
                    .setEmbeds(Collections.emptyList())
                    .setComponents(Collections.emptyList())
                    .build();
            channel.editMessageById(lobbyMessageId, closed)
                    .queue(updated -> {}, failure -> LOG.debug("Failed to close guess music lobby message", failure));
        }

        private synchronized void refreshRoundMessage()
        {
            if(lobby || currentRound == null || currentRound.ended || currentRound.messageId <= 0L)
                return;
            MessageChannel channel = channel();
            if(channel == null)
                return;

            channel.editMessageById(currentRound.messageId, MessageEditData.fromCreateData(roundMessage(currentRound)))
                    .queue(message -> {}, failure -> LOG.debug("Failed to refresh guess music round message", failure));
        }

        private String responseLead(String icon, boolean joined)
        {
            return icon + (joined ? " You joined the game." : "");
        }

        private synchronized void configureHints(Boolean enabled, Integer intervalSeconds, Integer stepSeconds,
                                                 Integer hintReplays)
        {
            if(enabled != null)
                options.hintsEnabled = enabled;
            if(intervalSeconds != null)
                options.hintIntervalSeconds = intervalSeconds;
            if(stepSeconds != null)
                options.hintStepSeconds = stepSeconds;
            if(hintReplays != null)
                options.hintReplays = hintReplays;
            options.sanitize();

            if(!options.hintsEnabled || (currentRound != null && currentRound.hintCount >= options.hintReplays))
            {
                cancelHintTimer();
                if(!pausedForNoListeners && currentRound != null && !currentRound.ended)
                    scheduleReplayWhenReady();
            }
            else if(!pausedForNoListeners && currentRound != null && !currentRound.ended && currentRound.correctUsers.isEmpty())
                scheduleHintWhenReady();

            if(!pausedForNoListeners && currentRound != null && !currentRound.ended && options.roundTimeSeconds <= 0)
                scheduleRoundTimeout(options.roundTimeMillis());
        }

        private synchronized String hintSettingsSummary()
        {
            if(!options.hintsEnabled)
                return "`off`";
            return "`on` (`" + options.hintStepSeconds + "s` more every `"
                    + options.hintIntervalSeconds + "s` for `" + options.hintReplays + "` replay"
                    + (options.hintReplays == 1 ? "" : "s") + ")";
        }

        private synchronized void handleSettingsButton(ButtonInteractionEvent event, String action)
        {
            if(!canControl(event.getMember(), event.getUser()))
            {
                event.reply(bot.getConfig().getError()
                        + " Only the host, DJs, Manage Server members, or the bot owner can change guess settings.")
                        .setEphemeral(true).queue();
                return;
            }

            applySettingsAction(action);
            event.editMessage(MessageEditData.fromCreateData(settingsMessage())).queue();
        }

        private void applySettingsAction(String action)
        {
            switch(action)
            {
                case "mode":
                    options.mode = next(options.mode);
                    break;
                case "win":
                    options.winMode = next(options.winMode);
                    break;
                case "position":
                    options.clipPosition = next(options.clipPosition);
                    break;
                case "match":
                    options.matchMode = options.matchMode == MatchMode.FORGIVING ? MatchMode.STRICT : MatchMode.FORGIVING;
                    break;
                case "input":
                    options.inputMode = next(options.inputMode);
                    break;
            case "hints":
                options.hintsEnabled = !options.hintsEnabled;
                break;
            case "guesses":
                options.guessesPerUser = nextGuessLimit(options.guessesPerUser);
                break;
            case "hint_interval_down":
                options.hintIntervalSeconds -= 5;
                break;
                case "hint_interval_up":
                    options.hintIntervalSeconds += 5;
                    break;
                case "replay_interval_down":
                    options.replayIntervalSeconds -= 5;
                    break;
                case "replay_interval_up":
                    options.replayIntervalSeconds += 5;
                    break;
                case "clip_down":
                    options.mode = GuessMode.CUSTOM;
                    options.clipSeconds -= 5;
                    break;
                case "clip_up":
                    options.mode = GuessMode.CUSTOM;
                    options.clipSeconds += 5;
                    break;
                case "timeout_down":
                    options.roundTimeSeconds = options.effectiveRoundTimeSeconds() - 5;
                    break;
                case "timeout_up":
                    options.roundTimeSeconds = options.effectiveRoundTimeSeconds() + 5;
                    break;
                case "timeout_auto":
                    options.roundTimeSeconds = 0;
                    break;
                case "known_down":
                    options.knownPercent -= 10;
                    break;
                case "known_up":
                    options.knownPercent += 10;
                    break;
                case "hint_replays_down":
                    options.hintReplays -= 1;
                    break;
                case "hint_replays_up":
                    options.hintReplays += 1;
                    break;
                case "buffer_down":
                    options.finalGuessSeconds -= 5;
                    break;
                case "buffer_up":
                    options.finalGuessSeconds += 5;
                    break;
                case "target_down":
                    if(options.winMode == WinMode.POINTS)
                        options.targetPoints -= 5;
                    else
                        options.rounds -= 1;
                    break;
                case "target_up":
                    if(options.winMode == WinMode.POINTS)
                        options.targetPoints += 5;
                    else
                        options.rounds += 1;
                    break;
                case "winners_down":
                    options.winnersPerRound -= 1;
                    break;
                case "winners_up":
                    options.winnersPerRound += 1;
                    break;
                default:
                    break;
            }
            options.sanitize();
            if(!options.hintsEnabled || (currentRound != null && currentRound.hintCount >= options.hintReplays))
                cancelHintTimer();
            else if(!pausedForNoListeners && currentRound != null && !currentRound.ended && currentRound.correctUsers.isEmpty())
                scheduleHintWhenReady();
            if(!pausedForNoListeners && currentRound != null && !currentRound.ended && !currentRound.correctUsers.isEmpty()
                    && ("replay_interval_down".equals(action) || "replay_interval_up".equals(action)))
                scheduleReplayWhenReady();
            if(currentRound != null && !currentRound.ended && !pausedForNoListeners
                    && (options.roundTimeSeconds <= 0
                    || "timeout_down".equals(action) || "timeout_up".equals(action) || "timeout_auto".equals(action)))
                scheduleRoundTimeout(options.roundTimeMillis());
        }

        private MessageCreateData settingsMessage()
        {
            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(guild().getSelfMember().getColor())
                    .setTitle("Guess Settings")
                    .setDescription(lobby ? "Lobby settings apply when the host starts the game."
                            : "Changes apply to the active game. Clip changes affect future rounds.")
                    .addField("Mode", options.mode.displayName + " | " + options.winMode.displayName, true)
                    .addField("End", options.roundSummary(), true)
                    .addField("Clip", options.clipSummary(), true)
                    .addField("Timeout", options.roundTimeSummary(), true)
                    .addField("Guessing", options.inputMode.displayName + " | " + options.matchMode.displayName()
                            + " | " + options.guessLimitSummary(), true)
                    .addField("Hints", options.hintSummary(), true)
                    .addField("Replay", "`" + options.replayIntervalSeconds + "s` after correct guesses", true)
                    .addField("Buffer", "`" + options.finalGuessSeconds + "s` after last replay", true)
                    .addField("Known Pool", "`" + options.knownPercent + "%`", true)
                    .addField("Artist", options.artistSummary(), true)
                    .addField("Winners/Round", "`" + options.winnersPerRound + "`", true);
            return new MessageCreateBuilder()
                    .setEmbeds(embed.build())
                    .setComponents(
                            ActionRow.of(
                                    Button.secondary(SETTINGS_PREFIX + "mode", "Mode"),
                                    Button.secondary(SETTINGS_PREFIX + "win", "Win"),
                                    Button.secondary(SETTINGS_PREFIX + "position", "Start"),
                                    Button.secondary(SETTINGS_PREFIX + "match", "Match"),
                                    Button.secondary(SETTINGS_PREFIX + "guesses", "Guesses")),
                            ActionRow.of(
                                    Button.secondary(SETTINGS_PREFIX + "clip_down", "Clip -5s"),
                                    Button.secondary(SETTINGS_PREFIX + "clip_up", "Clip +5s"),
                                    Button.secondary(SETTINGS_PREFIX + "timeout_down", "Timeout -5s"),
                                    Button.secondary(SETTINGS_PREFIX + "timeout_up", "Timeout +5s"),
                                    Button.secondary(SETTINGS_PREFIX + "timeout_auto", "Auto Timeout")),
                            ActionRow.of(
                                    Button.secondary(SETTINGS_PREFIX + "hints", "Hints"),
                                    Button.secondary(SETTINGS_PREFIX + "hint_interval_down", "Idle -5s"),
                                    Button.secondary(SETTINGS_PREFIX + "hint_interval_up", "Idle +5s"),
                                    Button.secondary(SETTINGS_PREFIX + "replay_interval_down", "Replay -5s"),
                                    Button.secondary(SETTINGS_PREFIX + "replay_interval_up", "Replay +5s")),
                            ActionRow.of(
                                    Button.secondary(SETTINGS_PREFIX + "known_down", "Known -10%"),
                                    Button.secondary(SETTINGS_PREFIX + "known_up", "Known +10%"),
                                    Button.secondary(SETTINGS_PREFIX + "target_down", "Target -"),
                                    Button.secondary(SETTINGS_PREFIX + "target_up", "Target +"),
                                    Button.secondary(SETTINGS_PREFIX + "winners_down", "Winners -")),
                            ActionRow.of(
                                    Button.secondary(SETTINGS_PREFIX + "winners_up", "Winners +"),
                                    Button.secondary(SETTINGS_PREFIX + "hint_replays_down", "Hints -"),
                                    Button.secondary(SETTINGS_PREFIX + "hint_replays_up", "Hints +"),
                                    Button.secondary(SETTINGS_PREFIX + "buffer_down", "Buffer -5s"),
                                    Button.secondary(SETTINGS_PREFIX + "buffer_up", "Buffer +5s")))
                    .build();
        }

        private GuessMode next(GuessMode mode)
        {
            GuessMode[] values = GuessMode.values();
            return values[(mode.ordinal() + 1) % values.length];
        }

        private ClipPosition next(ClipPosition position)
        {
            ClipPosition[] values = ClipPosition.values();
            return values[(position.ordinal() + 1) % values.length];
        }

        private InputMode next(InputMode input)
        {
            InputMode[] values = InputMode.values();
            return values[(input.ordinal() + 1) % values.length];
        }

        private WinMode next(WinMode winMode)
        {
            WinMode[] values = WinMode.values();
            return values[(winMode.ordinal() + 1) % values.length];
        }

        private int nextGuessLimit(int current)
        {
            if(current <= 0)
                return 3;
            if(current < 5)
                return 5;
            if(current < 10)
                return 10;
            if(current < 20)
                return 20;
            return 0;
        }

        private synchronized boolean acceptsChatGuesses(long eventChannelId)
        {
            return !lobby && !finished && eventChannelId == channelId
                    && (options.inputMode == InputMode.CHAT || options.inputMode == InputMode.BOTH);
        }

        private synchronized void submitGuess(User user, Member member, long eventChannelId, String answer,
                                              java.util.function.Consumer<String> reply)
        {
            if(finished)
            {
                reply.accept(bot.getConfig().getWarning() + " That game has ended.");
                return;
            }
            if(eventChannelId != channelId)
            {
                reply.accept(bot.getConfig().getError() + " Guess in the channel where the game is running.");
                return;
            }
            if(answer == null || answer.trim().isEmpty())
            {
                reply.accept(bot.getConfig().getError() + " Guess a song title.");
                return;
            }

            boolean joined = ensurePlayer(user);
            if(lobby)
            {
                reply.accept(responseLead(bot.getConfig().getWarning(), joined)
                        + " The game has not started yet.");
                return;
            }
            if(currentRound == null || currentRound.ended)
            {
                reply.accept(responseLead(bot.getConfig().getWarning(), joined)
                        + " There is no active round right now.");
                return;
            }

            PlayerScore score = scores.get(user.getIdLong());
            if(currentRound.correctUsers.contains(user.getIdLong()))
            {
                reply.accept(responseLead(bot.getConfig().getWarning(), joined)
                        + " You already got this round correct.");
                return;
            }
            int guesses = currentRound.guessesByUser.getOrDefault(user.getIdLong(), 0);
            if(options.hasGuessLimit() && guesses >= options.guessesPerUser)
            {
                reply.accept(responseLead(bot.getConfig().getWarning(), joined)
                        + " You are out of guesses for this round.");
                return;
            }
            currentRound.guessesByUser.put(user.getIdLong(), guesses + 1);

            if(GuessMusicTitleMatcher.matches(answer, currentRound.answer, options.matchMode))
            {
                boolean firstCorrect = currentRound.correctUsers.isEmpty();
                int rank = currentRound.correctUsers.size() + 1;
                int points = roundPoints(rank);
                currentRound.correctUsers.add(user.getIdLong());
                currentRound.idkUsers.remove(user.getIdLong());
                if(firstCorrect)
                    cancelHintTimer();
                score.points += points;
                score.correct++;
                // Hosted games grant NO coins/XP for guessing (the host could feed easy songs); players
                // still earn passive listening XP. Only bot-run games reward correct guesses.
                if(!options.hosted)
                    bot.getEconomyService().recordGuessCorrect(user.getIdLong(), channel());
                refreshRoundMessage();
                reply.accept(responseLead(bot.getConfig().getSuccess(), joined)
                        + " Correct. You earned `" + points + "` point" + (points == 1 ? "" : "s") + " this round.");
                announceCorrectGuess(user);
                if(currentRound.correctUsers.size() >= options.winnersPerRound)
                    endRound("Enough players guessed correctly.");
                else if(endRoundIfResolved("Everyone either guessed correctly or passed."))
                    return;
                else
                    scheduleReplayWhenReady();
            }
            else
            {
                if(options.hasGuessLimit())
                {
                    int remaining = options.guessesPerUser - guesses - 1;
                    reply.accept(responseLead(bot.getConfig().getWarning(), joined) + " Not quite."
                            + (remaining > 0 ? " `" + remaining + "` guess" + (remaining == 1 ? "" : "es") + " left." : " No guesses left."));
                    if(remaining <= 0)
                        endRoundIfResolved("Everyone either guessed correctly or passed.");
                }
                else
                {
                    reply.accept(responseLead(bot.getConfig().getWarning(), joined) + " Not quite.");
                }
            }
        }

        private synchronized void pass(User user, long eventChannelId, java.util.function.Consumer<String> reply)
        {
            if(finished)
            {
                reply.accept(bot.getConfig().getWarning() + " That game has ended.");
                return;
            }
            if(eventChannelId != channelId)
            {
                reply.accept(bot.getConfig().getError() + " Use `/idk` in the channel where the game is running.");
                return;
            }

            boolean joined = ensurePlayer(user);
            if(lobby)
            {
                reply.accept(responseLead(bot.getConfig().getWarning(), joined)
                        + " The game has not started yet.");
                return;
            }
            if(currentRound == null || currentRound.ended)
            {
                reply.accept(responseLead(bot.getConfig().getWarning(), joined)
                        + " There is no active round right now.");
                return;
            }
            if(currentRound.correctUsers.contains(user.getIdLong()))
            {
                reply.accept(responseLead(bot.getConfig().getWarning(), joined)
                        + " You already got this round correct.");
                return;
            }
            if(currentRound.idkUsers.contains(user.getIdLong()))
            {
                currentRound.idkUsers.remove(user.getIdLong());
                reply.accept(responseLead(bot.getConfig().getWarning(), joined)
                        + " Removed your pass for this round.");
                return;
            }

            currentRound.idkUsers.add(user.getIdLong());
            reply.accept(responseLead(bot.getConfig().getWarning(), joined)
                    + " Marked you as not knowing this one. You can still guess if the round stays open.");
            endRoundIfResolved("Everyone either guessed correctly or passed.");
        }

        private boolean endRoundIfResolved(String reason)
        {
            if(currentRound == null || currentRound.ended || scores.isEmpty())
                return false;
            boolean resolved = scores.keySet().stream().allMatch(this::isResolvedForRound);
            if(resolved)
            {
                endRound(reason);
                return true;
            }
            return false;
        }

        private boolean isResolvedForRound(long userId)
        {
            return currentRound.correctUsers.contains(userId)
                    || currentRound.idkUsers.contains(userId)
                    || (options.hasGuessLimit()
                    && currentRound.guessesByUser.getOrDefault(userId, 0) >= options.guessesPerUser);
        }

        private void announceCorrectGuess(User user)
        {
            MessageChannel channel = channel();
            if(channel != null)
                channel.sendMessage(bot.getConfig().getSuccess() + " " + user.getAsMention()
                        + " guessed correctly! (at " + roundElapsedSeconds() + "s)").queue();
        }

        private long roundElapsedSeconds()
        {
            if(currentRound == null || currentRound.startedAtMillis <= 0L)
                return 0L;
            return Math.max(0L, Math.round((System.currentTimeMillis() - currentRound.startedAtMillis) / 1000.0));
        }

        private int roundPoints(int rank)
        {
            int playerCount = Math.max(1, Math.max(scores.size(), listeningPlayerCount()));
            return calculateRoundPoints(playerCount, rank);
        }

        private int listeningPlayerCount()
        {
            Guild guild = guild();
            if(guild == null || guild.getAudioManager().getConnectedChannel() == null)
                return 0;
            return (int)guild.getAudioManager().getConnectedChannel().getMembers().stream()
                    .filter(member -> member.getVoiceState() != null)
                    .filter(member -> !member.getUser().isBot())
                    .filter(member -> !member.getVoiceState().isDeafened())
                    .count();
        }

        private synchronized void nextRound(String reason)
        {
            if(finished)
                return;
            if(options.winMode == WinMode.ROUNDS && roundNumber >= options.rounds)
            {
                finish("All rounds are complete.");
                return;
            }
            // The finished round's highlight (if any) has already been shown during the reveal;
            // cancel a still-running analysis so it can't pile up on the highlight executor.
            if(currentRound != null && currentRound.highlightFuture != null)
                currentRound.highlightFuture.cancel(true);
            currentRound = null;
            roundNumber++;
            // The next song is usually already prefetched during the previous round, so only tell the
            // user we are loading when nothing is ready and nothing is in flight (e.g. the first round).
            // A hosted game with a dry queue shows its own "add a song" prompt instead.
            if(preparedRound == null && !preparing && !(options.hosted && hostQueue.isEmpty()))
            {
                MessageChannel channel = channel();
                if(channel != null)
                    channel.sendMessage(bot.getConfig().getSuccess() + " Loading round `" + roundNumber + "`...").queue();
            }
            requestRoundToPlay();
        }

        // Ensures a round starts playing as soon as one is available — using the prefetched round if
        // ready, otherwise waiting for an in-flight prefetch or kicking off a fresh load.
        private synchronized void requestRoundToPlay()
        {
            if(finished)
                return;
            awaitingRound = true;
            if(preparedRound != null)
                playPreparedRound();
            else if(!preparing)
                startPreparePipeline();
            // else: a prefetch is already in flight and will play this round when it completes.
        }

        // Loads/scans the next round ahead of time (while the current one is being guessed) so the
        // transition between rounds is instant.
        private synchronized void maybePrefetchNextRound()
        {
            if(finished || preparing || preparedRound != null)
                return;
            if(options.winMode == WinMode.ROUNDS && roundNumber >= options.rounds)
                return; // the current round is the last one
            if(options.hosted && hostQueue.isEmpty())
                return; // nothing to prefetch yet; we'll wait for the host when the round actually ends
            startPreparePipeline();
        }

        private synchronized boolean isHosted()
        {
            return options.hosted;
        }

        /** True while a started, un-paused hosted game is running — listeners earn normal listening XP then. */
        private synchronized boolean isHostedListening()
        {
            return options.hosted && !lobby && !finished && !pausedForNoListeners;
        }

        // Hosted mode: when the host's queue runs dry, pause the game and wait for them to add another
        // song. If one arrives within the idle window the game resumes; otherwise it ends.
        private synchronized void beginHostWait()
        {
            if(finished || !options.hosted || hostWaitActive)
                return;
            hostWaitActive = true;
            cancelHostIdleTimer();
            long idleMs = options.hostIdleMillis();
            MessageChannel channel = channel();
            if(channel != null)
                channel.sendMessage(bot.getConfig().getWarning()
                        + " Out of songs. Use **➕ Add song** to keep going — the game ends in `"
                        + TimeUtil.formatTime(idleMs) + "` if no song is added.").queue();
            hostIdleTimeout = bot.getThreadpool().schedule(this::onHostIdleTimeout, idleMs, TimeUnit.MILLISECONDS);
        }

        private void onHostIdleTimeout()
        {
            boolean shouldFinish = false;
            synchronized(this)
            {
                if(finished || !hostWaitActive)
                    return;
                if(!hostQueue.isEmpty())
                {
                    // A song slipped in between the timer firing and acquiring the lock; resume instead.
                    resumeFromHostWait();
                    return;
                }
                // Commit to ending while still holding the lock so a concurrent enqueueHostSong can't
                // resume (and then have us tear down) a game we've already decided to end: clearing
                // hostWaitActive makes its `if(hostWaitActive)` branch fall through to a plain append.
                hostWaitActive = false;
                cancelHostIdleTimer();
                shouldFinish = true;
            }
            if(shouldFinish)
                finish("The hosted game ended because no new song was added in time.");
        }

        private synchronized void cancelHostIdleTimer()
        {
            if(hostIdleTimeout != null)
            {
                hostIdleTimeout.cancel(false);
                hostIdleTimeout = null;
            }
        }

        private synchronized void resumeFromHostWait()
        {
            if(!hostWaitActive)
                return;
            hostWaitActive = false;
            cancelHostIdleTimer();
            requestRoundToPlay();
        }

        // Appends a resolved host pick to the FIFO queue (under the session lock); if the game is sitting
        // idle waiting for songs, it resumes. Returns false only if the game is already over.
        private synchronized boolean enqueueHostSong(TrackReference reference)
        {
            if(finished || reference == null)
                return false;
            hostQueue.addLast(reference);
            if(hostWaitActive)
                resumeFromHostWait();
            return true;
        }

        private synchronized void seedHostQueueFromPlaylist(Guild guild)
        {
            if(options.playlistName == null || options.playlistName.isBlank())
                return;
            try
            {
                for(PlaylistSummary playlist : bot.getUserPlaylistService().listPlaylists(hostId))
                {
                    if(!playlist.getName().equalsIgnoreCase(options.playlistName))
                        continue;
                    for(PlaylistTrack item : bot.getUserPlaylistService().listItems(playlist.getId()))
                    {
                        TrackReference ref = hostReferenceFor(item.getLoadQuery(), item.getTitle(), item.getAuthor());
                        if(ref != null)
                            hostQueue.addLast(ref);
                    }
                    break;
                }
            }
            catch(PlaylistException ex)
            {
                LOG.debug("Failed to seed hosted guess music from playlist '{}': {}", options.playlistName, ex.getMessage());
            }
        }

        private synchronized void startPreparePipeline()
        {
            if(finished || preparing)
                return;
            preparing = true;
            coverSkipsThisRound = 0;
            loadRound(0);
        }

        // Pipeline terminus on success: stash the ready round and play it immediately if one is awaited.
        private synchronized void onRoundReady(PreparedRound prepared)
        {
            preparing = false;
            if(finished || prepared == null)
                return;
            preparedRound = prepared;
            // Reserve this song now so a subsequent prefetch (or retry) never re-picks it.
            usedKeys.add(prepared.reference.normalizedKey());
            usedKeys.addAll(songIdentityKeys(answerFor(prepared.track, prepared.reference)));
            usedKeys.addAll(trackIdentityKeys(prepared.track));
            if(awaitingRound)
                playPreparedRound();
        }

        // Pipeline terminus on failure: finish the game only if a round is actually needed now;
        // a failed background prefetch is harmless and just stops.
        private synchronized void abortOrFinish(String reason)
        {
            preparing = false;
            if(awaitingRound)
                finish(reason);
        }

        private synchronized void loadRound(int attempt)
        {
            if(finished)
                return;
            if(attempt >= MAX_LOAD_ATTEMPTS)
            {
                abortOrFinish("I could not load enough playable songs for another round.");
                return;
            }

            TrackReference reference = chooseReference();
            if(reference == null)
            {
                if(options.hosted)
                {
                    // A hosted game doesn't end when the pool empties: wait for the host to add another
                    // song (and end only if they don't within the idle window).
                    preparing = false;
                    if(awaitingRound)
                        beginHostWait();
                    return;
                }
                abortOrFinish("The game ran out of playable songs.");
                return;
            }

            loadReference(reference, attempt, 0);
        }

        private synchronized void loadReference(TrackReference reference, int attempt, int queryIndex)
        {
            if(finished)
                return;
            if(queryIndex >= reference.queries.size())
            {
                failedKeys.add(reference.normalizedKey());
                loadRound(attempt + 1);
                return;
            }

            bot.getPlayerManager().loadItemOrdered("guessmusic-" + guildId, reference.queries.get(queryIndex),
                    new RoundLoadHandler(this, reference, attempt, queryIndex));
        }

        private TrackReference chooseReference()
        {
            if(options.hosted)
                return hostQueue.poll(); // FIFO: play host picks in the order they were added; null when dry
            boolean discovery = !discoveryReferences.isEmpty() && random.nextInt(100) >= options.knownPercent;
            List<TrackReference> primary = discovery ? discoveryReferences : knownReferences;
            List<TrackReference> fallback = discovery ? knownReferences : discoveryReferences;
            // Exhaust fresh references in BOTH pools before reusing anything, so a fully-consumed
            // primary pool no longer repeats songs while the other pool still has unplayed tracks.
            TrackReference ref = chooseUnused(primary);
            if(ref == null)
                ref = chooseUnused(fallback);
            if(ref == null)
                ref = chooseFrom(primary);
            if(ref == null)
                ref = chooseFrom(fallback);
            return ref;
        }

        private TrackReference chooseUnused(List<TrackReference> refs)
        {
            if(refs.isEmpty())
                return null;
            List<TrackReference> shuffled = new ArrayList<>(refs);
            Collections.shuffle(shuffled, random);
            for(TrackReference ref : shuffled)
                if(!usedKeys.contains(ref.normalizedKey()) && !failedKeys.contains(ref.normalizedKey()))
                    return ref;
            for(TrackReference ref : shuffled)
                if(!usedKeys.contains(ref.normalizedKey()))
                    return ref;
            return null;
        }

        // Last-resort reuse (an already-played reference) so endless / long point games never stall.
        private TrackReference chooseFrom(List<TrackReference> refs)
        {
            if(refs.isEmpty())
                return null;
            TrackReference unused = chooseUnused(refs);
            if(unused != null)
                return unused;
            List<TrackReference> shuffled = new ArrayList<>(refs);
            Collections.shuffle(shuffled, random);
            return shuffled.get(0);
        }

        private synchronized void startRound(AudioTrack track, TrackReference reference, int attempt)
        {
            if(finished || track == null || track.getInfo() == null)
                return;

            long clipMs = options.clipMillis();
            scanExecutor.submit(() ->
            {
                try
                {
                    // Cover filtering, the audio scan and the highlight cache read are all blocking I/O; do
                    // them here on the scan executor (no lock held) and hand the results to onRoundReady.
                    if(reference.discovery && isCoverRound(track, reference))
                    {
                        skipCoverRound(track, reference, attempt);
                        return;
                    }
                    GuessMusicAudioScanner.ScanResult scan = GuessMusicAudioScanner.findClipStart(track, clipMs);
                    Highlight cachedHighlight = lookupCachedHighlight(track, reference);
                    onRoundReady(new PreparedRound(track, reference, scan, cachedHighlight));
                }
                catch(Throwable t)
                {
                    // Never let the prepare pipeline die mid-flight: an escaped exception would latch
                    // `preparing` and hang the game at "Loading...". Treat it as a failed reference and
                    // keep trying (which always reaches a terminus that clears `preparing`).
                    LOG.warn("Guess music round preparation failed unexpectedly for guild {}", guildId, t);
                    failPreparePipeline(reference, attempt);
                }
            });
        }

        private synchronized void failPreparePipeline(TrackReference reference, int attempt)
        {
            if(finished)
            {
                preparing = false;
                return;
            }
            failedKeys.add(reference.normalizedKey());
            loadRound(attempt + 1);
        }

        // Only discovery rounds are cover-checked; a song in the host's own queue/playlist is their choice.
        private boolean isCoverRound(AudioTrack track, TrackReference reference)
        {
            ParsedTitle answer = answerFor(track, reference);
            return coverDetector.isCover(answer.getArtist(), answer.getTitle());
        }

        private synchronized void skipCoverRound(AudioTrack track, TrackReference reference, int attempt)
        {
            if(finished)
                return;
            usedKeys.add(reference.normalizedKey());
            // Block this specific recording and the artist-qualified song (NOT the bare title, so a
            // different artist's song of the same name is still allowed) from being reloaded.
            usedKeys.addAll(TrackIdentity.keys(track));
            usedKeys.addAll(songOnlyKeys(answerFor(track, reference)));
            LOG.debug("Skipping guess music discovery round '{}' — flagged as a cover", track.getInfo().title);
            // A cover is loadable, so it must not consume the "couldn't load a playable song" budget;
            // bound cover skips separately so a cover-heavy pool can't spin on MusicBrainz lookups.
            if(++coverSkipsThisRound > MAX_COVER_SKIPS_PER_ROUND)
                loadRound(attempt + 1);
            else
                loadRound(attempt);
        }

        private Highlight lookupCachedHighlight(AudioTrack track, TrackReference reference)
        {
            if(highlightStore == null || track == null || track.getInfo() == null)
                return null;
            try
            {
                return highlightStore.find(highlightKeys(track, answerFor(track, reference))).orElse(null);
            }
            catch(RuntimeException ex)
            {
                LOG.debug("Failed to read guess music highlight cache", ex);
                return null;
            }
        }

        // Commits the prefetched round as the current round and starts playing it.
        private synchronized void playPreparedRound()
        {
            if(finished)
                return;
            PreparedRound prepared = preparedRound;
            preparedRound = null;
            awaitingRound = false;
            if(prepared == null || prepared.track == null || prepared.track.getInfo() == null)
            {
                requestRoundToPlay();
                return;
            }

            AudioTrack track = prepared.track;
            TrackReference reference = prepared.reference;
            ParsedTitle answer = answerFor(track, reference);
            long clipMs = options.clipMillis();
            long firstAudibleMs = prepared.scan == null ? 0L : prepared.scan.getPositionMillis();
            long startMs = options.startPosition(track, clipMs, random, firstAudibleMs);
            currentRound = new Round(roundNumber, track, answer, reference.discovery, reference.normalizedKey(),
                    startMs, clipMs, clipMs);
            currentRound.highlight = prepared.cachedHighlight;
            // Identity keys were already reserved in onRoundReady; re-adding here is a harmless no-op.
            usedKeys.add(reference.normalizedKey());
            usedKeys.addAll(songIdentityKeys(answer));
            usedKeys.addAll(trackIdentityKeys(track));
            prepareHighlight(currentRound, firstAudibleMs);

            if(pausedForNoListeners || isAloneInVoice())
            {
                pausedForNoListeners = true;
                MessageChannel channel = channel();
                if(channel != null)
                    channel.sendMessage(bot.getConfig().getWarning()
                            + " Round `" + roundNumber + "` is ready, but the game is paused until someone joins voice.").queue();
                return;
            }

            if(!startRoundPlayback(true, clipMs, options.roundTimeMillis()))
            {
                currentRound = null;
                requestRoundToPlay();
                return;
            }
            maybePrefetchNextRound();
        }

        private boolean startRoundPlayback(boolean sendRoundMessage, long snippetDelayMs, long roundDelayMs)
        {
            if(currentRound == null || currentRound.ended)
                return false;

            AudioHandler handler = bot.getPlayerManager().setUpHandler(guild());
            long windowId = openSnippetWindow();
            Round playbackRound = currentRound;
            if(!handler.playGuessMusicSnippet(playbackRound.track, playbackRound.startMs,
                    () -> onSnippetPlaybackStarted(windowId, handler, snippetDelayMs, roundDelayMs),
                    () -> onSnippetWindowEnded(windowId),
                    reason -> onSnippetPlaybackFailed(windowId, playbackRound, reason)))
            {
                clearSnippetWindow(windowId);
                return false;
            }

            MessageChannel channel = channel();
            if(sendRoundMessage && !currentRound.messageSent && channel != null)
            {
                Round round = currentRound;
                round.messageSent = true;
                channel.sendMessage(roundMessage(round)).queue(message ->
                {
                    synchronized(Session.this)
                    {
                        if(currentRound == round)
                            round.messageId = message.getIdLong();
                    }
                });
            }
            return true;
        }

        private synchronized void onSnippetPlaybackStarted(long windowId, AudioHandler handler,
                                                           long snippetDelayMs, long roundDelayMs)
        {
            if(activeSnippetWindowId != windowId || finished || currentRound == null || currentRound.ended)
                return;
            currentRound.playbackStarted = true;
            if(currentRound.startedAtMillis <= 0L)
                currentRound.startedAtMillis = System.currentTimeMillis();
            scheduleSnippetStopper(handler, snippetDelayMs, windowId);
            if(roundDelayMs > 0L && roundTimeout == null)
                scheduleRoundTimeout(roundDelayMs);
        }

        private synchronized void onSnippetPlaybackFailed(long windowId, Round round, String reason)
        {
            if(activeSnippetWindowId == windowId)
                activeSnippetWindowId = 0L;
            if(snippetStopper != null)
                snippetStopper.cancel(false);
            snippetStopper = null;
            snippetStopAtMillis = 0L;
            if(finished || round == null || currentRound != round || round.ended)
                return;

            LOG.warn("Guess music track failed before playback for guild {}; track='{}'; reason={}",
                    guildId, round.track.getInfo().title, reason);
            round.ended = true;
            cancelRoundTimers();
            usedKeys.add(round.referenceKey);
            usedKeys.addAll(trackIdentityKeys(round.track));
            publishRoundSkipMessage(round);
            currentRound = null;
            requestRoundToPlay();
        }

        private void publishRoundSkipMessage(Round round)
        {
            if(round.messageId <= 0L)
                return;
            MessageChannel channel = channel();
            if(channel == null)
                return;
            channel.editMessageById(round.messageId,
                            bot.getConfig().getWarning()
                                    + " This track failed before audio started, so I am loading another song.")
                    .setComponents(Collections.emptyList())
                    .queue(message -> {}, failure -> LOG.debug("Failed to edit failed guess music round", failure));
        }

        private MessageCreateData roundMessage(Round round)
        {
            String source = round.discovery ? "Discovery" : "Known";
            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(guild().getSelfMember().getColor())
                    .setTitle("Round " + roundNumber)
                    .setDescription("Use `/g` to guess privately.")
                    .addField("Mode", options.mode.displayName, true)
                    .addField("Clip", "`" + TimeUtil.formatTime(round.currentClipMs) + "`", true)
                    .addField("Pool", source, true)
                    .addField("Hints", options.hintSummary(), true)
                    .addField("Round Ends", options.roundTimeSummary(), true)
                    .addField("Leaderboard", leaderboard(5), false);
            ActionRow hostControls = options.hosted
                    ? ActionRow.of(
                            Button.secondary(ADD_SONG_BUTTON, "➕ Add song"),
                            Button.secondary(REVEAL_BUTTON, "Reveal"),
                            Button.danger(STOP_BUTTON, "Stop"))
                    : ActionRow.of(
                            Button.secondary(REVEAL_BUTTON, "Reveal"),
                            Button.danger(STOP_BUTTON, "Stop"));
            return new MessageCreateBuilder()
                    .setEmbeds(embed.build())
                    .setComponents(
                            ActionRow.of(
                                    Button.primary(GUESS_BUTTON, "Guess"),
                                    Button.secondary(IDK_BUTTON, "IDK"),
                                    Button.secondary(REPLAY_BUTTON, "Play Again"),
                                    Button.secondary(CLIP_SHORTER_BUTTON, "Shorter"),
                                    Button.secondary(CLIP_LONGER_BUTTON, "Longer")),
                            hostControls)
                    .build();
        }

        private synchronized long openSnippetWindow()
        {
            activeSnippetWindowId = ++snippetWindowSequence;
            return activeSnippetWindowId;
        }

        private synchronized void clearSnippetWindow(long windowId)
        {
            if(activeSnippetWindowId == windowId)
                activeSnippetWindowId = 0L;
        }

        private void scheduleSnippetStopper(AudioHandler handler, long delayMs, long windowId)
        {
            if(snippetStopper != null)
                snippetStopper.cancel(false);
            long delay = Math.max(500L, delayMs);
            snippetStopAtMillis = System.currentTimeMillis() + delay;
            snippetStopper = bot.getThreadpool().schedule(() ->
            {
                handler.stopGuessMusicSnippet();
                onSnippetWindowEnded(windowId);
            }, delay, TimeUnit.MILLISECONDS);
        }

        private synchronized void onSnippetWindowEnded(long windowId)
        {
            if(activeSnippetWindowId != windowId)
                return;
            activeSnippetWindowId = 0L;
            if(snippetStopper != null)
                snippetStopper.cancel(false);
            snippetStopper = null;
            snippetStopAtMillis = 0L;
            if(finished || pausedForNoListeners || currentRound == null || currentRound.ended)
                return;
            if(shouldReplayCurrentClip())
            {
                scheduleNextReplay(options.replayIntervalMillis());
                return;
            }
            scheduleNextHint(options.hintIntervalMillis());
        }

        private void scheduleRoundTimeout(long delayMs)
        {
            if(roundTimeout != null)
                roundTimeout.cancel(false);
            long delay = Math.max(1_000L, delayMs);
            roundDeadlineMillis = System.currentTimeMillis() + delay;
            roundTimeout = bot.getThreadpool().schedule(this::revealForTimeout, delay, TimeUnit.MILLISECONDS);
            scheduleCountdown(delay);
        }

        private void scheduleCountdown(long roundDelayMs)
        {
            cancelCountdownTimer();
            lastCountdownSeconds = Integer.MAX_VALUE;
            long firstDelay = nextCountdownDelay(roundDelayMs);
            if(firstDelay <= 0L)
                return;
            countdownDeadlineMillis = System.currentTimeMillis() + firstDelay;
            countdownTask = bot.getThreadpool().schedule(this::announceCountdown, firstDelay, TimeUnit.MILLISECONDS);
        }

        private long nextCountdownDelay(long remainingMs)
        {
            long remainingSeconds = Math.max(0L, (long)Math.ceil(remainingMs / 1000.0));
            if(remainingSeconds > COUNTDOWN_INTERVAL_SECONDS)
            {
                long nextDisplay = ((remainingSeconds - 1L) / COUNTDOWN_INTERVAL_SECONDS) * COUNTDOWN_INTERVAL_SECONDS;
                return Math.max(1_000L, (remainingSeconds - nextDisplay) * 1000L);
            }
            if(remainingSeconds > COUNTDOWN_FINAL_SECONDS)
                return (remainingSeconds - COUNTDOWN_FINAL_SECONDS) * 1000L;
            return 0L;
        }

        private synchronized void announceCountdown()
        {
            countdownTask = null;
            countdownDeadlineMillis = 0L;
            if(finished || pausedForNoListeners || currentRound == null || currentRound.ended || roundDeadlineMillis <= 0L)
                return;

            long remainingMs = Math.max(0L, roundDeadlineMillis - System.currentTimeMillis());
            int remainingSeconds = (int)Math.ceil(remainingMs / 1000.0);
            int displaySeconds = remainingSeconds <= COUNTDOWN_FINAL_SECONDS
                    ? COUNTDOWN_FINAL_SECONDS
                    : (remainingSeconds / COUNTDOWN_INTERVAL_SECONDS) * COUNTDOWN_INTERVAL_SECONDS;
            if(displaySeconds > 0 && displaySeconds < lastCountdownSeconds)
            {
                lastCountdownSeconds = displaySeconds;
                MessageChannel channel = channel();
                if(channel != null)
                    channel.sendMessage(bot.getConfig().getWarning() + " `" + displaySeconds + "s` left to guess.").queue();
            }

            long nextDelay = nextCountdownDelay(remainingMs);
            if(nextDelay > 0L)
            {
                countdownDeadlineMillis = System.currentTimeMillis() + nextDelay;
                countdownTask = bot.getThreadpool().schedule(this::announceCountdown, nextDelay, TimeUnit.MILLISECONDS);
            }
        }

        private void scheduleNextHint(long delayMs)
        {
            cancelHintTimer();
            if(!options.hintsEnabled || currentRound == null || currentRound.ended
                    || !currentRound.correctUsers.isEmpty() || currentRound.hintCount >= options.hintReplays)
                return;
            long delay = Math.max(1_000L, delayMs);
            hintDeadlineMillis = System.currentTimeMillis() + delay;
            hintTask = bot.getThreadpool().schedule(this::playHint, delay, TimeUnit.MILLISECONDS);
        }

        private void scheduleNextReplay(long delayMs)
        {
            cancelReplayTimer();
            if(currentRound == null || currentRound.ended || !shouldReplayCurrentClip())
                return;
            long delay = Math.max(1_000L, delayMs);
            replayDeadlineMillis = System.currentTimeMillis() + delay;
            replayTask = bot.getThreadpool().schedule(this::playReplay, delay, TimeUnit.MILLISECONDS);
        }

        private void scheduleHintWhenReady()
        {
            if(!options.hintsEnabled || currentRound == null || currentRound.ended
                    || !currentRound.correctUsers.isEmpty() || currentRound.hintCount >= options.hintReplays)
                return;
            if(snippetStopper != null)
                return;
            scheduleNextHint(options.hintIntervalMillis());
        }

        private void scheduleReplayWhenReady()
        {
            cancelReplayTimer();
            if(pausedForNoListeners || currentRound == null || currentRound.ended || !shouldReplayCurrentClip())
                return;
            if(snippetStopper != null)
                return;
            scheduleNextReplay(options.replayIntervalMillis());
        }

        private boolean shouldReplayCurrentClip()
        {
            return currentRound != null && !currentRound.ended
                    && (!currentRound.correctUsers.isEmpty()
                    || !options.hintsEnabled
                    || currentRound.hintCount >= options.hintReplays);
        }

        private synchronized void playHint()
        {
            hintTask = null;
            hintDeadlineMillis = 0L;
            if(finished || pausedForNoListeners || currentRound == null || currentRound.ended
                    || !options.hintsEnabled || !currentRound.correctUsers.isEmpty()
                    || currentRound.hintCount >= options.hintReplays)
                return;

            long remainingTrack = currentRound.track.getDuration() == Long.MAX_VALUE
                    ? currentRound.currentClipMs + options.hintStepMillis()
                    : Math.max(0L, currentRound.track.getDuration() - currentRound.startMs);
            long nextClipMs = Math.min(remainingTrack, currentRound.currentClipMs + options.hintStepMillis());
            if(nextClipMs <= currentRound.currentClipMs + 250L)
                return;

            currentRound.currentClipMs = nextClipMs;
            currentRound.hintCount++;
            MessageChannel channel = channel();
            if(channel != null)
                channel.sendMessage(bot.getConfig().getWarning() + " +" + options.hintStepSeconds + "s").queue();

            AudioHandler handler = bot.getPlayerManager().setUpHandler(guild());
            long windowId = openSnippetWindow();
            Round round = currentRound;
            if(!handler.playGuessMusicSnippet(round.track, round.startMs,
                    () -> onSnippetPlaybackStarted(windowId, handler, round.currentClipMs, 0L),
                    () -> onSnippetWindowEnded(windowId),
                    reason -> onSnippetPlaybackFailed(windowId, round, reason)))
            {
                clearSnippetWindow(windowId);
                reveal("I could not replay a hint.");
                return;
            }
        }

        private synchronized void playReplay()
        {
            replayTask = null;
            replayDeadlineMillis = 0L;
            if(finished || pausedForNoListeners || currentRound == null || currentRound.ended
                    || !shouldReplayCurrentClip())
                return;
            if(!startRoundPlayback(false, currentRound.currentClipMs, 0L))
                reveal("I could not replay the clip.");
        }

        private synchronized MessageCreateData replayNow()
        {
            cancelReplayTimer();
            cancelHintTimer();
            if(currentRound == null || currentRound.ended)
                return statusCreateMessage();
            if(!startRoundPlayback(false, currentRound.currentClipMs, 0L))
                reveal("I could not replay the clip.");
            return roundMessage(currentRound);
        }

        private synchronized MessageCreateData adjustClipLength(boolean longer)
        {
            if(currentRound == null || currentRound.ended)
                return statusCreateMessage();
            cancelReplayTimer();
            cancelHintTimer();
            long step = options.hintStepMillis();
            long duration = currentRound.track.getDuration() == Long.MAX_VALUE
                    ? currentRound.currentClipMs + step
                    : Math.max(currentRound.originalClipMs, currentRound.track.getDuration() - currentRound.startMs);
            long next = longer ? currentRound.currentClipMs + step : currentRound.currentClipMs - step;
            currentRound.currentClipMs = Math.max(currentRound.originalClipMs, Math.min(duration, next));
            if(!startRoundPlayback(false, currentRound.currentClipMs, 0L))
                reveal("I could not replay the adjusted clip.");
            return roundMessage(currentRound);
        }

        private synchronized void reveal(String reason)
        {
            if(finished || currentRound == null || currentRound.ended)
                return;
            endRound(reason);
        }

        private synchronized void revealForTimeout()
        {
            if(finished || currentRound == null || currentRound.ended)
                return;
            if(currentRound.correctUsers.isEmpty())
                endRound("Time is up. No one guessed the song.");
            else
                endRound("Time is up.");
        }

        private synchronized void pauseForNoListeners()
        {
            if(finished || lobby || pausedForNoListeners)
                return;

            pausedForNoListeners = true;
            long now = System.currentTimeMillis();
            remainingRoundMillis = remainingMillis(roundDeadlineMillis, options.roundTimeMillis(), now);
            remainingSnippetMillis = remainingMillis(snippetStopAtMillis,
                    currentRound == null ? options.clipMillis() : currentRound.currentClipMs, now);
            remainingHintMillis = remainingOptionalMillis(hintDeadlineMillis, now);
            remainingReplayMillis = remainingOptionalMillis(replayDeadlineMillis, now);
            remainingCountdownMillis = remainingOptionalMillis(countdownDeadlineMillis, now);
            cancelRoundTimers();

            MessageChannel channel = channel();
            if(channel != null && currentRound != null && !currentRound.ended)
                channel.sendMessage(bot.getConfig().getWarning()
                        + " Guess the music is paused because nobody is listening in voice. It will resume when someone comes back.").queue();
        }

        private synchronized void resumeForListeners()
        {
            if(finished || lobby || !pausedForNoListeners)
                return;

            pausedForNoListeners = false;
            MessageChannel channel = channel();
            if(channel != null)
                channel.sendMessage(bot.getConfig().getSuccess() + " Guess the music resumed.").queue();

            if(currentRound == null || currentRound.ended)
                return;

            AudioHandler handler = bot.getPlayerManager().setUpHandler(guild());
            if(currentRound.playbackStarted && handler.getPlayer().getPlayingTrack() != null
                    && handler.getRequestMetadata().isGuessGame())
            {
                long windowId = openSnippetWindow();
                scheduleSnippetStopper(handler, remainingSnippetMillis > 0L ? remainingSnippetMillis : currentRound.currentClipMs,
                        windowId);
                scheduleRoundTimeout(remainingRoundMillis > 0L ? remainingRoundMillis : options.roundTimeMillis());
                resumePendingRoundTimers();
                maybePrefetchNextRound();
                return;
            }

            if(!startRoundPlayback(!currentRound.messageSent, currentRound.currentClipMs,
                    remainingRoundMillis > 0L ? remainingRoundMillis : options.roundTimeMillis()))
                reveal("I could not resume playback for this round.");
            else
            {
                resumePendingRoundTimers();
                maybePrefetchNextRound();
            }
        }

        private void resumePendingRoundTimers()
        {
            if(currentRound == null || currentRound.ended)
                return;
            if(snippetStopper == null && currentRound.correctUsers.isEmpty() && options.hintsEnabled
                    && currentRound.hintCount < options.hintReplays && remainingHintMillis > 0L)
                scheduleNextHint(remainingHintMillis);
            else if(snippetStopper == null && shouldReplayCurrentClip() && remainingReplayMillis > 0L)
                scheduleNextReplay(remainingReplayMillis);
            if(remainingCountdownMillis > 0L && countdownTask == null && roundDeadlineMillis > 0L)
            {
                countdownDeadlineMillis = System.currentTimeMillis() + remainingCountdownMillis;
                countdownTask = bot.getThreadpool().schedule(this::announceCountdown,
                        remainingCountdownMillis, TimeUnit.MILLISECONDS);
            }
            remainingHintMillis = 0L;
            remainingReplayMillis = 0L;
            remainingCountdownMillis = 0L;
        }

        private synchronized void endRound(String reason)
        {
            if(finished || currentRound == null || currentRound.ended)
                return;
            currentRound.ended = true;
            cancelRoundTimers();

            publishRevealMessage(reason);

            boolean finishAfterRound = shouldFinishAfterRound();
            if(playPostRevealContinuation(finishAfterRound
                    ? () -> finish("Game complete.")
                    : () -> nextRound("Next round")))
                return;

            bot.getPlayerManager().setUpHandler(guild()).stopGuessMusicSnippet();
            if(finishAfterRound)
            {
                finish("Game complete.");
                return;
            }
            scheduleNextRoundAfter(BETWEEN_ROUNDS_SECONDS * 1000L);
        }

        private boolean playPostRevealContinuation(Runnable afterPlayback)
        {
            if(currentRound == null || currentRound.track == null)
                return false;

            long duration = currentRound.track.getDuration();
            if(duration == Long.MAX_VALUE)
                return false;

            long continuationMs = Math.max(0L, currentRound.startMs + currentRound.currentClipMs);
            long startMs = continuationMs;
            Highlight highlight = resolveRoundHighlight();
            if(highlight != null && (highlight.isManual() || highlight.getPositionMillis() > continuationMs + 1_000L))
                startMs = highlight.getPositionMillis();
            long remainingMs = duration - startMs;
            if(remainingMs < 1_000L)
                return false;

            long playMs = Math.min(POST_REVEAL_PLAYBACK_SECONDS * 1000L, remainingMs);
            long fadeMs = Math.min(POST_REVEAL_FADE_MS, Math.max(0L, playMs / 3L));
            AudioHandler handler = bot.getPlayerManager().setUpHandler(guild());
            if(!handler.playGuessMusicSnippet(currentRound.track, startMs, null, playMs, fadeMs))
                return false;

            scheduleAfterAnswerPlayback(playMs, afterPlayback);
            return true;
        }

        private void prepareHighlight(Round round, long firstAudibleMs)
        {
            // The cache was already consulted off-lock before the round began (lookupCachedHighlight),
            // so a cache hit needs no executor work; only the expensive analysis is scheduled here.
            if(round.highlight != null)
                return;
            Set<String> keys = highlightKeys(round.track, round.answer);
            long clueEndMs = round.startMs + round.currentClipMs;
            round.highlightFuture = highlightExecutor.submit(() ->
            {
                Highlight auto = GuessMusicHighlightAnalyzer.findHighlight(round.track, firstAudibleMs, clueEndMs).orElse(null);
                if(auto == null)
                    return null;

                boolean shouldSave = false;
                synchronized(Session.this)
                {
                    if(round.highlight == null || !round.highlight.isManual())
                    {
                        round.highlight = auto;
                        shouldSave = true;
                    }
                }
                if(shouldSave)
                    saveHighlight(keys, auto);
                return auto;
            });
        }

        private synchronized Highlight resolveRoundHighlight()
        {
            if(currentRound == null)
                return null;
            if(currentRound.highlight != null)
                return currentRound.highlight;
            Future<Highlight> future = currentRound.highlightFuture;
            if(future == null || !future.isDone())
                return null;
            try
            {
                currentRound.highlight = future.get();
                return currentRound.highlight;
            }
            catch(Exception ex)
            {
                LOG.debug("Failed to read completed guess music highlight analysis", ex);
                return null;
            }
        }

        private synchronized void setManualHighlight(CommandContext event, String timestamp)
        {
            if(currentRound == null)
            {
                event.replyWarning("There is no current round to correct.");
                return;
            }

            Long position = parseManualHighlightPosition(timestamp);
            if(position == null)
            {
                event.replyError("Invalid highlight time. Use `MM:SS`, `HH:MM:SS`, `90s`, or omit it to use the current playback position.");
                return;
            }

            long duration = currentRound.track.getDuration();
            if(duration != Long.MAX_VALUE && (position < 0L || position >= duration - 1_000L))
            {
                event.replyError("Cannot set highlight to `" + TimeUtil.formatTime(position)
                        + "` because this track is `" + TimeUtil.formatTime(duration) + "` long.");
                return;
            }

            Highlight manual = new Highlight(position, 1d, true);
            currentRound.highlight = manual;
            if(currentRound.highlightFuture != null)
                currentRound.highlightFuture.cancel(true);
            saveHighlight(highlightKeys(currentRound.track, currentRound.answer), manual);
            event.replySuccess("Saved this song's reveal highlight at `" + TimeUtil.formatTime(position) + "`.");
        }

        private Long parseManualHighlightPosition(String timestamp)
        {
            if(timestamp != null && !timestamp.isBlank())
            {
                TimeUtil.SeekTime parsed = TimeUtil.parseTime(timestamp.trim());
                if(parsed == null || parsed.relative)
                    return null;
                return parsed.milliseconds;
            }

            AudioHandler handler = bot.getPlayerManager().setUpHandler(guild());
            AudioTrack playing = handler.getPlayer().getPlayingTrack();
            if(playing != null && handler.getRequestMetadata().isGuessGame())
                return Math.max(0L, playing.getPosition());
            return Math.max(0L, currentRound.startMs + currentRound.currentClipMs);
        }

        private ParsedTitle answerFor(AudioTrack track, TrackReference reference)
        {
            ParsedTitle trackAnswer = GuessMusicTitleMatcher.parse(track.getInfo().title, track.getInfo().author);
            return reference.hasKnownTitle()
                    ? GuessMusicTitleMatcher.parse(reference.title, reference.author).withAliasesFrom(trackAnswer)
                    : trackAnswer;
        }

        private Set<String> highlightKeys(AudioTrack track, ParsedTitle answer)
        {
            Set<String> keys = new HashSet<>(TrackIdentity.keys(track));
            String songKey = TrackIdentity.songKey(answer.getTitle(), answer.getArtist());
            if(songKey != null)
                keys.add(songKey);
            return keys;
        }

        private void saveHighlight(Set<String> keys, Highlight highlight)
        {
            if(highlightStore == null || highlight == null)
                return;
            try
            {
                highlightStore.save(keys, highlight.getPositionMillis(), highlight.getConfidence(), highlight.isManual());
            }
            catch(RuntimeException ex)
            {
                LOG.debug("Failed to write guess music highlight cache", ex);
            }
        }

        private void scheduleAfterAnswerPlayback(long delayMs, Runnable afterPlayback)
        {
            cancelAnswerTimer();
            long delay = Math.max(1_000L, delayMs);
            answerStopper = bot.getThreadpool().schedule(() ->
            {
                synchronized(Session.this)
                {
                    answerStopper = null;
                }
                Guild guild = guild();
                if(guild != null)
                    bot.getPlayerManager().setUpHandler(guild).stopGuessMusicSnippet();
                synchronized(Session.this)
                {
                    if(finished)
                        return;
                    afterPlayback.run();
                }
            }, delay, TimeUnit.MILLISECONDS);
        }

        private void scheduleNextRoundAfter(long delayMs)
        {
            scheduleAfterAnswerPlayback(delayMs, () -> nextRound("Next round"));
        }

        private MessageCreateData revealMessage(String reason)
        {
            AudioTrack track = currentRound.track;
            ParsedTitle answer = currentRound.answer;
            String uri = track.getInfo().uri == null ? "" : track.getInfo().uri;
            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(guild().getSelfMember().getColor())
                    .setTitle("Round " + currentRound.number + " Answer")
                    .setDescription(reason)
                    .addField("Song", FormatUtil.filter(answer.getTitle()), true)
                    .addField("Artist", answer.getArtist().isBlank() ? "Unknown" : FormatUtil.filter(answer.getArtist()), true)
                    .addField("Correct", currentRound.correctUsers.isEmpty() ? "No correct guesses" : formatCorrectUsers(), false);
            if(!currentRound.idkUsers.isEmpty())
                embed.addField("Passed", formatPassedUsers(), false);
            embed.addField("Leaderboard", leaderboard(10), false);
            String thumbnailUrl = AudioHandler.getNowPlayingThumbnail(track);
            if(thumbnailUrl != null)
                embed.setThumbnail(thumbnailUrl);
            if(!uri.isBlank())
                embed.addField("Source", uri, false);
            return new MessageCreateBuilder().setEmbeds(embed.build()).build();
        }

        private void publishRevealMessage(String reason)
        {
            MessageChannel channel = channel();
            if(channel == null)
                return;

            MessageCreateData reveal = revealMessage(reason);
            long messageId = currentRound.messageId;
            if(messageId <= 0L)
            {
                channel.sendMessage(reveal).queue();
                return;
            }

            channel.editMessageById(messageId, MessageEditData.fromCreateData(reveal))
                    .setComponents(Collections.emptyList())
                    .queue(message -> {}, failure -> channel.sendMessage(reveal).queue());
        }

        private void closeRoundMessageWithoutAnswer(String reason)
        {
            if(currentRound == null || currentRound.messageId <= 0L)
                return;
            MessageChannel channel = channel();
            if(channel == null)
                return;

            MessageEditData closed = new MessageEditBuilder()
                    .setContent(bot.getConfig().getWarning() + " " + reason)
                    .setEmbeds(Collections.emptyList())
                    .setComponents(Collections.emptyList())
                    .build();
            channel.editMessageById(currentRound.messageId, closed)
                    .queue(message -> {}, failure -> LOG.debug("Failed to close guess music round message", failure));
        }

        private String formatCorrectUsers()
        {
            StringBuilder builder = new StringBuilder();
            int rank = 1;
            for(Long userId : currentRound.correctUsers)
            {
                if(builder.length() > 0)
                    builder.append('\n');
                builder.append('`').append(rank++).append(".` <@").append(userId).append('>');
            }
            return builder.toString();
        }

        private String formatPassedUsers()
        {
            StringBuilder builder = new StringBuilder();
            for(Long userId : currentRound.idkUsers)
            {
                if(builder.length() > 0)
                    builder.append(", ");
                builder.append("<@").append(userId).append('>');
            }
            return builder.toString();
        }

        private boolean shouldFinishAfterRound()
        {
            if(options.winMode == WinMode.ENDLESS)
                return false;
            if(options.winMode == WinMode.ROUNDS)
                return roundNumber >= options.rounds;
            return scores.values().stream().anyMatch(score -> score.points >= options.targetPoints);
        }

        private synchronized void finish(String reason)
        {
            if(finished)
                return;
            boolean closeActiveRound = currentRound != null && !currentRound.ended;
            finished = true;
            awaitingRound = false;
            preparing = false;
            preparedRound = null;
            hostWaitActive = false;
            cancelHostIdleTimer();
            cancelRoundTimers();
            cancelAnswerTimer();
            if(lobby)
                closeLobbyMessage(bot.getConfig().getWarning() + " " + reason);
            else
            {
                if(closeActiveRound)
                    closeRoundMessageWithoutAnswer(reason);
                awardEconomyRewards();
            }
            bot.getPlayerManager().setUpHandler(guild()).endGuessMusicMode();
            sessions.remove(guildId, this);
            MessageChannel channel = channel();
            if(channel != null)
                channel.sendMessage(finalMessage(reason)).queue();
        }

        /**
         * Awards economy rewards when a started game ends: every participant gets a
         * "game played" credit, and the top scorer(s) with a positive score earn a win.
         */
        private void awardEconomyRewards()
        {
            // Hosted games are excluded from all guess economy rewards (play/win/correct) to prevent
            // farming with hand-picked songs; participants only keep their passive listening XP.
            if(options.hosted)
                return;
            var economy = bot.getEconomyService();
            if(economy == null || !economy.isEnabled() || scores.isEmpty())
                return;
            int best = scores.values().stream().mapToInt(score -> score.points).max().orElse(0);
            net.dv8tion.jda.api.entities.channel.middleman.MessageChannel announceChannel = channel();
            for(Map.Entry<Long, PlayerScore> entry : scores.entrySet())
            {
                economy.recordGamePlayed(entry.getKey(), announceChannel);
                if(best > 0 && entry.getValue().points == best)
                    economy.recordGuessWin(entry.getKey(), announceChannel);
            }
        }

        private synchronized void finishWithoutRestore()
        {
            finished = true;
            awaitingRound = false;
            preparing = false;
            preparedRound = null;
            hostWaitActive = false;
            cancelHostIdleTimer();
            cancelAnswerTimer();
            sessions.remove(guildId, this);
        }

        private MessageCreateData finalMessage(String reason)
        {
            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(guild().getSelfMember().getColor())
                    .setTitle("Guess The Music Finished")
                    .setDescription(reason)
                    .addField("Final Leaderboard", leaderboard(15), false);
            return new MessageCreateBuilder().setEmbeds(embed.build()).build();
        }

        private String statusMessage()
        {
            if(lobby)
                return bot.getConfig().getSuccess() + " Guess the music lobby is waiting for the host to press Start.";
            return bot.getConfig().getSuccess() + " Guess the music is on round `" + roundNumber + "`. Use `/g` to guess.\n"
                    + leaderboard(10);
        }

        private MessageCreateData statusCreateMessage()
        {
            return new MessageCreateBuilder().setContent(statusMessage()).build();
        }

        private String leaderboard(int limit)
        {
            List<PlayerScore> ordered = scores.values().stream()
                    .sorted(Comparator.comparingInt((PlayerScore score) -> score.points).reversed()
                            .thenComparing(score -> score.name.toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
            if(ordered.isEmpty())
                return "`No scores yet`";

            StringBuilder builder = new StringBuilder();
            for(int i = 0; i < ordered.size() && i < limit; i++)
            {
                PlayerScore score = ordered.get(i);
                if(builder.length() > 0)
                    builder.append('\n');
                builder.append('`').append(i + 1).append(".` ")
                        .append(score.name)
                        .append(" - `").append(score.points).append("` pts, `")
                        .append(score.correct).append("` correct");
            }
            return builder.toString();
        }

        private boolean canControl(Member member, User user)
        {
            if(user != null && user.getIdLong() == hostId)
                return true;
            if(user != null && user.getIdLong() == bot.getConfig().getOwnerId())
                return true;
            if(member == null)
                return false;
            if(member.hasPermission(Permission.MANAGE_SERVER))
                return true;
            Settings settings = bot.getSettingsManager().getSettings(guildId);
            Role dj = settings.getRole(guild());
            return dj != null && (member.getRoles().contains(dj) || dj.getIdLong() == guildId);
        }

        private Guild guild()
        {
            return bot.getJDA().getGuildById(guildId);
        }

        private MessageChannel channel()
        {
            Guild guild = guild();
            return guild == null ? null : guild.getTextChannelById(channelId);
        }

        private boolean isAloneInVoice()
        {
            Guild guild = guild();
            if(guild == null || guild.getAudioManager().getConnectedChannel() == null)
                return false;
            AudioChannel channel = guild.getAudioManager().getConnectedChannel();
            return channel.getMembers().stream()
                    .noneMatch(member -> member.getVoiceState() != null
                            && !member.getUser().isBot()
                            && !member.getVoiceState().isDeafened());
        }

        private void cancelRoundTimers()
        {
            if(roundTimeout != null)
                roundTimeout.cancel(false);
            if(snippetStopper != null)
                snippetStopper.cancel(false);
            cancelHintTimer();
            cancelReplayTimer();
            cancelCountdownTimer();
            roundTimeout = null;
            snippetStopper = null;
            activeSnippetWindowId = 0L;
            roundDeadlineMillis = 0L;
            snippetStopAtMillis = 0L;
        }

        private void cancelHintTimer()
        {
            if(hintTask != null)
                hintTask.cancel(false);
            hintTask = null;
            hintDeadlineMillis = 0L;
        }

        private void cancelReplayTimer()
        {
            if(replayTask != null)
                replayTask.cancel(false);
            replayTask = null;
            replayDeadlineMillis = 0L;
        }

        private void cancelCountdownTimer()
        {
            if(countdownTask != null)
                countdownTask.cancel(false);
            countdownTask = null;
            countdownDeadlineMillis = 0L;
        }

        private void cancelAnswerTimer()
        {
            if(answerStopper != null)
                answerStopper.cancel(false);
            answerStopper = null;
        }

        private long remainingMillis(long deadlineMillis, long fallbackMillis, long nowMillis)
        {
            if(deadlineMillis <= 0L)
                return Math.max(1_000L, fallbackMillis);
            return Math.max(1_000L, deadlineMillis - nowMillis);
        }

        private long remainingOptionalMillis(long deadlineMillis, long nowMillis)
        {
            if(deadlineMillis <= 0L)
                return 0L;
            return Math.max(1_000L, deadlineMillis - nowMillis);
        }
    }

    private final class RoundLoadHandler implements AudioLoadResultHandler
    {
        private final Session session;
        private final TrackReference reference;
        private final int attempt;
        private final int queryIndex;

        private RoundLoadHandler(Session session, TrackReference reference, int attempt, int queryIndex)
        {
            this.session = session;
            this.reference = reference;
            this.attempt = attempt;
            this.queryIndex = queryIndex;
        }

        @Override
        public void trackLoaded(AudioTrack track)
        {
            AudioTrack selected = isCandidate(track, reference, session) ? track : null;
            if(selected == null)
                session.loadReference(reference, attempt, queryIndex + 1);
            else
                session.startRound(selected, reference, attempt);
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist)
        {
            List<AudioTrack> candidates = playlist.getTracks().stream()
                    .filter(track -> isCandidate(track, reference, session))
                    .collect(Collectors.toList());
            if(candidates.isEmpty())
            {
                session.loadReference(reference, attempt, queryIndex + 1);
                return;
            }
            AudioTrack selected = chooseCandidate(candidates, reference);
            if(selected == null)
            {
                session.loadReference(reference, attempt, queryIndex + 1);
                return;
            }
            session.startRound(selected, reference, attempt);
        }

        @Override
        public void noMatches()
        {
            session.loadReference(reference, attempt, queryIndex + 1);
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            LOG.warn("Guess music failed to load '{}': {}", reference.queries.get(queryIndex), throwable.getMessage());
            session.loadReference(reference, attempt, queryIndex + 1);
        }
    }

    private boolean isCandidate(AudioTrack track, TrackReference reference, Session session)
    {
        if(track == null || track.getInfo() == null || track.getInfo().isStream)
            return false;
        if(bot.getConfig().isTooLong(track))
            return false;
        if(reference.host)
        {
            // The host explicitly chose this song: trust it. Skip cover/remix/live/dedupe vetting and
            // the artist/title match (those exist to keep the bot's auto-discovery on-target), and allow
            // the host to repeat a song. Only require a usable title for the answer matcher.
            return hasUsableTitle(GuessMusicTitleMatcher.parse(track.getInfo().title, track.getInfo().author));
        }
        long duration = track.getDuration();
        if(duration == Long.MAX_VALUE || duration < 20_000L || duration > 15 * 60_000L)
            return false;
        ParsedTitle parsed = GuessMusicTitleMatcher.parse(track.getInfo().title, track.getInfo().author);
        if(!hasUsableTitle(parsed))
            return false;
        if(containsAny(session.usedKeys, trackIdentityKeys(track)))
        {
            LOG.debug("Skipping duplicate guess music candidate '{}'", track.getInfo().title);
            return false;
        }
        if(reference.discovery && containsAny(session.knownSongKeys, trackSongKeys(track)))
        {
            LOG.debug("Skipping discovery candidate '{}' that duplicates an un-played known song", track.getInfo().title);
            return false;
        }
        String candidateText = GuessMusicTitleMatcher.normalize(track.getInfo().title + " " + track.getInfo().author);
        if(isBlockedAudioCandidate(track.getInfo().title, track.getInfo().author))
            return false;
        if(isRemixCandidate(candidateText))
            return false;
        if(reference.hasKnownTitle())
        {
            // A titled reference (a known song, or a Spotify-sourced discovery song) must actually be
            // that song — this is what stops a discovery search returning a cover of a different song.
            ParsedTitle expected = GuessMusicTitleMatcher.parse(reference.title, reference.author);
            if(!GuessMusicTitleMatcher.matches(parsed.getTitle(), expected, MatchMode.FORGIVING))
                return false;
            if(reference.discovery || session.options.hasArtistFilter())
            {
                if(!artistMetadataMatches(reference.author, parsed.getArtist()))
                    return false;
            }
            else if(!artistMatches(reference.author, parsed.getArtist(), candidateText))
                return false;
        }
        else if(reference.discovery && reference.author != null && !reference.author.isBlank())
        {
            if(!artistMetadataMatches(reference.author, parsed.getArtist()))
                return false;
        }
        return true;
    }

    private AudioTrack chooseCandidate(List<AudioTrack> candidates, TrackReference reference)
    {
        if(candidates == null || candidates.isEmpty())
            return null;

        List<AudioTrack> sorted = candidates.stream()
                .sorted(Comparator.comparingInt(track -> -candidateScore(track, reference)))
                .collect(Collectors.toList());
        int maxScore = candidateScore(sorted.get(0), reference);
        List<AudioTrack> pool = sorted.stream()
                .filter(track -> candidateScore(track, reference) >= maxScore - 25)
                .limit(8)
                .collect(Collectors.toList());
        if(pool.isEmpty())
            pool = Collections.singletonList(sorted.get(0));

        AudioTrack selected = pool.get(random.nextInt(pool.size()));
        if(LOG.isDebugEnabled())
        {
            LOG.debug("Guess music query '{}' selected '{}' from {}/{} usable candidates",
                    reference.queries.isEmpty() ? "" : reference.queries.get(0),
                    selected.getInfo().title, pool.size(), candidates.size());
        }
        return selected;
    }

    private int candidateScore(AudioTrack track, TrackReference reference)
    {
        String text = GuessMusicTitleMatcher.normalize(track.getInfo().title + " " + track.getInfo().author);
        int score = 0;
        if(text.contains("lyric video") || text.contains("lyrics video"))
            score += 45;
        if(text.contains("lyrics") || text.contains("lyric"))
            score += 25;
        if(text.contains("official audio"))
            score += 50;
        if((" " + text + " ").contains(" topic "))
            score += 35;
        if(text.contains("visualizer"))
            score += 15;
        if(text.contains("official"))
            score += 10;
        if(text.contains("official music video") || text.contains("official video") || text.contains("music video"))
            score -= 90;
        if(isBlockedAudioCandidate(track.getInfo().title, track.getInfo().author))
            score -= 100;
        if(text.contains("remix") || text.contains("nightcore") || text.contains("sped up") || text.contains("slowed"))
            score -= 60;
        if((" " + text + " ").contains(" live "))
            score -= 20;
        if(reference.author != null && !reference.author.isBlank()
                && text.contains(GuessMusicTitleMatcher.normalize(reference.author)))
            score += 25;
        return score;
    }

    static boolean isBlockedAudioCandidate(String normalizedText)
    {
        String normalized = normalizedText == null ? "" : normalizedText.trim();
        return hasBlockedAudioTerms(normalized)
                || isLivePerformanceCandidate("", normalized)
                || isAcousticVersionCandidate("", normalized, "");
    }

    static boolean isBlockedAudioCandidate(String title, String author)
    {
        String normalizedText = GuessMusicTitleMatcher.normalize((title == null ? "" : title)
                + " " + (author == null ? "" : author));
        return hasBlockedAudioTerms(normalizedText)
                || isCoverCandidate(title)
                || isLivePerformanceCandidate(title, normalizedText)
                || isAcousticVersionCandidate(title, normalizedText, author);
    }

    // Word-boundary matching so "cover" no longer false-positives on "discover"/"undercover"/
    // "recovery"/"hardcover" and "review" no longer hits "preview", while still catching the plural
    // and past forms ("instrumentals", "trailers", "covered", ...) the old substring match blocked.
    private static final java.util.regex.Pattern BLOCKED_AUDIO_TERMS = java.util.regex.Pattern.compile(
            "\\b(?:cover(?:s|ed)?|karaokes?|instrumentals?|reactions?|reviews?|trailers?|teasers?)\\b"
                    + "|\\btribute to\\b|\\boriginally (?:by|performed)\\b");

    private static boolean hasBlockedAudioTerms(String normalizedText)
    {
        return normalizedText != null && BLOCKED_AUDIO_TERMS.matcher(normalizedText).find();
    }

    private static boolean isCoverCandidate(String rawTitle)
    {
        if(rawTitle == null || rawTitle.isBlank())
            return false;
        // Bracketed cover credits like "(cover)", "[piano cover]", "(cover by X)" — the \\b keeps
        // "discover"/"undercover" inside brackets from matching.
        return rawTitle.matches("(?i).*[\\[({][^\\])}]*\\bcover(?:ed)?(?:\\s+(?:by|of|version))?\\b[^\\[({]*[\\])}].*");
    }

    private static boolean isLivePerformanceCandidate(String rawTitle, String normalizedText)
    {
        String padded = " " + (normalizedText == null ? "" : normalizedText.trim()) + " ";
        if(!padded.contains(" live "))
            return false;

        String title = rawTitle == null ? "" : rawTitle;
        if(title.matches("(?i).*\\[\\s*live(?:\\s+(?:version|performance|session))?\\s*\\].*")
                || title.matches("(?i).*\\(\\s*live(?:\\s+(?:version|performance|session))?\\s*\\).*")
                || title.matches("(?i).*\\{\\s*live(?:\\s+(?:version|performance|session))?\\s*\\}.*"))
            return true;

        return padded.matches(".*\\slive\\s+(?:at|from|in|on|version|performance|session|concert|tour|video)\\b.*")
                || padded.matches(".*\\b(?:concert|tour|performance|session)\\b.*\\blive\\b.*")
                || padded.matches(".*\\blive\\b.*\\b(?:concert|tour|performance|session)\\b.*");
    }

    private static boolean isAcousticVersionCandidate(String rawTitle, String normalizedText, String author)
    {
        String padded = " " + (normalizedText == null ? "" : normalizedText.trim()) + " ";
        if(!padded.matches(".*\\b(?:acoustic|piano|unplugged|stripped)\\b.*"))
            return false;

        ParsedTitle parsed = rawTitle == null || rawTitle.isBlank()
                ? null
                : GuessMusicTitleMatcher.parse(rawTitle, author);
        if(parsed != null && isStandaloneVersionTitle(parsed.getNormalizedTitle()))
            return false;

        String title = rawTitle == null ? "" : rawTitle;
        if(title.matches("(?i).*\\[\\s*(?:acoustic|piano|unplugged|stripped)(?:\\s+(?:version|session|performance|live|audio))?\\s*\\].*")
                || title.matches("(?i).*\\(\\s*(?:acoustic|piano|unplugged|stripped)(?:\\s+(?:version|session|performance|live|audio))?\\s*\\).*")
                || title.matches("(?i).*\\{\\s*(?:acoustic|piano|unplugged|stripped)(?:\\s+(?:version|session|performance|live|audio))?\\s*\\}.*"))
            return true;

        return padded.matches(".*\\b(?:acoustic|piano|unplugged|stripped)\\s+(?:version|session|performance|live|audio)\\b.*")
                || padded.matches(".*\\b(?:official\\s+)?(?:acoustic|piano|unplugged|stripped)\\s+(?:version|session|performance|audio)\\b.*")
                || title.matches("(?i).+\\s+[\\p{Pd}:|]\\s*(?:acoustic|piano|unplugged|stripped)\\s*$");
    }

    private static boolean isStandaloneVersionTitle(String normalizedTitle)
    {
        return "acoustic".equals(normalizedTitle)
                || "piano".equals(normalizedTitle)
                || "unplugged".equals(normalizedTitle)
                || "stripped".equals(normalizedTitle);
    }

    // Word-boundary matching so an unrelated title that merely contains a marker substring
    // (e.g. "remixology") is not mistaken for a derivative version.
    private static final java.util.regex.Pattern REMIX_TERMS = java.util.regex.Pattern.compile(
            "\\b(?:remix(?:ed|es)?|nightcore|daycore|sped up|slowed(?: down)?)\\b");

    static boolean isRemixCandidate(String normalizedText)
    {
        return normalizedText != null && REMIX_TERMS.matcher(normalizedText).find();
    }

    private static boolean artistMatches(String expectedArtist, String candidateArtist, String candidateText)
    {
        String expected = GuessMusicTitleMatcher.normalize(expectedArtist);
        if(expected.isEmpty())
            return true;
        String candidate = GuessMusicTitleMatcher.normalize(candidateArtist);
        return (!candidate.isEmpty() && (candidate.contains(expected) || expected.contains(candidate)))
                || candidateText.contains(expected);
    }

    static boolean artistMetadataMatches(String expectedArtist, String candidateArtist)
    {
        String expected = compactArtist(expectedArtist);
        if(expected.isEmpty())
            return true;
        String candidate = compactArtist(candidateArtist);
        return !candidate.isEmpty() && (candidate.contains(expected) || expected.contains(candidate));
    }

    private static String compactArtist(String value)
    {
        return GuessMusicTitleMatcher.normalize(GuessMusicTitleMatcher.cleanArtist(value)).replace(" ", "");
    }

    private static final class TrackReference
    {
        private final List<String> queries;
        private final String title;
        private final String author;
        private final boolean discovery;
        private final String source;
        // A song the host explicitly chose for a hosted game: it bypasses the cover/remix/dedupe
        // vetting (the host's pick is their choice) and never repopulates the bot's discovery pool.
        private final boolean host;

        private TrackReference(List<String> queries, String title, String author, boolean discovery, String source)
        {
            this(queries, title, author, discovery, source, false);
        }

        private TrackReference(List<String> queries, String title, String author, boolean discovery, String source,
                               boolean host)
        {
            this.queries = queries;
            this.title = title;
            this.author = author;
            this.discovery = discovery;
            this.source = source;
            this.host = host;
        }

        private String normalizedKey()
        {
            return duplicateKey(queries.isEmpty() ? "" : queries.get(0), title, author);
        }

        private boolean hasKnownTitle()
        {
            return title != null && !title.isBlank();
        }
    }

    // A fully loaded, cover-checked, scanned round that has been prepared ahead of time and is ready
    // to be committed and played (its round number is assigned only when it actually starts).
    private static final class PreparedRound
    {
        private final AudioTrack track;
        private final TrackReference reference;
        private final GuessMusicAudioScanner.ScanResult scan;
        private final Highlight cachedHighlight;

        private PreparedRound(AudioTrack track, TrackReference reference,
                              GuessMusicAudioScanner.ScanResult scan, Highlight cachedHighlight)
        {
            this.track = track;
            this.reference = reference;
            this.scan = scan;
            this.cachedHighlight = cachedHighlight;
        }
    }

    private static final class Round
    {
        private final int number;
        private final AudioTrack track;
        private final ParsedTitle answer;
        private final boolean discovery;
        private final String referenceKey;
        private final long startMs;
        private final long originalClipMs;
        private final Map<Long, Integer> guessesByUser = new HashMap<>();
        private final List<Long> correctUsers = new ArrayList<>();
        private final Set<Long> idkUsers = new HashSet<>();
        private long currentClipMs;
        private int hintCount;
        private boolean playbackStarted;
        private boolean messageSent;
        private long messageId;
        private long startedAtMillis;
        private boolean ended;
        private Highlight highlight;
        private Future<Highlight> highlightFuture;

        private Round(int number, AudioTrack track, ParsedTitle answer, boolean discovery, String referenceKey,
                      long startMs, long currentClipMs, long originalClipMs)
        {
            this.number = number;
            this.track = track;
            this.answer = answer;
            this.discovery = discovery;
            this.referenceKey = referenceKey;
            this.startMs = startMs;
            this.currentClipMs = currentClipMs;
            this.originalClipMs = originalClipMs;
        }
    }

    private static final class PlayerScore
    {
        private final long userId;
        private final String name;
        private int points;
        private int correct;

        private PlayerScore(long userId, String name)
        {
            this.userId = userId;
            this.name = name;
        }
    }

    public static class Options
    {
        private GuessMode mode = GuessMode.CLASSIC;
        private WinMode winMode = WinMode.ROUNDS;
        private InputMode inputMode = InputMode.PRIVATE;
        private MatchMode matchMode = MatchMode.FORGIVING;
        private ClipPosition clipPosition = ClipPosition.INTRO;
        private int rounds = DEFAULT_ROUNDS;
        private int targetPoints = DEFAULT_TARGET_POINTS;
        private int clipSeconds = GuessMode.CLASSIC.defaultSeconds;
        private int roundTimeSeconds = 0;
        private int guessesPerUser = DEFAULT_GUESSES_PER_USER;
        private int winnersPerRound = 3;
        private int knownPercent = DEFAULT_KNOWN_PERCENT;
        private boolean hintsEnabled = true;
        private int hintIntervalSeconds = DEFAULT_HINT_INTERVAL_SECONDS;
        private int hintStepSeconds = DEFAULT_HINT_STEP_SECONDS;
        private int hintReplays = DEFAULT_HINT_REPLAYS;
        private int replayIntervalSeconds = DEFAULT_REPLAY_INTERVAL_SECONDS;
        private int finalGuessSeconds = DEFAULT_FINAL_GUESS_SECONDS;
        private String playlistName;
        private String artistName;
        // Hosted mode: the host supplies every song (privately) instead of the bot building a pool.
        private boolean hosted = false;
        private int hostIdleSeconds = DEFAULT_HOST_IDLE_SECONDS;

        public static Options defaults()
        {
            return new Options();
        }

        private Options sanitize()
        {
            if(mode != GuessMode.CUSTOM)
                clipSeconds = mode.defaultSeconds;
            if(mode == GuessMode.IMPOSSIBLE && clipPosition == ClipPosition.INTRO)
                clipPosition = ClipPosition.FIRST_AUDIBLE;
            rounds = clamp(rounds, 1, 50);
            targetPoints = clamp(targetPoints, 1, 200);
            clipSeconds = clamp(clipSeconds, 1, 60);
            roundTimeSeconds = roundTimeSeconds <= 0 ? 0 : clamp(roundTimeSeconds, 5, 600);
            guessesPerUser = guessesPerUser <= 0 ? 0 : clamp(guessesPerUser, 1, 20);
            winnersPerRound = clamp(winnersPerRound, 1, 10);
            knownPercent = clamp(knownPercent, 0, 100);
            hintIntervalSeconds = clamp(hintIntervalSeconds, 5, 60);
            hintStepSeconds = clamp(hintStepSeconds, 1, 30);
            hintReplays = clamp(hintReplays, 0, 8);
            replayIntervalSeconds = clamp(replayIntervalSeconds, 5, 60);
            finalGuessSeconds = clamp(finalGuessSeconds, 5, 60);
            hostIdleSeconds = clamp(hostIdleSeconds, 15, 1800);
            playlistName = normalizeOptional(playlistName);
            artistName = normalizeArtistFilter(artistName);
            return this;
        }

        private long hostIdleMillis()
        {
            return hostIdleSeconds * 1000L;
        }

        private long clipMillis()
        {
            return clipSeconds * 1000L;
        }

        private long roundTimeMillis()
        {
            return effectiveRoundTimeSeconds() * 1000L;
        }

        private long hintIntervalMillis()
        {
            return hintIntervalSeconds * 1000L;
        }

        private long hintStepMillis()
        {
            return hintStepSeconds * 1000L;
        }

        private long replayIntervalMillis()
        {
            return replayIntervalSeconds * 1000L;
        }

        private long startPosition(AudioTrack track, long clipMs, Random random, long firstAudibleMs)
        {
            long duration = Math.max(0L, track.getDuration());
            if(duration <= clipMs || duration == Long.MAX_VALUE)
                return 0L;
            long max = Math.max(0L, duration - clipMs);
            long firstAudible = Math.max(0L, Math.min(firstAudibleMs, max));
            switch(clipPosition)
            {
                case OUTRO:
                    return Math.max(0L, duration - clipMs - 500L);
                case RANDOM:
                    long range = Math.max(0L, max - firstAudible);
                    return range <= 0L ? firstAudible : firstAudible + (long)(random.nextDouble() * (range + 1L));
                case FIRST_AUDIBLE:
                case INTRO:
                default:
                    return firstAudible;
            }
        }

        private String roundSummary()
        {
            switch(winMode)
            {
                case POINTS:
                    return "First to `" + targetPoints + "` points";
                case ENDLESS:
                    return "Endless";
                case ROUNDS:
                default:
                    return "`" + rounds + "` rounds";
            }
        }

        private String clipSummary()
        {
            return "`" + clipSeconds + "s` " + clipPosition.displayName;
        }

        private int effectiveRoundTimeSeconds()
        {
            if(roundTimeSeconds > 0)
                return roundTimeSeconds;

            int total = clipSeconds + finalGuessSeconds;
            if(hintsEnabled)
            {
                for(int i = 1; i <= hintReplays; i++)
                    total += hintIntervalSeconds + Math.min(60, clipSeconds + (i * hintStepSeconds));
            }

            int minimum = hintsEnabled && hintReplays > 0
                    ? clipSeconds + hintIntervalSeconds + hintStepSeconds + finalGuessSeconds
                    : clipSeconds + finalGuessSeconds;
            return clamp(total, Math.max(30, minimum), 600);
        }

        private String roundTimeSummary()
        {
            String value = "`" + effectiveRoundTimeSeconds() + "s`";
            return roundTimeSeconds <= 0 ? value + " auto" : value;
        }

        private String hintSummary()
        {
            if(!hintsEnabled)
                return "`off`";
            return "`" + hintReplays + "` replay" + (hintReplays == 1 ? "" : "s")
                    + ", `+" + hintStepSeconds + "s` after `" + hintIntervalSeconds + "s` idle";
        }

        private String guessLimitSummary()
        {
            return hasGuessLimit()
                    ? "`" + guessesPerUser + "` guess" + (guessesPerUser == 1 ? "" : "es") + "/user"
                    : "`unlimited` guesses";
        }

        private String artistSummary()
        {
            List<String> artists = artistFilters();
            return artists.isEmpty()
                    ? "`any`"
                    : artists.stream().map(artist -> "`" + artist + "`").collect(Collectors.joining(", "));
        }

        private String poolSummary()
        {
            if(hosted)
                return "`host-picked` songs added privately by the host";
            String pool = "`" + knownPercent + "%` known songs, discovery from same artists";
            return hasArtistFilter() ? pool + ", artists " + artistSummary() : pool;
        }

        private boolean hasArtistFilter()
        {
            return !artistFilters().isEmpty();
        }

        private List<String> artistFilters()
        {
            return parseArtistFilters(artistName);
        }

        private boolean hasGuessLimit()
        {
            return guessesPerUser > 0;
        }

        private static String normalizeOptional(String value)
        {
            if(value == null)
                return null;
            String clean = value.trim();
            return clean.isEmpty() ? null : clean;
        }

        private static String normalizeArtistFilter(String value)
        {
            List<String> artists = parseArtistFilters(value);
            return artists.isEmpty() ? null : String.join(", ", artists);
        }

        private static int clamp(int value, int min, int max)
        {
            return Math.max(min, Math.min(max, value));
        }
    }

    public enum GuessMode
    {
        CLASSIC("Classic", 15),
        HARDCORE("Hardcore", 5),
        IMPOSSIBLE("Impossible", 1),
        CUSTOM("Custom", 15);

        private final String displayName;
        private final int defaultSeconds;

        GuessMode(String displayName, int defaultSeconds)
        {
            this.displayName = displayName;
            this.defaultSeconds = defaultSeconds;
        }

        public static GuessMode parse(String value)
        {
            if(value == null)
                return CLASSIC;
            for(GuessMode mode : values())
                if(mode.name().equalsIgnoreCase(value))
                    return mode;
            return CLASSIC;
        }
    }

    public enum WinMode
    {
        ROUNDS("Rounds"),
        POINTS("Points"),
        ENDLESS("Endless");

        private final String displayName;

        WinMode(String displayName)
        {
            this.displayName = displayName;
        }

        public static WinMode parse(String value)
        {
            if(value == null)
                return ROUNDS;
            for(WinMode mode : values())
                if(mode.name().equalsIgnoreCase(value))
                    return mode;
            return ROUNDS;
        }
    }

    public enum InputMode
    {
        PRIVATE("Private"),
        CHAT("Chat"),
        BOTH("Private + chat");

        private final String displayName;

        InputMode(String displayName)
        {
            this.displayName = displayName;
        }

        public static InputMode parse(String value)
        {
            if(value == null)
                return PRIVATE;
            for(InputMode mode : values())
                if(mode.name().equalsIgnoreCase(value))
                    return mode;
            return PRIVATE;
        }
    }

    public enum ClipPosition
    {
        INTRO("intro"),
        OUTRO("outro"),
        RANDOM("random"),
        FIRST_AUDIBLE("first audible");

        private final String displayName;

        ClipPosition(String displayName)
        {
            this.displayName = displayName;
        }

        public static ClipPosition parse(String value)
        {
            if(value == null)
                return INTRO;
            String normalized = value.replace('-', '_').replace(' ', '_');
            if("firstaudible".equalsIgnoreCase(normalized))
                normalized = "first_audible";
            for(ClipPosition position : values())
                if(position.name().equalsIgnoreCase(normalized))
                    return position;
            return INTRO;
        }
    }
}
