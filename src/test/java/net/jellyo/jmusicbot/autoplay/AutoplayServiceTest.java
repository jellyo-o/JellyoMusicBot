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
package com.jagrosh.jmusicbot.autoplay;

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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutoplayServiceTest
{
    @Test
    public void autoplayRejectsLongFormCandidates()
    {
        assertTrue(AutoplayService.isAutoplayDurationAcceptable(new TestTrack("Song", "Artist", 5 * 60 * 1000L)));
        assertTrue(AutoplayService.isAutoplayDurationAcceptable(new TestTrack("Song", "Artist", 10 * 60 * 1000L)));
        assertFalse(AutoplayService.isAutoplayDurationAcceptable(new TestTrack("Compilation", "Artist", 40 * 60 * 1000L)));
    }

    @Test
    public void autoplayDurationUsesConfiguredLimit()
    {
        assertTrue(AutoplayService.isAutoplayDurationAcceptable(new TestTrack("Short Song", "Artist", 3 * 60 * 1000L), 4 * 60 * 1000L));
        assertFalse(AutoplayService.isAutoplayDurationAcceptable(new TestTrack("Long Song", "Artist", 5 * 60 * 1000L), 4 * 60 * 1000L));
    }

    @Test
    public void autoplayRejectsStreams()
    {
        assertFalse(AutoplayService.isAutoplayDurationAcceptable(new TestTrack("Stream", "Artist", Long.MAX_VALUE, true)));
    }

    @Test
    public void coverUploadsScoreBelowArtistOriginals()
    {
        AudioTrack original = new TestTrack("Against The Current - Gravity (Official Music Video)", "Against The Current", 4 * 60 * 1000L);
        AudioTrack cover = new TestTrack("Against The Current - Gravity Cover", "Fan Channel", 4 * 60 * 1000L);

        assertTrue(AutoplayService.originalityScore(original, "AgainstTheCurrentNY")
                > AutoplayService.originalityScore(cover, "AgainstTheCurrentNY"));
        assertTrue(AutoplayService.isLikelyCover(cover));
        assertFalse(AutoplayService.isLikelyCover(original));
    }

    @Test
    public void prefetchedCandidateStaysPlayableWhenNothingChanged()
    {
        // Re-validated at promotion time: autoplay still on, short enough, acceptable duration,
        // not already played this session, and not on the avoid list.
        assertTrue(AutoplayService.isPlayableCandidate(true, false, true, false, false));
    }

    @Test
    public void prefetchedCandidateIsDroppedWhenConditionsChange()
    {
        assertFalse("autoplay turned off",     AutoplayService.isPlayableCandidate(false, false, true, false, false));
        assertFalse("now too long",            AutoplayService.isPlayableCandidate(true, true, true, false, false));
        assertFalse("duration not acceptable", AutoplayService.isPlayableCandidate(true, false, false, false, false));
        assertFalse("already played",          AutoplayService.isPlayableCandidate(true, false, true, true, false));
        assertFalse("now avoided",             AutoplayService.isPlayableCandidate(true, false, true, false, true));
    }

    private static class TestTrack implements AudioTrack
    {
        private final AudioTrackInfo info;
        private final AudioSourceManager sourceManager = new TestSourceManager();

        private TestTrack(String title, String author, long duration)
        {
            this(title, author, duration, false);
        }

        private TestTrack(String title, String author, long duration, boolean stream)
        {
            this.info = new AudioTrackInfo(title, author, duration, title.toLowerCase().replaceAll("\\s+", "-"),
                    stream, "https://example.test/" + title.toLowerCase().replaceAll("\\s+", "-"), null, null);
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
        @Override
        public String getSourceName()
        {
            return "test";
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
