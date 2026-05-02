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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlaybackHistoryStoreTest
{
    @Test
    public void recordCollapsesOnlyConsecutiveSongsAndPersists() throws Exception
    {
        Path db = Files.createTempFile("playback-history-test", ".db");
        PlaybackHistoryStore store = new PlaybackHistoryStore(db);
        store.init();

        store.record(1L, track("a", "Song", "Artist"));
        store.record(1L, track("b", "Song (Official Music Video)", "ArtistVEVO"));
        store.record(1L, track("c", "Other", "Artist"));
        store.record(1L, track("a", "Song", "Artist"));

        List<PlaybackHistoryStore.Entry> entries = store.list(1L, 0, 10);
        assertEquals(3, entries.size());
        assertEquals("Song", entries.get(0).getTitle());
        assertEquals(1, entries.get(0).getCount());
        assertEquals("Other", entries.get(1).getTitle());
        assertEquals(1, entries.get(1).getCount());
        assertEquals(2, entries.get(2).getCount());
        store.close();

        store = new PlaybackHistoryStore(db);
        store.init();
        assertEquals(3, store.countEntries(1L));
        assertEquals(3, store.list(1L, 0, 10).size());
        store.close();
    }

    @Test
    public void historyIsPerGuild()
    {
        Path db;
        try
        {
            db = Files.createTempFile("playback-history-guild-test", ".db");
        }
        catch(IOException ex)
        {
            throw new AssertionError(ex);
        }
        PlaybackHistoryStore store = new PlaybackHistoryStore(db);
        try
        {
            store.init();
        }
        catch(Exception ex)
        {
            throw new AssertionError(ex);
        }

        store.record(1L, track("a", "Song", "Artist"));
        store.record(2L, track("b", "Other", "Artist"));

        assertEquals("Song", store.list(1L, 0, 10).get(0).getTitle());
        assertEquals("Other", store.list(2L, 0, 10).get(0).getTitle());
        store.close();
    }

    private static AudioTrack track(String identifier, String title, String author)
    {
        return new TestTrack(identifier, title, author);
    }

    private static class TestTrack implements AudioTrack
    {
        private final AudioTrackInfo info;
        private final AudioSourceManager sourceManager = new TestSourceManager();

        private TestTrack(String identifier, String title, String author)
        {
            this.info = new AudioTrackInfo(title, author, 1000L, identifier, false,
                    "https://example.test/" + identifier, null, null);
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
            return "youtube";
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
