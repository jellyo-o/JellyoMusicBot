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
package com.jagrosh.jmusicbot.playlist;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.TrackMarker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaylistTrackLoaderTest
{
    @Test
    public void rateLimitedItemIsRetried()
            throws Exception
    {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try
        {
            PlaylistTrack item = new PlaylistTrack(0, "https://open.spotify.com/track/1", null,
                    "Title", "Artist", 1000L, "spotify");
            AtomicInteger attempts = new AtomicInteger();
            AtomicReference<PlaylistTrackLoader.Result> result = new AtomicReference<>();
            CountDownLatch complete = new CountDownLatch(1);

            PlaylistTrackLoader.load((query, handler) ->
            {
                if(attempts.incrementAndGet() == 1)
                {
                    handler.loadFailed(new FriendlyException("Server responded with an error.",
                            FriendlyException.Severity.SUSPICIOUS,
                            new IllegalStateException("Response code from channel info is 429")));
                    return;
                }
                handler.trackLoaded(new TestTrack("loaded"));
            }, scheduler, "ATC", List.of(item), track -> false, loaded ->
            {
                result.set(loaded);
                complete.countDown();
            }, 1, 0L, 2, 1L);

            assertTrue(complete.await(2, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
            assertEquals(1, result.get().getLoadedTrackCount());
            assertEquals(0, result.get().getFailed());
            assertEquals(1, result.get().getRetries());
        }
        finally
        {
            scheduler.shutdownNow();
        }
    }

    @Test
    public void loaderLimitsConcurrentStarts()
            throws Exception
    {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try
        {
            List<PlaylistTrack> items = List.of(
                    playlistItem("one"),
                    playlistItem("two"),
                    playlistItem("three"),
                    playlistItem("four"));
            List<AudioLoadResultHandler> handlers = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            CountDownLatch firstTwoStarted = new CountDownLatch(2);
            CountDownLatch complete = new CountDownLatch(1);

            PlaylistTrackLoader.load((query, handler) ->
            {
                int nowActive = active.incrementAndGet();
                maxActive.updateAndGet(current -> Math.max(current, nowActive));
                handlers.add(handler);
                firstTwoStarted.countDown();
            }, scheduler, "ATC", items, track -> false, loaded -> complete.countDown(),
                    2, 0L, 0, 1L);

            assertTrue(firstTwoStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(50L);
            assertEquals(2, handlers.size());

            int completed = 0;
            while(completed < items.size())
            {
                AudioLoadResultHandler handler = waitForHandler(handlers, completed);
                active.decrementAndGet();
                handler.trackLoaded(new TestTrack("track-" + completed));
                completed++;
            }

            assertTrue(complete.await(2, TimeUnit.SECONDS));
            assertEquals(items.size(), handlers.size());
            assertTrue(maxActive.get() <= 2);
        }
        finally
        {
            scheduler.shutdownNow();
        }
    }

    @Test
    public void rateLimitDetectionSearchesCauseChain()
    {
        FriendlyException exception = new FriendlyException("Server responded with an error.",
                FriendlyException.Severity.SUSPICIOUS,
                new IllegalStateException("Response code from channel info is 429"));

        assertTrue(PlaylistTrackLoader.isRateLimit(exception));
    }

    private static PlaylistTrack playlistItem(String query)
    {
        return new PlaylistTrack(0, query, null, query, "Artist", 1000L, "test");
    }

    private static AudioLoadResultHandler waitForHandler(List<AudioLoadResultHandler> handlers, int index)
            throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 2000L;
        while(System.currentTimeMillis() < deadline)
        {
            synchronized(handlers)
            {
                if(handlers.size() > index)
                    return handlers.get(index);
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for handler " + index);
    }

    private static class TestTrack implements AudioTrack
    {
        private final AudioTrackInfo info;

        private TestTrack(String identifier)
        {
            this.info = new AudioTrackInfo("Title " + identifier, "Artist", 1000L, identifier,
                    false, "https://example.test/" + identifier, null, null);
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
            return null;
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
}
