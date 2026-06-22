/*
 * Copyright 2021 John Grosh <john.a.grosh@gmail.com>.
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

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.User;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class RequestMetadata
{
    public static final RequestMetadata EMPTY = new RequestMetadata((User) null, null, Origin.UNKNOWN, null, 0L, 0L);

    public enum Origin
    {
        UNKNOWN,
        MANUAL,
        SAVED_PLAYLIST,
        AUTOPLAY,
        GUESS_GAME
    }
    
    public final UserInfo user;
    public final RequestInfo requestInfo;
    public final Origin origin;
    public final String playlistName;
    public final long playlistId;
    public final long textChannelId;
    
    public RequestMetadata(User user, RequestInfo requestInfo)
    {
        this(user, requestInfo, 0L);
    }

    public RequestMetadata(User user, RequestInfo requestInfo, long textChannelId)
    {
        this(user, requestInfo, Origin.MANUAL, null, 0L, textChannelId);
    }

    private RequestMetadata(User user, RequestInfo requestInfo, Origin origin, String playlistName, long playlistId, long textChannelId)
    {
        this.user = user == null ? null : new UserInfo(user.getIdLong(), user.getName(), user.getDiscriminator(), user.getEffectiveAvatarUrl());
        this.requestInfo = requestInfo;
        this.origin = origin == null ? Origin.UNKNOWN : origin;
        this.playlistName = playlistName;
        this.playlistId = playlistId;
        this.textChannelId = textChannelId;
    }

    private RequestMetadata(UserInfo user, RequestInfo requestInfo, Origin origin, String playlistName, long playlistId, long textChannelId)
    {
        this.user = user;
        this.requestInfo = requestInfo;
        this.origin = origin == null ? Origin.UNKNOWN : origin;
        this.playlistName = playlistName;
        this.playlistId = playlistId;
        this.textChannelId = textChannelId;
    }

    /**
     * Rebuilds request metadata for a track being restored from a saved queue
     * snapshot. The original requester is reconstructed from stored fields (the
     * JDA {@link User} may no longer be cached), and {@code startMillis} resumes
     * the track at the position it was saved at.
     */
    public static RequestMetadata restored(long userId, String username, String avatar, String query,
                                           String url, long startMillis)
    {
        UserInfo user = userId > 0 ? new UserInfo(userId, username, null, avatar) : null;
        RequestInfo info = new RequestInfo(query == null ? "" : query, url == null ? "" : url, Math.max(0, startMillis));
        // UNKNOWN origin: restored tracks keep their requester for display/fair-queue but are
        // not re-counted as new "song requests" (the requester was already credited originally).
        return new RequestMetadata(user, info, Origin.UNKNOWN, null, 0L, 0L);
    }
    
    public long getOwner()
    {
        return user == null ? 0L : user.id;
    }

    public long getTextChannelId()
    {
        return textChannelId;
    }

    public static RequestMetadata fromResultHandler(AudioTrack track, CommandEvent event)
    {
        return new RequestMetadata(event.getAuthor(), new RequestInfo(event.getArgs(), track.getInfo().uri), event.getTextChannel().getIdLong());
    }

    public static RequestMetadata fromSlash(net.dv8tion.jda.api.entities.User user, String args, AudioTrack track)
    {
        return new RequestMetadata(user, new RequestInfo(args, track.getInfo().uri));
    }

    public static RequestMetadata fromSlash(net.dv8tion.jda.api.entities.User user, String args, AudioTrack track, long textChannelId)
    {
        return new RequestMetadata(user, new RequestInfo(args, track.getInfo().uri), textChannelId);
    }

    public static RequestMetadata fromPlaylist(User user, String playlistName, AudioTrack track)
    {
        return fromPlaylist(user, 0L, playlistName, track, 0L);
    }

    public static RequestMetadata fromPlaylist(User user, String playlistName, AudioTrack track, long textChannelId)
    {
        return fromPlaylist(user, 0L, playlistName, track, textChannelId);
    }

    public static RequestMetadata fromPlaylist(User user, long playlistId, String playlistName, AudioTrack track)
    {
        return fromPlaylist(user, playlistId, playlistName, track, 0L);
    }

    public static RequestMetadata fromPlaylist(User user, long playlistId, String playlistName, AudioTrack track, long textChannelId)
    {
        return new RequestMetadata(user, new RequestInfo("playlist:" + playlistName, track.getInfo().uri),
                Origin.SAVED_PLAYLIST, playlistName, playlistId, textChannelId);
    }

    public static RequestMetadata autoplay(String query, AudioTrack track)
    {
        return new RequestMetadata((User) null, new RequestInfo(query, track.getInfo().uri), Origin.AUTOPLAY, null, 0L, 0L);
    }

    public static RequestMetadata guessGame(AudioTrack track)
    {
        return new RequestMetadata((User) null, new RequestInfo("guessmusic", track.getInfo().uri), Origin.GUESS_GAME, null, 0L, 0L);
    }

    public boolean isAutoplay()
    {
        return origin == Origin.AUTOPLAY;
    }

    public boolean isGuessGame()
    {
        return origin == Origin.GUESS_GAME;
    }

    public boolean shouldRecordInHistory()
    {
        return user != null && origin != Origin.AUTOPLAY;
    }
    
    public static class RequestInfo
    {
        public final String query, url;
        public final long startTimestamp;

        public RequestInfo(String query, String url)
        {
            this(query, url, tryGetTimestamp(query));
        }

        private RequestInfo(String query, String url, long startTimestamp)
        {
            this.url = url;
            this.query = query;
            this.startTimestamp = startTimestamp;
        }

        private static final Pattern youtubeTimestampPattern = Pattern.compile("youtu(?:\\.be|be\\..+)/.*\\?.*(?!.*list=)t=([\\dhms]+)");
        private static long tryGetTimestamp(String url)
        {
            Matcher matcher = youtubeTimestampPattern.matcher(url);
            return matcher.find() ? TimeUtil.parseUnitTime(matcher.group(1)) : 0;
        }
    }
    
    public static class UserInfo
    {
        public final long id;
        public final String username, discrim, avatar;
        
        private UserInfo(long id, String username, String discrim, String avatar)
        {
            this.id = id;
            this.username = username;
            this.discrim = discrim;
            this.avatar = avatar;
        }
    }
}
