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
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
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
    public final static String PLAY_PAUSE_EMOJI = "\u23EF"; // ⏯
    public final static String SKIP_EMOJI = "\u23ED"; // ⏭
    public final static String REPEAT_EMOJI = "\uD83D\uDD01"; // 🔁
    public final static String LYRICS_EMOJI = "\uD83C\uDFA4"; // 🎤
    private final static Pattern YOUTUBE_VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private final static int RECENT_TRACK_LIMIT = 25;


    private final Set<String> votes = new HashSet<>();
    
    private final PlayerManager manager;
    private final AudioPlayer audioPlayer;
    private final long guildId;
    
    private AudioFrame lastFrame;
    private AbstractQueue<QueuedTrack> queue;
    private AudioFilterPreset filterPreset = AudioFilterPreset.OFF;
    private final LinkedList<String> recentTrackKeys = new LinkedList<>();
    private String lastPlaylistName;
    private long lastPlaylistId;
    private boolean autoplayStopQueued = false;
    private boolean suppressAutoplayOnce = false;
    private String currentStatsSessionKey;
    private long currentTrackStartedAt;
    private SkipInfo pendingSkip;

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
    
    public AbstractQueue<QueuedTrack> getQueue()
    {
        return queue;
    }
    
    public void stopAndClear()
    {
        AudioTrack playing = audioPlayer.getPlayingTrack();
        int queueSize = queue == null ? 0 : queue.size();
        LOG.info("Stopping player and clearing queue for guild {}; playing={}; queueSize={}",
                guildId, trackSummary(playing), queueSize);
        queue.clear();
        autoplayStopQueued = false;
        suppressAutoplayOnce = playing != null;
        audioPlayer.stopTrack();
        updateMusicPanels();
        //current = null;
    }
    
    public boolean isMusicPlaying(JDA jda)
    {
        return guild(jda).getSelfMember().getVoiceState().inAudioChannel() && audioPlayer.getPlayingTrack()!=null;
    }
    
    public Set<String> getVotes()
    {
        return votes;
    }
    
    public AudioPlayer getPlayer()
    {
        return audioPlayer;
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

    public boolean isRecentlyPlayed(AudioTrack track)
    {
        Set<String> keys = trackKeys(track);
        for(String key : keys)
            if(recentTrackKeys.contains(key))
                return true;
        return false;
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
    
    // Audio Events
    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) 
    {
        RepeatMode repeatMode = manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode();
        SkipInfo skipInfo = endReason == AudioTrackEndReason.STOPPED ? pendingSkip : null;
        recordTrackEnd(track, endReason, skipInfo);
        currentStatsSessionKey = null;
        currentTrackStartedAt = 0L;
        pendingSkip = null;
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

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        LOG.error("Track failed to play for guild {}: {}", guildId, trackSummary(track), exception);
        recordTrackIssue(track, "track_exception", exception.getMessage());
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs)
    {
        LOG.warn("Track stuck for guild {} after {}ms: {}", guildId, thresholdMs, trackSummary(track));
        recordTrackIssue(track, "track_stuck", "Stuck after " + thresholdMs + "ms");
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) 
    {
        votes.clear();
        pendingSkip = null;
        autoplayStopQueued = false;
        currentTrackStartedAt = System.currentTimeMillis();
        recordTrackStart(player, track);
        rememberRecentTrack(track);
        RequestMetadata metadata = track.getUserData(RequestMetadata.class);
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

            double progress = (double)audioPlayer.getPlayingTrack().getPosition()/track.getDuration();
            eb.setDescription(getStatusEmoji()
                    + " "+FormatUtil.progressBar(progress)
                    + " `[" + TimeUtil.formatTime(track.getPosition()) + "/" + TimeUtil.formatTime(track.getDuration()) + "]` "
                    + FormatUtil.volumeIcon(audioPlayer.getVolume()));
            RepeatMode repeatMode = manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode();
            AutoplayMode autoplayMode = manager.getBot().getSettingsManager().getSettings(guildId).getAutoplayMode();
            eb.addField("Position", getQueuePositionText(queue.size()), true);
            eb.addField("Queue", getQueueSummary(), true);
            eb.addField("Repeat", formatRepeatMode(repeatMode), true);
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

    static String getQueuePositionText(int queuedTracks)
    {
        int totalTracks = Math.max(1, queuedTracks + 1);
        if(totalTracks == 1)
            return "Now `#1`";
        return "Now `#1` of `" + totalTracks + "`";
    }

    private String getQueueSummary()
    {
        int queuedTracks = queue.size();
        if(queuedTracks == 0)
            return "`0` waiting";

        long total = 0L;
        for(QueuedTrack queuedTrack : queue.getList())
            total += Math.max(0L, queuedTrack.getTrack().getDuration());
        return "`" + queuedTracks + "` waiting | `" + TimeUtil.formatTime(total) + "`";
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
                .setDescription(STOP_EMOJI+" "+FormatUtil.progressBar(-1)+" "+FormatUtil.volumeIcon(audioPlayer.getVolume()))
                .setColor(guild.getSelfMember().getColor())
                .addField("Queue", "`0` waiting", true)
                .addField("Repeat", formatRepeatMode(manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode()), true)
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
        String playPauseLabel = audioPlayer.isPaused() ? "Resume" : "Pause";
        return List.of(ActionRow.of(
                Button.primary(NowplayingHandler.BUTTON_PLAY_PAUSE, playPauseLabel)
                        .withEmoji(Emoji.fromUnicode(PLAY_PAUSE_EMOJI))
                        .withDisabled(!enabled),
                Button.secondary(NowplayingHandler.BUTTON_SKIP, "Skip")
                        .withEmoji(Emoji.fromUnicode(SKIP_EMOJI))
                        .withDisabled(!enabled),
                Button.secondary(NowplayingHandler.BUTTON_LOOP, "Loop")
                        .withEmoji(Emoji.fromUnicode(REPEAT_EMOJI))
                        .withDisabled(!enabled),
                Button.secondary(NowplayingHandler.BUTTON_LYRICS, "Lyrics")
                        .withEmoji(Emoji.fromUnicode(LYRICS_EMOJI))
                        .withDisabled(!enabled),
                Button.danger(NowplayingHandler.BUTTON_STOP, "Stop")
                        .withEmoji(Emoji.fromUnicode(STOP_EMOJI))
                        .withDisabled(!enabled)
        ));
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
