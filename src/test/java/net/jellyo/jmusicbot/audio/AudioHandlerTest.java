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
package com.jagrosh.jmusicbot.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.TrackMarker;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AudioHandlerTest
{
    @Test
    public void nowPlayingThumbnailUsesTrackArtwork()
    {
        String artworkUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg";

        assertEquals(artworkUrl, AudioHandler.getNowPlayingThumbnail(new TestTrack("dQw4w9WgXcQ", artworkUrl, "youtube")));
    }

    @Test
    public void nowPlayingThumbnailFallsBackToYouTubeIdentifier()
    {
        assertEquals("https://img.youtube.com/vi/dQw4w9WgXcQ/mqdefault.jpg",
                AudioHandler.getNowPlayingThumbnail(new TestTrack("dQw4w9WgXcQ", null, "youtube")));
    }

    @Test
    public void nowPlayingThumbnailIgnoresNonYouTubeIdentifiers()
    {
        assertNull(AudioHandler.getNowPlayingThumbnail(new TestTrack("dQw4w9WgXcQ", null, "soundcloud")));
    }

    @Test
    public void discordTimestampUsesRequestedStyle()
    {
        assertEquals("<t:1714600000:R>", AudioHandler.formatDiscordTimestamp(1714600000L, "R"));
        assertEquals("<t:1714600000:t>", AudioHandler.formatDiscordTimestamp(1714600000L, "t"));
    }

    @Test
    public void queuedTrackLineIncludesDurationAndTitle()
    {
        QueuedTrack queuedTrack = new QueuedTrack(new TestTrack("dQw4w9WgXcQ", null, "youtube"), RequestMetadata.EMPTY);

        assertEquals("`[00:01]` **Title**", AudioHandler.formatQueuedTrackLine(queuedTrack));
    }

    private static class TestTrack implements AudioTrack
    {
        private final AudioTrackInfo info;
        private final AudioSourceManager sourceManager;

        private TestTrack(String identifier, String artworkUrl, String sourceName)
        {
            this.info = new AudioTrackInfo("Title", "Author", 1000L, identifier, false,
                    "https://www.youtube.com/watch?v=" + identifier, artworkUrl, null);
            this.sourceManager = new TestSourceManager(sourceName);
        }

        @Override
        public AudioTrackInfo getInfo()
        {
            return info;
        }

        @Override
        public String getIdentifier()
        {
            return info.identifier;
        }

        @Override
        public AudioTrackState getState()
        {
            return AudioTrackState.INACTIVE;
        }

        @Override
        public void stop()
        {
        }

        @Override
        public boolean isSeekable()
        {
            return true;
        }

        @Override
        public long getPosition()
        {
            return 0;
        }

        @Override
        public void setPosition(long position)
        {
        }

        @Override
        public void setMarker(TrackMarker marker)
        {
        }

        @Override
        public void addMarker(TrackMarker marker)
        {
        }

        @Override
        public void removeMarker(TrackMarker marker)
        {
        }

        @Override
        public long getDuration()
        {
            return info.length;
        }

        @Override
        public AudioTrack makeClone()
        {
            return this;
        }

        @Override
        public AudioSourceManager getSourceManager()
        {
            return sourceManager;
        }

        @Override
        public void setUserData(Object userData)
        {
        }

        @Override
        public Object getUserData()
        {
            return null;
        }

        @Override
        public <T> T getUserData(Class<T> klass)
        {
            return null;
        }
    }

    private static class TestSourceManager implements AudioSourceManager
    {
        private final String sourceName;

        private TestSourceManager(String sourceName)
        {
            this.sourceName = sourceName;
        }

        @Override
        public String getSourceName()
        {
            return sourceName;
        }

        @Override
        public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference)
        {
            return null;
        }

        @Override
        public boolean isTrackEncodable(AudioTrack track)
        {
            return false;
        }

        @Override
        public void encodeTrack(AudioTrack track, DataOutput output) throws IOException
        {
        }

        @Override
        public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException
        {
            return null;
        }

        @Override
        public void shutdown()
        {
        }
    }
}
