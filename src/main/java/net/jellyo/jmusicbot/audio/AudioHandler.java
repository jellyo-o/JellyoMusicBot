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

import com.jagrosh.jmusicbot.playlist.PlaylistLoader.Playlist;
import com.jagrosh.jmusicbot.queue.AbstractQueue;
import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.utils.TimeUtil;
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
import com.jagrosh.jmusicbot.settings.Settings;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import java.nio.ByteBuffer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
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
    private final static Pattern YOUTUBE_VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");


    private final List<AudioTrack> defaultQueue = new LinkedList<>();
    private final Set<String> votes = new HashSet<>();
    
    private final PlayerManager manager;
    private final AudioPlayer audioPlayer;
    private final long guildId;
    
    private AudioFrame lastFrame;
    private AbstractQueue<QueuedTrack> queue;

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
        else
        {
            queue.addAt(0, qtrack);
            LOG.debug("Queued track at front for guild {}: {}; queueSize={}", guildId, trackSummary(qtrack.getTrack()), queue.size());
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
        else
        {
            int position = queue.add(qtrack);
            LOG.debug("Queued track for guild {}: {}; position={}; queueSize={}", guildId, trackSummary(qtrack.getTrack()), position + 1, queue.size());
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
        LOG.info("Stopping player and clearing queue for guild {}; playing={}; queueSize={}; defaultQueueSize={}",
                guildId, trackSummary(playing), queueSize, defaultQueue.size());
        queue.clear();
        defaultQueue.clear();
        audioPlayer.stopTrack();
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
    
    public RequestMetadata getRequestMetadata()
    {
        if(audioPlayer.getPlayingTrack() == null)
            return RequestMetadata.EMPTY;
        RequestMetadata rm = audioPlayer.getPlayingTrack().getUserData(RequestMetadata.class);
        return rm == null ? RequestMetadata.EMPTY : rm;
    }
    
    public boolean playFromDefault()
    {
        if(!defaultQueue.isEmpty())
        {
            LOG.info("Starting queued default-playlist track for guild {}; remainingDefaultQueue={}",
                    guildId, defaultQueue.size() - 1);
            audioPlayer.playTrack(defaultQueue.remove(0));
            return true;
        }
        Settings settings = manager.getBot().getSettingsManager().getSettings(guildId);
        if(settings==null || settings.getDefaultPlaylist()==null)
        {
            LOG.debug("No default playlist configured for guild {}", guildId);
            return false;
        }
        
        Playlist pl = manager.getBot().getPlaylistLoader().getPlaylist(settings.getDefaultPlaylist());
        if(pl==null || pl.getItems().isEmpty())
        {
            LOG.warn("Default playlist '{}' for guild {} could not be loaded or is empty",
                    settings.getDefaultPlaylist(), guildId);
            return false;
        }
        LOG.info("Loading default playlist '{}' for guild {} with {} configured entries",
                pl.getName(), guildId, pl.getItems().size());
        pl.loadTracks(manager, (at) -> 
        {
            if(audioPlayer.getPlayingTrack()==null)
            {
                LOG.info("Starting default playlist track for guild {}: {}", guildId, trackSummary(at));
                audioPlayer.playTrack(at);
            }
            else
            {
                defaultQueue.add(at);
                LOG.debug("Added track to default queue for guild {}: {}; defaultQueueSize={}",
                        guildId, trackSummary(at), defaultQueue.size());
            }
        }, () -> 
        {
            if(pl.getTracks().isEmpty() && !manager.getBot().getConfig().getStay())
            {
                LOG.info("Default playlist '{}' loaded no tracks for guild {}; closing audio connection",
                        pl.getName(), guildId);
                manager.getBot().closeAudioConnection(guildId);
            }
            else
            {
                LOG.info("Default playlist '{}' loaded {} tracks for guild {}; errors={}",
                        pl.getName(), pl.getTracks().size(), guildId, pl.getErrors().size());
            }
        });
        return true;
    }
    
    // Audio Events
    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) 
    {
        RepeatMode repeatMode = manager.getBot().getSettingsManager().getSettings(guildId).getRepeatMode();
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
            if(!playFromDefault())
            {
                manager.getBot().getNowplayingHandler().onTrackUpdate(null);
                if(!manager.getBot().getConfig().getStay())
                {
                    LOG.info("Queue empty and stayinchannel=false for guild {}; closing audio connection", guildId);
                    manager.getBot().closeAudioConnection(guildId);
                }
                else
                {
                    LOG.info("Queue empty for guild {}; staying connected because stayinchannel=true", guildId);
                }
                // unpause, in the case when the player was paused and the track has been skipped.
                // this is to prevent the player being paused next time it's being used.
                player.setPaused(false);
            }
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
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs)
    {
        LOG.warn("Track stuck for guild {} after {}ms: {}", guildId, thresholdMs, trackSummary(track));
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) 
    {
        votes.clear();
        LOG.info("Track started for guild {}; volume={}; queueSize={}; track={}",
                guildId, player.getVolume(), queue.size(), trackSummary(track));
        manager.getBot().getNowplayingHandler().onTrackUpdate(track);
    }

    
    // Formatting
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
            if(rm.getOwner() != 0L)
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
            
            return mb.setEmbeds(eb.build()).build();
        }
        else return null;
    }

    static String getNowPlayingThumbnail(AudioTrack track)
    {
        if(track == null)
            return null;

        if(track.getInfo().artworkUrl != null && !track.getInfo().artworkUrl.isEmpty())
            return track.getInfo().artworkUrl;

        if(isYouTubeTrack(track) && track.getIdentifier() != null && YOUTUBE_VIDEO_ID.matcher(track.getIdentifier()).matches())
            return "https://img.youtube.com/vi/" + track.getIdentifier() + "/mqdefault.jpg";

        return null;
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
                .build()).build();
    }

    public String getStatusEmoji()
    {
        return audioPlayer.isPaused() ? PAUSE_EMOJI : PLAY_EMOJI;
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
