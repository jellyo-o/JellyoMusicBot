/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.audio.filter.AudioFilterPreset;
import com.jagrosh.jmusicbot.dashboard.DashboardStatsService;
import com.jagrosh.jmusicbot.economy.EconomyService;
import com.jagrosh.jmusicbot.dashboard.DashboardStatsService.SkipInfo;
import com.jagrosh.jmusicbot.queue.AbstractQueue;
import com.jagrosh.jmusicbot.settings.AutoplayMode;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.jagrosh.jmusicbot.utils.TrackIdentity;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import java.nio.ByteBuffer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class AudioHandler extends AudioEventAdapter implements AudioSendHandler 
{
    private final static Logger LOG = LoggerFactory.getLogger(AudioHandler.class);

    public final static String PLAY_EMOJI  = "\u25B6"; // ▶
    public final static String PAUSE_EMOJI = "\u23F8"; // ⏸
    public final static String STOP_EMOJI  = "\u23F9"; // ⏹
    public final static String RESTART_EMOJI = "\u23EE"; // ⏮
    public final static String SKIP_EMOJI = "\u23ED"; // ⏭
    public final static String REPEAT_EMOJI = "\uD83D\uDD01"; // 🔁
    public final static String QUEUE_EMOJI = "\uD83D\uDCDC"; // 📜
    public final static String LIKE_EMOJI = "\u2764"; // ❤
    public final static String SHUFFLE_EMOJI = "\uD83D\uDD00"; // 🔀
    public final static String LYRICS_EMOJI = "\uD83C\uDFA4"; // 🎤
    private final static Pattern YOUTUBE_VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private final static int RECENT_TRACK_LIMIT = 25;
    private final static int NEXT_UP_TITLE_LIMIT = 64;


    private final Set<String> votes = new HashSet<>();
    
    private final PlayerManager manager;
    private final AudioPlayer audioPlayer;
    private final long guildId;
    
    private AudioFrame lastFrame;
    private AbstractQueue<QueuedTrack> queue;
    private AudioFilterPreset filterPreset = AudioFilterPreset.OFF;
    private final LinkedList<String> recentTrackKeys = new LinkedList<>();
    private final PlaybackSessionHistory playbackSessionHistory = new PlaybackSessionHistory();
    private String lastPlaylistName;
    private long lastPlaylistId;
    private boolean autoplayStopQueued = false;
    private boolean suppressAutoplayOnce = false;
    private String currentStatsSessionKey;
    private long currentTrackStartedAt;
    private SkipInfo pendingSkip;
    private boolean guessMusicMode = false;
    private final Set<AudioTrack> suppressedTrackEnds = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean guessMusicSnippetPlaying = false;
    private boolean guessMusicSnippetStarted = false;
    private Runnable guessMusicSnippetStart;
    private Runnable guessMusicSnippetEnd;
    private Consumer<String> guessMusicSnippetFailure;
    private final List<ScheduledFuture<?>> guessMusicFadeTasks = new ArrayList<>();
    private long guessMusicFadeSequence = 0L;
    private int guessMusicFadeRestoreVolume = -1;
    private AudioTrack guessMusicSavedTrack;
    private RequestMetadata guessMusicSavedMetadata;
    private long guessMusicSavedPosition;
    private boolean guessMusicSavedPaused;
    private List<QueuedTrack> guessMusicSavedQueue = new ArrayList<>();

    // Sleep timer (per-guild): either a wall-clock deadline or a number of songs to finish.
    private ScheduledFuture<?> sleepFuture;
    private long sleepDeadlineMs = 0L;
    private int sleepTracksRemaining = 0;
    private long sleepChannelId = 0L;

    // Users who have queued a song this playback session; gates minimal listening XP.
    private final Set<Long> sessionContributors = java.util.concurrent.ConcurrentHashMap.newKeySet();

    protected AudioHandler(PlayerManager manager, Guild guild, AudioPlayer player)
    {
        this.manager = manager;
        this.audioPlayer = player;
        this.guildId = guild.getIdLong();

        this.setQueueType(manager.getBot().getSettingsManager().getSettings(guildId).getQueueType());
        LOG.debug("Audio handler initialized for guild {} ({})", guild.getName(), guild.getId());
    }

    public void setQueueType(QueueType type)
    {
        queue = type.createInstance(queue);
        LOG.info("Queue type set to {} for guild {}", type, guildId);
    }

    public int addTrackToFront(QueuedTrack qtrack)
    {
        onManualEnqueue(qtrack);
        if(audioPlayer.getPlayingTrack()==null)
        {
            LOG.info("Starting track immediately at front for guild {}: {}", guildId, trackSummary(qtrack.getTrack()));
            audioPlayer.playTrack(qtrack.getTrack());
            return -1;
        }
        else if(shouldInterruptAutoplay(qtrack))
        {
            queue.addAt(0, qtrack);
            autoplayStopQueued = true;
            LOG.info("Manual front-queue request interrupted autoplay for guild {}; track={}",
                    guildId, trackSummary(qtrack.getTrack()));
            audioPlayer.stopTrack();
            return -1;
        }
        else
        {
            queue.addAt(0, qtrack);
            LOG.debug("Queued track at front for guild {}: {}; queueSize={}", guildId, trackSummary(qtrack.getTrack()), queue.size());
            updateMusicPanels();
            return 0;
        }
    }
    
    public int addTrack(QueuedTrack qtrack)
    {
        onManualEnqueue(qtrack);
        if(audioPlayer.getPlayingTrack()==null)
        {
            LOG.info("Starting track immediately for guild {}: {}", guildId, trackSummary(qtrack.getTrack()));
            audioPlayer.playTrack(qtrack.getTrack());
            return -1;
        }
        else if(shouldInterruptAutoplay(qtrack))
        {
            queue.addAt(0, qtrack);
            autoplayStopQueued = true;
            LOG.info("Manual queue request interrupted autoplay for guild {}; track={}",
                    guildId, trackSummary(qtrack.getTrack()));
            audioPlayer.stopTrack();
            return -1;
        }
        else
        {
            int position = queue.add(qtrack);
            LOG.debug("Queued track for guild {}: {}; position={}; queueSize={}", guildId, trackSummary(qtrack.getTrack()), position + 1, queue.size());
            updateMusicPanels();
            return position;
        }
    }

    public int addTracks(List<QueuedTrack> qtracks)
    {
        if(qtracks == null || qtracks.isEmpty())
            return 0;
        onManualEnqueue(qtracks.get(0));

        if(audioPlayer.getPlayingTrack()==null)
        {
            QueuedTrack first = qtracks.get(0);
            if(qtracks.size() > 1)
                queue.addAll(qtracks.subList(1, qtracks.size()));
            LOG.info("Starting first bulk track immediately for guild {}; tracks={}; first={}",
                    guildId, qtracks.size(), trackSummary(first.getTrack()));
            audioPlayer.playTrack(first.getTrack());
            return qtracks.size();
        }
        else if(shouldInterruptAutoplay(qtracks.get(0)))
        {
            queue.addAt(0, qtracks.get(0));
            if(qtracks.size() > 1)
                queue.addAll(qtracks.subList(1, qtracks.size()));
            autoplayStopQueued = true;
            LOG.info("Manual bulk queue request interrupted autoplay for guild {}; tracks={}; first={}",
                    guildId, qtracks.size(), trackSummary(qtracks.get(0).getTrack()));
            audioPlayer.stopTrack();
            return qtracks.size();
        }
        else
        {
            int firstPosition = queue.addAll(qtracks);
            LOG.debug("Queued tracks in bulk for guild {}; tracks={}; firstPosition={}; queueSize={}",
                    guildId, qtracks.size(), firstPosition + 1, queue.size());
            updateMusicPanels();
            return qtracks.size();
        }
    }

    public int addTracksToFront(List<QueuedTrack> qtracks)
    {
        if(qtracks == null || qtracks.isEmpty())
            return 0;
        onManualEnqueue(qtracks.get(0));

        if(audioPlayer.getPlayingTrack()==null)
        {
            QueuedTrack first = qtracks.get(0);
            for(int i = qtracks.size() - 1; i >= 1; i--)
                queue.addAt(0, qtracks.get(i));
            LOG.info("Starting first bulk front-track immediately for guild {}; tracks={}; first={}",
                    guildId, qtracks.size(), trackSummary(first.getTrack()));
            audioPlayer.playTrack(first.getTrack());
            return qtracks.size();
        }

        for(int i = qtracks.size() - 1; i >= 0; i--)
            queue.addAt(0, qtracks.get(i));
        if(shouldInterruptAutoplay(qtracks.get(0)))
        {
            autoplayStopQueued = true;
            LOG.info("Manual bulk front-queue request interrupted autoplay for guild {}; tracks={}; first={}",
                    guildId, qtracks.size(), trackSummary(qtracks.get(0).getTrack()));
            audioPlayer.stopTrack();
        }
        else
        {
            LOG.debug("Queued tracks in bulk at front for guild {}; tracks={}; queueSize={}",
                    guildId, qtracks.size(), queue.size());
            updateMusicPanels();
        }
        return qtracks.size();
    }
    
    public AbstractQueue<QueuedTrack> getQueue()
    {
        return queue;
    }
    
    public void stopAndClear()
    {
        cancelSleepTimer();
        AudioTrack playing = audioPlayer.getPlayingTrack();
        int queueSize = queue == null ? 0 : queue.size();
        LOG.info("Stopping player and clearing queue for guild {}; playing={}; queueSize={}",
                guildId, trackSummary(playing), queueSize);
        queue.clear();
        autoplayStopQueued = false;
        suppressAutoplayOnce = playing != null;
        clearPlaybackSessionHistory();
        audioPlayer.stopTrack();
        synchronized(this)
        {
            // Drop any stale guess-music suppression markers on a full teardown.
            suppressedTrackEnds.clear();
        }
        updateMusicPanels();
        //current = null;
    }
    
    public boolean isMusicPlaying(JDA jda)
    {
        return guild(jda).getSelfMember().getVoiceState().inAudioChannel() && audioPlayer.getPlayingTrack()!=null;
    }

    // ===== Sleep timer =====

    /** Schedules a graceful stop+disconnect after the given duration. */
    public synchronized void scheduleSleepDuration(long millis, long channelId)
    {
        cancelSleepTimerInternal();
        sleepDeadlineMs = System.currentTimeMillis() + millis;
        sleepChannelId = channelId;
        sleepFuture = manager.getBot().getThreadpool().schedule(this::onSleepDurationElapsed, millis, TimeUnit.MILLISECONDS);
        LOG.info("Sleep timer set for guild {} in {} ms", guildId, millis);
    }

    /** Schedules a stop+disconnect after the given number of songs finish playing. */
    public synchronized void scheduleSleepTracks(int tracks, long channelId)
    {
        cancelSleepTimerInternal();
        sleepTracksRemaining = Math.max(1, tracks);
        sleepChannelId = channelId;
        LOG.info("Sleep timer set for guild {} after {} track(s)", guildId, sleepTracksRemaining);
    }

    public synchronized boolean cancelSleepTimer()
    {
        boolean wasActive = sleepDeadlineMs > 0 || sleepTracksRemaining > 0;
        cancelSleepTimerInternal();
        return wasActive;
    }

    private void cancelSleepTimerInternal()
    {
        if(sleepFuture != null)
        {
            sleepFuture.cancel(false);
            sleepFuture = null;
        }
        sleepDeadlineMs = 0L;
        sleepTracksRemaining = 0;
        sleepChannelId = 0L;
    }

    public synchronized boolean isSleepActive()
    {
        return sleepDeadlineMs > 0 || sleepTracksRemaining > 0;
    }

    /** @return milliseconds left on a duration sleep, or -1 if not in duration mode */
    public synchronized long sleepRemainingMillis()
    {
        return sleepDeadlineMs > 0 ? Math.max(0, sleepDeadlineMs - System.currentTimeMillis()) : -1;
    }

    /** @return songs left to finish for a track-count sleep, or 0 if not in that mode */
    public synchronized int getSleepTracksRemaining()
    {
        return sleepTracksRemaining;
    }

    private void onSleepDurationElapsed()
    {
        long channelId;
        synchronized(this)
        {
            if(sleepDeadlineMs <= 0)
                return; // cancelled before firing
            channelId = sleepChannelId;
            sleepDeadlineMs = 0L;
            sleepFuture = null;
            sleepChannelId = 0L;
        }
        fadeOutAndStop(channelId);
    }

    /**
     * Counts a finished song toward a track-count sleep. Returns the announce
     * channel id (&ge; 0) when the timer should fire now, or -1 otherwise. The
     * channel id is read under the lock to avoid a concurrent cancel zeroing it.
     */
    private synchronized long consumeSleepTrackEnd(AudioTrackEndReason endReason)
    {
        if(sleepTracksRemaining <= 0 || endReason != AudioTrackEndReason.FINISHED)
            return -1;
        sleepTracksRemaining--;
        return sleepTracksRemaining <= 0 ? sleepChannelId : -1;
    }

    private void fadeOutAndStop(long channelId)
    {
        int startVol = audioPlayer.getVolume();
        if(audioPlayer.getPlayingTrack() == null || startVol <= 0)
        {
            finishSleep(channelId, startVol);
            return;
        }
        final int steps = 8;
        final long stepMs = 250L; // ~2 second fade
        for(int i = 1; i <= steps; i++)
        {
            final int vol = Math.max(0, startVol - (int)((long) startVol * i / steps));
            manager.getBot().getThreadpool().schedule(() ->
            {
                if(audioPlayer.getPlayingTrack() != null)
                    audioPlayer.setVolume(vol);
            }, stepMs * i, TimeUnit.MILLISECONDS);
        }
        manager.getBot().getThreadpool().schedule(() -> finishSleep(channelId, startVol),
                stepMs * (steps + 1), TimeUnit.MILLISECONDS);
    }

    private void finishSleep(long channelId, int restoreVolume)
    {
        LOG.info("Sleep timer reached for guild {}; stopping playback and disconnecting", guildId);
        stopAndClear();
        audioPlayer.setVolume(restoreVolume);
        manager.getBot().closeAudioConnection(guildId);
        if(channelId > 0 && manager.getBot().getJDA() != null)
        {
            net.dv8tion.jda.api.entities.channel.concrete.TextChannel ch =
                    manager.getBot().getJDA().getTextChannelById(channelId);
            if(ch != null)
                ch.sendMessage("💤 Sleep timer reached — stopping playback. Goodnight!").queue(m -> {}, f -> {});
        }
    }

    private void onManualEnqueue(QueuedTrack qtrack)
    {
        if(qtrack == null || qtrack.getTrack() == null)
            return;
        RequestMetadata md = qtrack.getTrack().getUserData(RequestMetadata.class);
        if(md != null && (md.origin == RequestMetadata.Origin.MANUAL || md.origin == RequestMetadata.Origin.SAVED_PLAYLIST))
        {
            cancelSleepTimer();
            // Queuing a song this session unlocks (minimal) listening XP for the requester.
            if(md.getOwner() > 0)
                sessionContributors.add(md.getOwner());
        }
    }

    /** @return true if the user has queued a song during the current playback session */
    public boolean isSessionContributor(long userId)
    {
        return sessionContributors.contains(userId);
    }

    /** Best channel to announce economy milestones for a request: where it was requested, else the music text channel. */
    private net.dv8tion.jda.api.entities.channel.middleman.MessageChannel announceChannelFor(RequestMetadata metadata)
    {
        JDA jda = manager.getBot().getJDA();
        if(jda == null)
            return null;
        if(metadata != null && metadata.getTextChannelId() > 0)
        {
            net.dv8tion.jda.api.entities.channel.concrete.TextChannel tc = jda.getTextChannelById(metadata.getTextChannelId());
            if(tc != null)
                return tc;
        }
        Guild g = jda.getGuildById(guildId);
        return g == null ? null : manager.getBot().getSettingsManager().getSettings(guildId).getTextChannel(g);
    }
    
    public Set<String> getVotes()
    {
        return votes;
    }
    
    public AudioPlayer getPlayer()
    {
        return audioPlayer;
    }

    /** @return true while a guess-the-song session has commandeered playback in this guild */
    public boolean isInGuessMusicMode()
    {
        return guessMusicMode;
    }

    public AudioFilterPreset getFilterPreset()
    {
        return filterPreset;
    }

    public void setFilterPreset(AudioFilterPreset preset)
    {
        filterPreset = preset == null ? AudioFilterPreset.OFF : preset;
        audioPlayer.setFilterFactory(filterPreset.createFactory());
        LOG.info("Audio filter set to {} for guild {}", filterPreset.getId(), guildId);
    }

    public long getGuildId()
    {
        return guildId;
    }

    public String getLastPlaylistName()
    {
        return lastPlaylistName;
    }

    public long getLastPlaylistId()
    {
        return lastPlaylistId;
    }

    public Set<String> getRecentTrackKeys()
    {
        return new HashSet<>(recentTrackKeys);
    }

    public Set<String> getPlaybackSessionTrackKeys()
    {
        return playbackSessionHistory.snapshot();
    }

    public List<PlaybackHistoryStore.Entry> getPlaybackSessionHistory()
    {
        return playbackSessionHistory.snapshotEntries();
    }

    public boolean isRecentlyPlayed(AudioTrack track)
    {
        Set<String> keys = trackKeys(track);
        for(String key : keys)
            if(recentTrackKeys.contains(key))
                return true;
        return false;
    }

    public boolean hasPlayedThisSession(AudioTrack track)
    {
        return playbackSessionHistory.contains(track);
    }

    public boolean hasPlayedThisSession(String identifier, String uri, String title, String author)
    {
        return playbackSessionHistory.contains(identifier, uri, title, author);
    }

    public long getCurrentTrackStartedAt()
    {
        return currentTrackStartedAt;
    }

    public void skipCurrentTrack(User actor, String skipType)
    {
        pendingSkip = SkipInfo.fromUser(actor, skipType);
        audioPlayer.stopTrack();
    }
    
    public RequestMetadata getRequestMetadata()
    {
        if(audioPlayer.getPlayingTrack() == null)
            return RequestMetadata.EMPTY;
        RequestMetadata rm = audioPlayer.getPlayingTrack().getUserData(RequestMetadata.class);
        return rm == null ? RequestMetadata.EMPTY : rm;
    }

    public synchronized void beginGuessMusicMode()
    {
        if(guessMusicMode)
            return;

        AudioTrack playing = audioPlayer.getPlayingTrack();
        guessMusicMode = true;
        guessMusicSavedQueue = new ArrayList<>(queue.getList());
        queue.clear();
        votes.clear();
        autoplayStopQueued = false;
        suppressAutoplayOnce = false;
        guessMusicSavedPaused = audioPlayer.isPaused();
        if(playing != null)
        {
            guessMusicSavedTrack = playing.makeClone();
            guessMusicSavedPosition = Math.max(0L, playing.getPosition());
            guessMusicSavedMetadata = playing.getUserData(RequestMetadata.class);
            guessMusicSavedTrack.setUserData(guessMusicSavedMetadata);
            suppressTrackEnd(playing);
            audioPlayer.stopTrack();
        }
        else
        {
            guessMusicSavedTrack = null;
            guessMusicSavedMetadata = null;
            guessMusicSavedPosition = 0L;
        }
        LOG.info("Guess music mode started for guild {}; savedQueueSize={}; savedTrack={}",
                guildId, guessMusicSavedQueue.size(), trackSummary(playing));
    }

    public synchronized boolean playGuessMusicSnippet(AudioTrack source, long startPositionMs, Runnable onEnd)
    {
        return playGuessMusicSnippet(source, startPositionMs, null, onEnd, null, 0L, 0L);
    }

    public synchronized boolean playGuessMusicSnippet(AudioTrack source, long startPositionMs, Runnable onEnd,
                                                      long playDurationMs, long fadeMs)
    {
        return playGuessMusicSnippet(source, startPositionMs, null, onEnd, null, playDurationMs, fadeMs);
    }

    public synchronized boolean playGuessMusicSnippet(AudioTrack source, long startPositionMs, Runnable onStart,
                                                      Runnable onEnd, Consumer<String> onFailure)
    {
        return playGuessMusicSnippet(source, startPositionMs, onStart, onEnd, onFailure, 0L, 0L);
    }

    public synchronized boolean playGuessMusicSnippet(AudioTrack source, long startPositionMs, Runnable onStart,
                                                      Runnable onEnd, Consumer<String> onFailure,
                                                      long playDurationMs, long fadeMs)
    {
        if(!guessMusicMode || source == null)
            return false;

        cancelGuessMusicFade(true);
        AudioTrack playing = audioPlayer.getPlayingTrack();
        if(guessMusicSnippetPlaying && playing != null && getRequestMetadata().isGuessGame())
        {
            guessMusicSnippetPlaying = false;
            guessMusicSnippetStarted = false;
            guessMusicSnippetStart = null;
            guessMusicSnippetEnd = null;
            guessMusicSnippetFailure = null;
            suppressTrackEnd(playing);
            audioPlayer.stopTrack();
        }

        AudioTrack snippet = source.makeClone();
        snippet.setUserData(RequestMetadata.guessGame(snippet));
        if(snippet.isSeekable())
            snippet.setPosition(Math.max(0L, startPositionMs));
        guessMusicSnippetStart = onStart;
        guessMusicSnippetEnd = onEnd;
        guessMusicSnippetFailure = onFailure;
        guessMusicSnippetStarted = false;
        guessMusicSnippetPlaying = true;
        audioPlayer.setPaused(false);
        if(fadeMs > 0L && playDurationMs > fadeMs)
            startGuessMusicFade(playDurationMs, fadeMs);
        audioPlayer.playTrack(snippet);
        LOG.info("Guess music snippet started for guild {}; start={}; track={}",
                guildId, TimeUtil.formatTime(Math.max(0L, startPositionMs)), trackSummary(snippet));
        return true;
    }

    private void startGuessMusicFade(long playDurationMs, long fadeMs)
    {
        int originalVolume = audioPlayer.getVolume();
        guessMusicFadeRestoreVolume = originalVolume;
        long token = ++guessMusicFadeSequence;
        int fadeVolume = Math.max(0, originalVolume);
        audioPlayer.setVolume(0);
        scheduleGuessMusicFade(token, 0L, fadeMs, 0, fadeVolume);

        long fadeOutDelay = Math.max(0L, playDurationMs - fadeMs);
        scheduleGuessMusicFade(token, fadeOutDelay, fadeMs, fadeVolume, 0);
    }

    private void scheduleGuessMusicFade(long token, long delayMs, long durationMs, int fromVolume, int toVolume)
    {
        int steps = 10;
        long stepDelay = Math.max(50L, durationMs / steps);
        for(int i = 0; i <= steps; i++)
        {
            int step = i;
            long delay = delayMs + (stepDelay * i);
            ScheduledFuture<?> future = manager.getBot().getThreadpool().schedule(() ->
            {
                synchronized(AudioHandler.this)
                {
                    if(token != guessMusicFadeSequence || !guessMusicSnippetPlaying)
                        return;
                    double progress = steps == 0 ? 1.0 : (double)step / steps;
                    int volume = (int)Math.round(fromVolume + ((toVolume - fromVolume) * progress));
                    audioPlayer.setVolume(Math.max(0, volume));
                }
            }, delay, TimeUnit.MILLISECONDS);
            guessMusicFadeTasks.add(future);
        }
    }

    private synchronized void cancelGuessMusicFade(boolean restoreVolume)
    {
        guessMusicFadeSequence++;
        for(ScheduledFuture<?> future : guessMusicFadeTasks)
            future.cancel(false);
        guessMusicFadeTasks.clear();
        if(restoreVolume && guessMusicFadeRestoreVolume >= 0)
            audioPlayer.setVolume(guessMusicFadeRestoreVolume);
        guessMusicFadeRestoreVolume = -1;
    }

    public synchronized void stopGuessMusicSnippet()
    {
        if(!guessMusicMode || !guessMusicSnippetPlaying || audioPlayer.getPlayingTrack() == null)
            return;
        audioPlayer.stopTrack();
        cancelGuessMusicFade(true);
    }

    public synchronized void endGuessMusicMode()
    {
        if(!guessMusicMode)
            return;

        AudioTrack playing = audioPlayer.getPlayingTrack();
        if(playing != null && getRequestMetadata().isGuessGame())
        {
            guessMusicSnippetPlaying = false;
            guessMusicSnippetStarted = false;
            guessMusicSnippetStart = null;
            guessMusicSnippetEnd = null;
            guessMusicSnippetFailure = null;
            suppressTrackEnd(playing);
            audioPlayer.stopTrack();
            cancelGuessMusicFade(true);
        }

        queue.clear();
        queue.getList().addAll(guessMusicSavedQueue);
        AudioTrack restore = guessMusicSavedTrack == null ? null : guessMusicSavedTrack.makeClone();
        RequestMetadata restoreMetadata = guessMusicSavedMetadata;
        long restorePosition = guessMusicSavedPosition;
        boolean restorePaused = guessMusicSavedPaused;

        guessMusicMode = false;
        guessMusicSavedTrack = null;
        guessMusicSavedMetadata = null;
        guessMusicSavedPosition = 0L;
        guessMusicSavedPaused = false;
        guessMusicSavedQueue = new ArrayList<>();
        guessMusicSnippetPlaying = false;
        guessMusicSnippetStarted = false;
        guessMusicSnippetStart = null;
        guessMusicSnippetEnd = null;
        guessMusicSnippetFailure = null;

        if(restore != null)
        {
            restore.setUserData(restoreMetadata);
            if(restore.isSeekable())
                restore.setPosition(Math.min(Math.max(0L, restorePosition), Math.max(0L, restore.getDuration())));
            audioPlayer.playTrack(restore);
            audioPlayer.setPaused(restorePaused);
        }
        else
        {
            updateMusicPanels();
        }
        LOG.info("Guess music mode ended for guild {}; restoredTrack={}; restoredQueueSize={}",
                guildId, trackSummary(restore), queue.size());
    }
    
    // Audio Events
    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) 
    {
        if(consumeSuppressedTrackEnd(track))
        {
            LOG.debug("Suppressed track end while transitioning guess music mode for guild {}; track={}",
                    guildId, trackSummary(track));
            return;
        }
        RequestMetadata endedMetadata = track == null ? null : track.getUserData(RequestMetadata.class);
        if(endedMetadata != null && endedMetadata.isGuessGame())
        {
            // This runs on the lavaplayer playback thread; the snippet fields are mutated
            // elsewhere only under this monitor, so take the lock here too. Capture the
            // callback under the lock and run it outside to keep audio teardown non-blocking.
            Runnable callback = null;
            synchronized(this)
            {
                if(guessMusicSnippetPlaying)
                {
                    guessMusicSnippetPlaying = false;
                    guessMusicSnippetStarted = false;
                    guessMusicSnippetStart = null;
                    callback = guessMusicSnippetEnd;
                    guessMusicSnippetEnd = null;
                    guessMusicSnippetFailure = null;
                    cancelGuessMusicFade(true);
                }
            }
            if(callback != null)
                manager.getBot().getThreadpool().submit(callback);
            LOG.debug("Guess music snippet ended for guild {}; reason={}; track={}",
                    guildId, endReason, trackSummary(track));
            return;
        }

        RepeatMode repeatMode = manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode();
        SkipInfo skipInfo = endReason == AudioTrackEndReason.STOPPED ? pendingSkip : null;
        recordTrackEnd(track, endReason, skipInfo);
        currentStatsSessionKey = null;
        currentTrackStartedAt = 0L;
        pendingSkip = null;
        long sleepChannel = consumeSleepTrackEnd(endReason);
        if(sleepChannel != -1)
        {
            LOG.info("Sleep timer (track count) reached for guild {}", guildId);
            finishSleep(sleepChannel, audioPlayer.getVolume());
            return;
        }
        LOG.info("Track ended for guild {}; reason={}; mayStartNext={}; repeatMode={}; queueSize={}; track={}",
                guildId, endReason, endReason.mayStartNext, repeatMode, queue.size(), trackSummary(track));
        // if the track ended normally, and we're in repeat mode, re-add it to the queue
        if(endReason==AudioTrackEndReason.FINISHED && repeatMode != RepeatMode.OFF)
        {
            QueuedTrack clone = new QueuedTrack(track.makeClone(), track.getUserData(RequestMetadata.class));
            if(repeatMode == RepeatMode.ALL)
            {
                queue.add(clone);
                LOG.debug("Re-added finished track to end of queue for repeat-all in guild {}", guildId);
            }
            else
            {
                queue.addAt(0, clone);
                LOG.debug("Re-added finished track to front of queue for repeat-one in guild {}", guildId);
            }
        }
        
        if(queue.isEmpty())
        {
            if(suppressAutoplayOnce)
            {
                suppressAutoplayOnce = false;
                manager.getBot().getNowplayingHandler().onTrackUpdate(guildId, null);
                player.setPaused(false);
                return;
            }
            handleEmptyQueue(player, track);
        }
        else
        {
            QueuedTrack qt = queue.pull();
            LOG.info("Starting next queued track for guild {}; remainingQueueSize={}; track={}",
                    guildId, queue.size(), trackSummary(qt.getTrack()));
            player.playTrack(qt.getTrack());
        }
    }

    private synchronized void suppressTrackEnd(AudioTrack track)
    {
        // Only suppress the track that is actually playing: the caller stops it right after,
        // so a matching onTrackEnd is guaranteed to consume the entry. Suppressing anything
        // else would leave a permanent entry (no onTrackEnd ever arrives) and leak the set.
        if(track != null && track == audioPlayer.getPlayingTrack())
            suppressedTrackEnds.add(track);
    }

    private synchronized boolean consumeSuppressedTrackEnd(AudioTrack track)
    {
        return track != null && suppressedTrackEnds.remove(track);
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        LOG.error("Track failed to play for guild {}: {}", guildId, trackSummary(track), exception);
        recordTrackIssue(track, "track_exception", exception.getMessage());
        failGuessMusicSnippet(track, exception.getMessage());
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs)
    {
        LOG.warn("Track stuck for guild {} after {}ms: {}", guildId, thresholdMs, trackSummary(track));
        recordTrackIssue(track, "track_stuck", "Stuck after " + thresholdMs + "ms");
        failGuessMusicSnippet(track, "Track stuck after " + thresholdMs + "ms");
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) 
    {
        RequestMetadata metadata = track.getUserData(RequestMetadata.class);
        if(metadata != null && metadata.isGuessGame())
        {
            votes.clear();
            pendingSkip = null;
            currentTrackStartedAt = System.currentTimeMillis();
            Runnable startCallback = null;
            synchronized(this)
            {
                if(guessMusicSnippetPlaying && !guessMusicSnippetStarted)
                {
                    guessMusicSnippetStarted = true;
                    startCallback = guessMusicSnippetStart;
                    guessMusicSnippetStart = null;
                }
            }
            if(startCallback != null)
                manager.getBot().getThreadpool().submit(startCallback);
            LOG.info("Guess music snippet playback started for guild {}; volume={}; track={}",
                    guildId, player.getVolume(), trackSummary(track));
            return;
        }

        votes.clear();
        pendingSkip = null;
        autoplayStopQueued = false;
        currentTrackStartedAt = System.currentTimeMillis();
        boolean firstPlayThisSession = !playbackSessionHistory.contains(track);
        recordTrackStart(player, track);
        recordPlaybackHistory(track);
        rememberRecentTrack(track);
        playbackSessionHistory.remember(track);
        creditSongRequest(metadata, firstPlayThisSession);
        if(metadata != null && metadata.origin == RequestMetadata.Origin.SAVED_PLAYLIST)
        {
            lastPlaylistName = metadata.playlistName;
            lastPlaylistId = metadata.playlistId;
        }
        else if(metadata == null || metadata.origin != RequestMetadata.Origin.AUTOPLAY)
        {
            lastPlaylistName = null;
            lastPlaylistId = 0L;
        }
        manager.getBot().getAutoplayService().recordIfEligible(guildId, track);
        LOG.info("Track started for guild {}; volume={}; queueSize={}; track={}",
                guildId, player.getVolume(), queue.size(), trackSummary(track));
        manager.getBot().getNowplayingHandler().onTrackUpdate(guildId, track);
    }

    /**
     * Credits the requester with a "song requested" toward the global economy.
     * Only genuine user requests count (manual plays and saved-playlist plays);
     * autoplay and guess-game snippets are excluded, and the per-session
     * de-duplication prevents farming via repeat mode or duplicate queueing.
     */
    private void creditSongRequest(RequestMetadata metadata, boolean firstPlayThisSession)
    {
        if(!firstPlayThisSession || metadata == null)
            return;
        if(metadata.origin != RequestMetadata.Origin.MANUAL
                && metadata.origin != RequestMetadata.Origin.SAVED_PLAYLIST)
            return;
        long owner = metadata.getOwner();
        if(owner <= 0)
            return;
        EconomyService economy = manager.getBot().getEconomyService();
        if(economy == null || !economy.isEnabled())
            return;
        manager.getBot().getThreadpool().submit(() ->
        {
            try
            {
                economy.onSongRequested(owner, announceChannelFor(metadata));
            }
            catch(RuntimeException ex)
            {
                LOG.warn("Failed to credit song request for user {}", owner, ex);
            }
        });
    }


    // Formatting
    public MessageCreateData getMusicPanel(JDA jda)
    {
        MessageCreateData nowPlaying = getNowPlaying(jda);
        return nowPlaying == null ? getNoMusicPlaying(jda) : nowPlaying;
    }

    public MessageCreateData getNowPlaying(JDA jda)
    {
        if(isMusicPlaying(jda))
        {
            Guild guild = guild(jda);
            AudioTrack track = audioPlayer.getPlayingTrack();
            MessageCreateBuilder mb = new MessageCreateBuilder();
            mb.setContent(FormatUtil.filter(manager.getBot().getConfig().getSuccess()+" **Now Playing in "+guild.getSelfMember().getVoiceState().getChannel().getAsMention()+"...**"));
            EmbedBuilder eb = new EmbedBuilder();
            eb.setColor(guild.getSelfMember().getColor());
            RequestMetadata rm = getRequestMetadata();
            if(rm.isAutoplay())
            {
                eb.setAuthor("Autoplay", null,
                        guild.getJDA().getSelfUser().getEffectiveAvatarUrl());
            }
            else if(rm.getOwner() != 0L)
            {
                User u = guild.getJDA().getUserById(rm.user.id);
                if(u==null)
                    eb.setAuthor(FormatUtil.formatUsername(rm.user), null, rm.user.avatar);
                else
                    eb.setAuthor(FormatUtil.formatUsername(u), null, u.getEffectiveAvatarUrl());
            }

            try 
            {
                eb.setTitle(track.getInfo().title, track.getInfo().uri);
            }
            catch(Exception e) 
            {
                eb.setTitle(track.getInfo().title);
            }

            if(manager.getBot().getConfig().useNPImages())
            {
                String thumbnailUrl = getNowPlayingThumbnail(track);
                if(thumbnailUrl != null)
                    eb.setThumbnail(thumbnailUrl);
            }
            
            if(track.getInfo().author != null && !track.getInfo().author.isEmpty())
                eb.setFooter("Source: " + track.getInfo().author, null);

            double progress = getProgress(track);
            eb.setDescription(getStatusEmoji()
                    + " "+FormatUtil.progressBar(progress)
                    + " `[" + TimeUtil.formatTime(track.getPosition()) + "/" + TimeUtil.formatTime(track.getDuration()) + "]`");
            RepeatMode repeatMode = manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode();
            AutoplayMode autoplayMode = manager.getBot().getSettingsManager().getSettings(guildId).getAutoplayMode();
            eb.addField("Queue", getQueueSummary(track), false);
            eb.addField("Next Up", getNextUpSummary(), false);
            eb.addField("Loop", formatRepeatMode(repeatMode), true);
            eb.addField("Autoplay", formatAutoplayMode(autoplayMode), true);
            eb.addField("Volume", FormatUtil.volumeIcon(audioPlayer.getVolume()) + " `" + audioPlayer.getVolume() + "%`", true);
            if(!votes.isEmpty())
                eb.addField("Skip Votes", "`" + votes.size() + "` vote" + (votes.size() == 1 ? "" : "s"), true);

            return mb.setEmbeds(eb.build())
                    .setComponents(nowPlayingButtons(true))
                    .build();
        }
        else return null;
    }

    public static String getNowPlayingThumbnail(AudioTrack track)
    {
        if(track == null)
            return null;

        if(track.getInfo().artworkUrl != null && !track.getInfo().artworkUrl.isEmpty())
            return track.getInfo().artworkUrl;

        if(isYouTubeTrack(track) && track.getIdentifier() != null && YOUTUBE_VIDEO_ID.matcher(track.getIdentifier()).matches())
            return "https://img.youtube.com/vi/" + track.getIdentifier() + "/mqdefault.jpg";

        return null;
    }

    private String getQueueSummary(AudioTrack currentTrack)
    {
        QueueTiming timing = getQueueTiming(currentTrack);
        StringBuilder builder = new StringBuilder("`")
                .append(timing.queuedTracks)
                .append("` waiting");
        if(timing.queuedTracks > 0)
            builder.append(" | `").append(timing.queuedDurationUnknown ? "LIVE" : TimeUtil.formatTime(timing.queuedDuration)).append("`");

        if(timing.estimatedEndUnknown)
            builder.append("\nTotal left unknown");
        else if(audioPlayer.isPaused())
            builder.append("\nTotal left `").append(TimeUtil.formatTime(timing.remainingDuration)).append("` (paused)");
        else
        {
            long endEpochSeconds = Instant.now().plusMillis(timing.remainingDuration).getEpochSecond();
            builder.append("\nTotal left `")
                    .append(TimeUtil.formatTime(timing.remainingDuration))
                    .append("` | Ends ")
                    .append(formatDiscordTimestamp(endEpochSeconds, "R"))
                    .append(" (")
                    .append(formatDiscordTimestamp(endEpochSeconds, "t"))
                    .append(")");
        }
        return builder.toString();
    }

    static String formatDiscordTimestamp(long epochSeconds, String style)
    {
        return "<t:" + epochSeconds + ":" + style + ">";
    }

    private static String formatRepeatMode(RepeatMode repeatMode)
    {
        String emoji = repeatMode.getEmoji();
        return (emoji == null ? "" : emoji + " ") + "`" + repeatMode.getUserFriendlyName() + "`";
    }

    private static String formatAutoplayMode(AutoplayMode autoplayMode)
    {
        return "`" + autoplayMode.getUserFriendlyName() + "`";
    }

    private static boolean isYouTubeTrack(AudioTrack track)
    {
        return track.getSourceManager() != null
                && "youtube".equalsIgnoreCase(track.getSourceManager().getSourceName());
    }
    
    public MessageCreateData getNoMusicPlaying(JDA jda)
    {
        Guild guild = guild(jda);
        return new MessageCreateBuilder()
                .setContent(FormatUtil.filter(manager.getBot().getConfig().getSuccess()+" **Now Playing...**"))
                .setEmbeds(new EmbedBuilder()
                .setTitle("No music playing")
                .setDescription(STOP_EMOJI+" "+FormatUtil.progressBar(-1))
                .setColor(guild.getSelfMember().getColor())
                .addField("Queue", "`0` waiting", false)
                .addField("Next Up", getNextUpSummary(), false)
                .addField("Loop", formatRepeatMode(manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode()), true)
                .addField("Autoplay", formatAutoplayMode(manager.getBot().getSettingsManager().getSettings(guildId).getAutoplayMode()), true)
                .addField("Volume", FormatUtil.volumeIcon(audioPlayer.getVolume()) + " `" + audioPlayer.getVolume() + "%`", true)
                .build())
                .setComponents(nowPlayingButtons(false))
                .build();
    }

    public String getStatusEmoji()
    {
        return audioPlayer.isPaused() ? PAUSE_EMOJI : PLAY_EMOJI;
    }

    public void updateMusicPanels()
    {
        manager.getBot().getNowplayingHandler().updatePanels(guildId);
    }

    private List<ActionRow> nowPlayingButtons(boolean enabled)
    {
        String playPauseEmoji = audioPlayer.isPaused() ? PLAY_EMOJI : PAUSE_EMOJI;
        return List.of(
                ActionRow.of(
                        panelButton(NowplayingHandler.BUTTON_SHUFFLE, SHUFFLE_EMOJI, enabled),
                        panelButton(NowplayingHandler.BUTTON_RESTART, RESTART_EMOJI, enabled),
                        panelButton(NowplayingHandler.BUTTON_PLAY_PAUSE, playPauseEmoji, enabled),
                        panelButton(NowplayingHandler.BUTTON_SKIP, SKIP_EMOJI, enabled),
                        panelButton(NowplayingHandler.BUTTON_LOOP, REPEAT_EMOJI, enabled)
                ),
                ActionRow.of(
                        labeledPanelButton(NowplayingHandler.BUTTON_QUEUE, QUEUE_EMOJI, "Queue", enabled),
                        labeledPanelButton(NowplayingHandler.BUTTON_LYRICS, LYRICS_EMOJI, "Lyrics", enabled),
                        labeledPanelButton(NowplayingHandler.BUTTON_LIKE, LIKE_EMOJI, "Like", enabled),
                        Button.danger(NowplayingHandler.BUTTON_STOP, "Stop")
                                .withEmoji(Emoji.fromUnicode(STOP_EMOJI))
                                .withDisabled(!enabled)
                ));
    }

    private static Button panelButton(String id, String emoji, boolean enabled)
    {
        return Button.secondary(id, Emoji.fromUnicode(emoji)).withDisabled(!enabled);
    }

    private static Button labeledPanelButton(String id, String emoji, String label, boolean enabled)
    {
        return Button.secondary(id, label)
                .withEmoji(Emoji.fromUnicode(emoji))
                .withDisabled(!enabled);
    }
    
    // Audio Send Handler methods
    /*@Override
    public boolean canProvide() 
    {
        if (lastFrame == null)
            lastFrame = audioPlayer.provide();

        return lastFrame != null;
    }

    @Override
    public byte[] provide20MsAudio() 
    {
        if (lastFrame == null) 
            lastFrame = audioPlayer.provide();

        byte[] data = lastFrame != null ? lastFrame.getData() : null;
        lastFrame = null;

        return data;
    }*/
    
    @Override
    public boolean canProvide() 
    {
        lastFrame = audioPlayer.provide();
        return lastFrame != null;
    }

    @Override
    public ByteBuffer provide20MsAudio() 
    {
        return ByteBuffer.wrap(lastFrame.getData());
    }

    @Override
    public boolean isOpus() 
    {
        return true;
    }
    
    
    // Private methods
    private Guild guild(JDA jda)
    {
        return jda.getGuildById(guildId);
    }

    private void handleEmptyQueue(AudioPlayer player, AudioTrack previousTrack)
    {
        if(manager.getBot().getAutoplayService().startNext(this, previousTrack, () -> finishEmptyQueue(player)))
        {
            player.setPaused(false);
            return;
        }
        finishEmptyQueue(player);
    }

    private void finishEmptyQueue(AudioPlayer player)
    {
        manager.getBot().getNowplayingHandler().onTrackUpdate(guildId, null);
        if(!manager.getBot().getConfig().getStay())
        {
            LOG.info("Queue empty and stayinchannel=false for guild {}; closing audio connection", guildId);
            manager.getBot().closeAudioConnection(guildId);
        }
        else
        {
            LOG.info("Queue empty for guild {}; staying connected because stayinchannel=true", guildId);
        }
        // Unpause in case the player was paused when the track was skipped.
        player.setPaused(false);
    }

    private boolean shouldInterruptAutoplay(QueuedTrack qtrack)
    {
        if(autoplayStopQueued)
            return false;
        RequestMetadata current = getRequestMetadata();
        RequestMetadata next = qtrack.getRequestMetadata();
        return current.isAutoplay() && !next.isAutoplay();
    }

    private QueueTiming getQueueTiming(AudioTrack currentTrack)
    {
        List<QueuedTrack> queuedTracks = snapshotQueue();
        int queuedCount = queuedTracks.size();
        long queuedDuration = 0L;
        boolean queuedDurationUnknown = false;
        boolean estimatedEndUnknown = isUnknownDuration(currentTrack);
        long remainingDuration = getRemainingDuration(currentTrack);

        for(QueuedTrack queuedTrack : queuedTracks)
        {
            AudioTrack track = queuedTrack.getTrack();
            if(isUnknownDuration(track))
            {
                queuedDurationUnknown = true;
                estimatedEndUnknown = true;
                continue;
            }

            long duration = Math.max(0L, track.getDuration());
            queuedDuration = safeAdd(queuedDuration, duration);
            remainingDuration = safeAdd(remainingDuration, duration);
            if(queuedDuration == Long.MAX_VALUE)
                queuedDurationUnknown = true;
            if(queuedDurationUnknown || remainingDuration == Long.MAX_VALUE)
                estimatedEndUnknown = true;
        }

        return new QueueTiming(queuedCount, queuedDuration, remainingDuration, queuedDurationUnknown, estimatedEndUnknown);
    }

    private List<QueuedTrack> snapshotQueue()
    {
        try
        {
            return new ArrayList<>(queue.getList());
        }
        catch(RuntimeException ex)
        {
            LOG.debug("Could not snapshot queue for guild {}; panel timing will use current track only", guildId, ex);
            return List.of();
        }
    }

    private void failGuessMusicSnippet(AudioTrack track, String reason)
    {
        RequestMetadata metadata = track == null ? null : track.getUserData(RequestMetadata.class);
        if(metadata == null || !metadata.isGuessGame())
            return;

        Consumer<String> callback;
        synchronized(this)
        {
            if(!guessMusicSnippetPlaying)
                return;
            guessMusicSnippetPlaying = false;
            guessMusicSnippetStarted = false;
            guessMusicSnippetStart = null;
            guessMusicSnippetEnd = null;
            callback = guessMusicSnippetFailure;
            guessMusicSnippetFailure = null;
            cancelGuessMusicFade(true);
        }
        if(callback != null)
            manager.getBot().getThreadpool().submit(() -> callback.accept(reason));
    }

    private String getNextUpSummary()
    {
        List<QueuedTrack> queuedTracks = snapshotQueue();
        if(!queuedTracks.isEmpty())
            return formatQueuedTrackLine(queuedTracks.get(0), NEXT_UP_TITLE_LIMIT);

        AutoplayMode autoplayMode = manager.getBot().getSettingsManager().getSettings(guildId).getAutoplayMode();
        if(autoplayMode != AutoplayMode.OFF)
        {
            String message = "Autoplay `" + autoplayMode.getUserFriendlyName() + "` will choose the next track.";
            if(getRequestMetadata().isAutoplay())
                message += "\nUser requests play immediately.";
            return message;
        }

        return "`Nothing queued`";
    }

    static String formatQueuedTrackLine(QueuedTrack queuedTrack)
    {
        return formatQueuedTrackLine(queuedTrack, Integer.MAX_VALUE);
    }

    static String formatQueuedTrackLine(QueuedTrack queuedTrack, int titleLimit)
    {
        if(queuedTrack == null || queuedTrack.getTrack() == null)
            return "`Unknown track`";

        AudioTrack track = queuedTrack.getTrack();
        String duration = isUnknownDuration(track) ? "LIVE" : TimeUtil.formatTime(Math.max(0L, track.getDuration()));
        String title = track.getInfo() == null || track.getInfo().title == null || track.getInfo().title.isBlank()
                ? "Unknown track" : track.getInfo().title;
        StringBuilder builder = new StringBuilder("`[")
                .append(duration)
                .append("]` **")
                .append(shortenTitle(FormatUtil.filter(title), titleLimit))
                .append("**");

        RequestMetadata metadata = queuedTrack.getRequestMetadata();
        if(metadata != null && metadata.user != null)
            builder.append(" - ").append(FormatUtil.formatUsername(metadata.user));
        return builder.toString();
    }

    private static String shortenTitle(String title, int limit)
    {
        if(title == null || title.length() <= limit)
            return title;
        if(limit <= 3)
            return title.substring(0, Math.max(0, limit));
        return title.substring(0, limit - 3).stripTrailing() + "...";
    }

    private static long getRemainingDuration(AudioTrack track)
    {
        if(track == null || isUnknownDuration(track))
            return 0L;
        return Math.max(0L, track.getDuration() - track.getPosition());
    }

    private static boolean isUnknownDuration(AudioTrack track)
    {
        return track != null
                && (track.getDuration() == Long.MAX_VALUE || (track.getInfo() != null && track.getInfo().isStream));
    }

    private static long safeAdd(long total, long duration)
    {
        if(duration <= 0L)
            return total;
        if(Long.MAX_VALUE - total < duration)
            return Long.MAX_VALUE;
        return total + duration;
    }

    private static double getProgress(AudioTrack track)
    {
        if(track == null || track.getDuration() <= 0L || isUnknownDuration(track))
            return -1;
        return (double)track.getPosition() / track.getDuration();
    }

    private static class QueueTiming
    {
        private final int queuedTracks;
        private final long queuedDuration;
        private final long remainingDuration;
        private final boolean queuedDurationUnknown;
        private final boolean estimatedEndUnknown;

        private QueueTiming(int queuedTracks, long queuedDuration, long remainingDuration,
                            boolean queuedDurationUnknown, boolean estimatedEndUnknown)
        {
            this.queuedTracks = queuedTracks;
            this.queuedDuration = queuedDuration;
            this.remainingDuration = remainingDuration;
            this.queuedDurationUnknown = queuedDurationUnknown;
            this.estimatedEndUnknown = estimatedEndUnknown;
        }
    }

    private void rememberRecentTrack(AudioTrack track)
    {
        for(String key : trackKeys(track))
        {
            recentTrackKeys.remove(key);
            recentTrackKeys.addFirst(key);
        }
        while(recentTrackKeys.size() > RECENT_TRACK_LIMIT)
            recentTrackKeys.removeLast();
    }

    private void clearPlaybackSessionHistory()
    {
        recentTrackKeys.clear();
        playbackSessionHistory.clear();
        sessionContributors.clear();
    }

    private static Set<String> trackKeys(AudioTrack track)
    {
        return TrackIdentity.keys(track);
    }

    private void recordTrackStart(AudioPlayer player, AudioTrack track)
    {
        DashboardStatsService stats = manager.getBot().getDashboardStats();
        JDA jda = manager.getBot().getJDA();
        if(stats == null || jda == null)
            return;

        Guild guild = guild(jda);
        if(guild == null)
            return;

        currentStatsSessionKey = stats.recordTrackStart(guild, player, track, queue == null ? 0 : queue.size());
    }

    private void recordPlaybackHistory(AudioTrack track)
    {
        PlaybackHistoryStore history = manager.getBot().getPlaybackHistoryStore();
        if(history == null)
            return;

        try
        {
            history.record(guildId, track);
        }
        catch(RuntimeException ex)
        {
            LOG.warn("Failed to record playback history for guild {}", guildId, ex);
        }
    }

    private void recordTrackEnd(AudioTrack track, AudioTrackEndReason endReason, SkipInfo skipInfo)
    {
        DashboardStatsService stats = manager.getBot().getDashboardStats();
        if(stats == null)
            return;

        Guild guild = manager.getBot().getJDA() == null ? null : guild(manager.getBot().getJDA());
        stats.recordTrackEnd(currentStatsSessionKey, guildId, guild == null ? null : guild.getName(), track, endReason, skipInfo);
    }

    private void recordTrackIssue(AudioTrack track, String eventType, String detail)
    {
        DashboardStatsService stats = manager.getBot().getDashboardStats();
        if(stats == null)
            return;

        Guild guild = manager.getBot().getJDA() == null ? null : guild(manager.getBot().getJDA());
        stats.recordTrackIssue(guildId, guild == null ? null : guild.getName(), track, eventType, detail);
    }

    private static String trackSummary(AudioTrack track)
    {
        if(track == null)
            return "none";

        String source = track.getSourceManager() == null ? "unknown" : track.getSourceManager().getSourceName();
        return "'" + track.getInfo().title + "' by '" + track.getInfo().author + "'"
                + " [id=" + track.getIdentifier()
                + ", source=" + source
                + ", duration=" + TimeUtil.formatTime(track.getDuration()) + "]";
    }
}
