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

import com.jagrosh.jmusicbot.utils.TrackIdentity;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayHistoryStoreTest
{
    @Test
    public void recordsAndReturnsWeightedTrack() throws Exception
    {
        PlayHistoryStore store = newStore();
        store.record(123L, new TestTrack("id-1", "Title 1", "Artist 1"));
        store.record(123L, new TestTrack("id-1", "Title 1", "Artist 1"));

        Optional<TrackReference> selected = store.chooseWeighted(123L, Collections.emptySet(), new Random(1));

        assertTrue(selected.isPresent());
        assertEquals("id-1", selected.get().getIdentifier());
        assertEquals(2, selected.get().getPlays());
    }

    @Test
    public void excludesRecentTrackIdentifiers() throws Exception
    {
        PlayHistoryStore store = newStore();
        store.record(123L, new TestTrack("id-1", "Title 1", "Artist 1"));

        Optional<TrackReference> selected = store.chooseWeighted(123L, Collections.singleton("id-1"), new Random(1));

        assertFalse(selected.isPresent());
    }

    @Test
    public void excludesRecentSongVersions() throws Exception
    {
        PlayHistoryStore store = newStore();
        store.record(123L, new TestTrack("lyric-id", "Artist - Song (Official Lyrics Video)", "Artist - Topic"));

        Optional<TrackReference> selected = store.chooseWeighted(123L,
                TrackIdentity.keys("video-id", "https://example.test/watch/video-id",
                        "Artist - Song (Official Music Video)", "ArtistVEVO"),
                new Random(1));

        assertFalse(selected.isPresent());
    }

    @Test
    public void prunesGuildHistoryToBoundedSize() throws Exception
    {
        PlayHistoryStore store = newStore();
        for(int i = 0; i < 260; i++)
            store.record(123L, new TestTrack("id-" + i, "Title " + i, "Artist"));

        assertEquals(250, store.size(123L));
    }

    @Test
    public void persistedHistoryDoesNotContainUserIds() throws Exception
    {
        Path file = tempHistoryFile();
        PlayHistoryStore store = new PlayHistoryStore(file);
        store.record(123L, new TestTrack("id-1", "Title 1", "Artist 1"));

        String json = new String(Files.readAllBytes(file));

        assertFalse(json.contains("user"));
    }

    private static PlayHistoryStore newStore() throws IOException
    {
        return new PlayHistoryStore(tempHistoryFile());
    }

    private static Path tempHistoryFile() throws IOException
    {
        Path dir = Files.createTempDirectory("play-history-test");
        return dir.resolve("playhistory.json");
    }

    private static class TestTrack implements AudioTrack
    {
        private final AudioTrackInfo info;
        private final AudioSourceManager sourceManager = new TestSourceManager();

        private TestTrack(String identifier, String title, String author)
        {
            this.info = new AudioTrackInfo(title, author, 1000L, identifier, false,
                    "https://example.test/watch/" + identifier, null, null);
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
