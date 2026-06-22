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
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads saved playlist entries without flooding remote source APIs.
 */
public class PlaylistTrackLoader
{
    private static final Logger LOG = LoggerFactory.getLogger(PlaylistTrackLoader.class);
    private static final int DEFAULT_MAX_CONCURRENT = intProperty("playlist.load.maxConcurrent", 3, 1, 16);
    private static final int DEFAULT_MAX_RETRIES = intProperty("playlist.load.maxRetries", 4, 0, 10);
    private static final long DEFAULT_DISPATCH_INTERVAL_MS = longProperty("playlist.load.dispatchMillis", 250L, 0L, 10_000L);
    private static final long DEFAULT_RATE_LIMIT_DELAY_MS = longProperty("playlist.load.rateLimitDelayMillis", 15_000L, 1_000L, 300_000L);
    private static final Object GLOBAL_DISPATCH_LOCK = new Object();
    private static long globalNextDispatchAtNanos;

    private PlaylistTrackLoader()
    {
    }

    public static void load(AudioPlayerManager manager, ScheduledExecutorService scheduler,
                            String playlistName, List<PlaylistTrack> items, Predicate<AudioTrack> tooLong,
                            Consumer<Result> callback)
    {
        new LoadRun(manager, scheduler, playlistName, items, tooLong, callback,
                DEFAULT_MAX_CONCURRENT, DEFAULT_DISPATCH_INTERVAL_MS, DEFAULT_MAX_RETRIES,
                DEFAULT_RATE_LIMIT_DELAY_MS).start();
    }

    static void load(AudioItemLoader loader, ScheduledExecutorService scheduler,
                     String playlistName, List<PlaylistTrack> items, Predicate<AudioTrack> tooLong,
                     Consumer<Result> callback, int maxConcurrent, long dispatchIntervalMillis,
                     int maxRetries, long rateLimitDelayMillis)
    {
        new LoadRun(loader, scheduler, playlistName, items, tooLong, callback,
                maxConcurrent, dispatchIntervalMillis, maxRetries, rateLimitDelayMillis).start();
    }

    static boolean isRateLimit(Throwable throwable)
    {
        Throwable current = throwable;
        while(current != null)
        {
            String message = current.getMessage();
            if(message != null)
            {
                String lower = message.toLowerCase();
                if(lower.contains("429") || lower.contains("rate limit"))
                    return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static int intProperty(String name, int defaultValue, int min, int max)
    {
        int value = Integer.getInteger(name, defaultValue);
        return Math.max(min, Math.min(max, value));
    }

    private static long longProperty(String name, long defaultValue, long min, long max)
    {
        long value = Long.getLong(name, defaultValue);
        return Math.max(min, Math.min(max, value));
    }

    interface AudioItemLoader
    {
        void load(String query, AudioLoadResultHandler handler);
    }

    public static class Result
    {
        private final List<List<AudioTrack>> tracksByItem;
        private final int failed;
        private final int retries;
        private final long elapsedMillis;

        private Result(List<List<AudioTrack>> tracksByItem, int failed, int retries, long elapsedMillis)
        {
            this.tracksByItem = tracksByItem;
            this.failed = failed;
            this.retries = retries;
            this.elapsedMillis = elapsedMillis;
        }

        public List<List<AudioTrack>> getTracksByItem()
        {
            return tracksByItem;
        }

        public List<AudioTrack> getOrderedTracks()
        {
            List<AudioTrack> tracks = new ArrayList<>();
            for(List<AudioTrack> itemTracks : tracksByItem)
                tracks.addAll(itemTracks);
            return tracks;
        }

        public int getLoadedTrackCount()
        {
            int count = 0;
            for(List<AudioTrack> itemTracks : tracksByItem)
                count += itemTracks.size();
            return count;
        }

        public int getFailed()
        {
            return failed;
        }

        public int getRetries()
        {
            return retries;
        }

        public long getElapsedMillis()
        {
            return elapsedMillis;
        }
    }

    private static class LoadRun
    {
        private final AudioItemLoader loader;
        private final ScheduledExecutorService scheduler;
        private final String playlistName;
        private final List<PlaylistTrack> items;
        private final Predicate<AudioTrack> tooLong;
        private final Consumer<Result> callback;
        private final int maxConcurrent;
        private final long dispatchIntervalMillis;
        private final int maxRetries;
        private final long rateLimitDelayMillis;
        private final Object lock = new Object();
        private final AtomicReferenceArray<List<AudioTrack>> loadedTracks;
        private final PriorityQueue<LoadAttempt> retryQueue = new PriorityQueue<>(Comparator.comparingLong(a -> a.readyAtNanos));
        private final long startedAt = System.nanoTime();
        private int nextIndex;
        private int inFlight;
        private int remaining;
        private int failed;
        private int retries;
        private long nextDispatchAtNanos;
        private boolean drainScheduled;
        private boolean completed;

        private LoadRun(AudioPlayerManager manager, ScheduledExecutorService scheduler, String playlistName,
                        List<PlaylistTrack> items, Predicate<AudioTrack> tooLong, Consumer<Result> callback,
                        int maxConcurrent, long dispatchIntervalMillis, int maxRetries, long rateLimitDelayMillis)
        {
            this((query, handler) -> manager.loadItem(query, handler), scheduler, playlistName, items, tooLong,
                    callback, maxConcurrent, dispatchIntervalMillis, maxRetries, rateLimitDelayMillis);
        }

        private LoadRun(AudioItemLoader loader, ScheduledExecutorService scheduler, String playlistName,
                        List<PlaylistTrack> items, Predicate<AudioTrack> tooLong, Consumer<Result> callback,
                        int maxConcurrent, long dispatchIntervalMillis, int maxRetries, long rateLimitDelayMillis)
        {
            this.loader = loader;
            this.scheduler = scheduler;
            this.playlistName = playlistName;
            this.items = new ArrayList<>(items);
            this.tooLong = tooLong;
            this.callback = callback;
            this.maxConcurrent = Math.max(1, maxConcurrent);
            this.dispatchIntervalMillis = Math.max(0L, dispatchIntervalMillis);
            this.maxRetries = Math.max(0, maxRetries);
            this.rateLimitDelayMillis = Math.max(1L, rateLimitDelayMillis);
            this.loadedTracks = new AtomicReferenceArray<>(items.size());
            this.remaining = items.size();
            this.nextDispatchAtNanos = System.nanoTime();
        }

        private void start()
        {
            if(items.isEmpty())
            {
                callback.accept(new Result(Collections.emptyList(), 0, 0, 0));
                return;
            }

            LOG.info("Loading playlist '{}' with {} items using maxConcurrent={}, dispatchIntervalMs={}, maxRetries={}",
                    playlistName, items.size(), maxConcurrent, dispatchIntervalMillis, maxRetries);
            scheduleDrain(0L);
        }

        private void scheduleDrain(long delayMillis)
        {
            synchronized(lock)
            {
                scheduleDrainLocked(delayMillis);
            }
        }

        private void scheduleDrainLocked(long delayMillis)
        {
            if(completed || drainScheduled)
                return;

            drainScheduled = true;
            scheduler.schedule(this::drain, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
        }

        private void drain()
        {
            LoadAttempt attempt;
            long delayMillis = -1L;
            synchronized(lock)
            {
                drainScheduled = false;
                attempt = nextAttemptLocked();
                if(attempt != null)
                    inFlight++;
                else if(!completed && hasPendingAttemptsLocked() && inFlight < maxConcurrent)
                    delayMillis = nextDelayMillisLocked();
            }

            if(attempt != null)
            {
                load(attempt);
                scheduleDrain(0L);
            }
            else if(delayMillis >= 0L)
            {
                scheduleDrain(delayMillis);
            }
        }

        private boolean hasPendingAttemptsLocked()
        {
            return nextIndex < items.size() || !retryQueue.isEmpty();
        }

        private LoadAttempt nextAttemptLocked()
        {
            if(completed || inFlight >= maxConcurrent)
                return null;

            long now = System.nanoTime();
            if(now < nextDispatchAtNanos)
                return null;

            LoadAttempt retry = retryQueue.peek();
            boolean hasReadyRetry = retry != null && retry.readyAtNanos <= now;
            boolean hasNewItem = nextIndex < items.size();
            if(!hasReadyRetry && !hasNewItem)
                return null;
            if(!reserveGlobalDispatchSlot(now, dispatchIntervalMillis))
                return null;

            if(hasReadyRetry)
            {
                retryQueue.poll();
                nextDispatchAtNanos = now + TimeUnit.MILLISECONDS.toNanos(dispatchIntervalMillis);
                return retry;
            }

            if(hasNewItem)
            {
                LoadAttempt attempt = new LoadAttempt(nextIndex++, 0, now, false);
                nextDispatchAtNanos = now + TimeUnit.MILLISECONDS.toNanos(dispatchIntervalMillis);
                return attempt;
            }
            return null;
        }

        private long nextDelayMillisLocked()
        {
            long now = System.nanoTime();
            long readyAt = nextDispatchAtNanos;
            readyAt = Math.max(readyAt, globalReadyAtNanos());
            LoadAttempt retry = retryQueue.peek();
            if(retry != null)
                readyAt = Math.max(readyAt, retry.readyAtNanos);
            return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(Math.max(0L, readyAt - now)));
        }

        private void load(LoadAttempt attempt)
        {
            PlaylistTrack item = items.get(attempt.index);
            String loadQuery = attempt.loadQuery(item);
            try
            {
                loader.load(loadQuery, new AudioLoadResultHandler()
                {
                    @Override
                    public void trackLoaded(AudioTrack track)
                    {
                        LoadOutcome outcome = acceptedTrack(track);
                        finish(attempt.index, outcome.tracks, outcome.failed);
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist)
                    {
                        if(playlist.isSearchResult())
                        {
                            if(playlist.getTracks().isEmpty())
                                finish(attempt.index, Collections.emptyList(), 1);
                            else
                            {
                                LoadOutcome outcome = acceptedTrack(playlist.getTracks().get(0));
                                finish(attempt.index, outcome.tracks, outcome.failed);
                            }
                        }
                        else if(playlist.getSelectedTrack() != null)
                        {
                            LoadOutcome outcome = acceptedTrack(playlist.getSelectedTrack());
                            finish(attempt.index, outcome.tracks, outcome.failed);
                        }
                        else
                        {
                            LoadOutcome outcome = acceptedTracks(playlist.getTracks());
                            finish(attempt.index, outcome.tracks, outcome.failed);
                        }
                    }

                    @Override
                    public void noMatches()
                    {
                        handleNoMatches(attempt, item);
                    }

                    @Override
                    public void loadFailed(FriendlyException exception)
                    {
                        handleLoadFailed(attempt, item, exception);
                    }
                });
            }
            catch(RuntimeException ex)
            {
                handleLoadFailed(attempt, item,
                        new FriendlyException("Error loading playlist item.", FriendlyException.Severity.SUSPICIOUS, ex));
            }
        }

        private LoadOutcome acceptedTrack(AudioTrack track)
        {
            if(tooLong.test(track))
                return new LoadOutcome(Collections.emptyList(), 1);
            return new LoadOutcome(List.of(track), 0);
        }

        private LoadOutcome acceptedTracks(List<AudioTrack> tracks)
        {
            if(tracks.isEmpty())
                return new LoadOutcome(Collections.emptyList(), 1);

            List<AudioTrack> accepted = new ArrayList<>();
            int rejected = 0;
            for(AudioTrack track : tracks)
            {
                if(tooLong.test(track))
                    rejected++;
                else
                    accepted.add(track);
            }
            return new LoadOutcome(accepted, rejected);
        }

        private void handleLoadFailed(LoadAttempt attempt, PlaylistTrack item, FriendlyException exception)
        {
            if(isRateLimit(exception) && attempt.retry < maxRetries)
            {
                long delayMillis = retryDelayMillis(attempt.retry);
                synchronized(lock)
                {
                    inFlight--;
                    retries++;
                    long readyAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
                    retryQueue.add(new LoadAttempt(attempt.index, attempt.retry + 1, readyAt, attempt.fallback));
                    nextDispatchAtNanos = Math.max(nextDispatchAtNanos, readyAt);
                    pauseGlobalDispatchUntil(readyAt);
                    LOG.warn("Rate limited while loading playlist '{}' item {} ('{}'); retry {}/{} in {}ms",
                            playlistName, attempt.index + 1, attempt.loadQuery(item), attempt.retry + 1, maxRetries, delayMillis);
                    scheduleDrainLocked(delayMillis);
                }
                return;
            }

            if(scheduleFallback(attempt, item, "load failure"))
                return;

            LOG.warn("Failed to load playlist '{}' item {} ('{}'); retries={}",
                    playlistName, attempt.index + 1, attempt.loadQuery(item), attempt.retry, exception);
            finish(attempt.index, Collections.emptyList(), 1);
        }

        private void handleNoMatches(LoadAttempt attempt, PlaylistTrack item)
        {
            if(scheduleFallback(attempt, item, "no matches"))
                return;
            finish(attempt.index, Collections.emptyList(), 1);
        }

        private boolean scheduleFallback(LoadAttempt attempt, PlaylistTrack item, String reason)
        {
            String fallbackQuery = item.getFallbackLoadQuery();
            if(attempt.fallback || fallbackQuery.isBlank())
                return false;

            synchronized(lock)
            {
                inFlight--;
                retryQueue.add(new LoadAttempt(attempt.index, 0, System.nanoTime(), true));
                LOG.info("Retrying playlist '{}' item {} with fallback query after {}; primary='{}'; fallback='{}'",
                        playlistName, attempt.index + 1, reason, attempt.loadQuery(item), fallbackQuery);
                scheduleDrainLocked(0L);
            }
            return true;
        }

        private long retryDelayMillis(int retry)
        {
            long multiplier = 1L << Math.min(retry, 4);
            return rateLimitDelayMillis * multiplier;
        }

        private void finish(int index, List<AudioTrack> tracks, int failedForItem)
        {
            Result result = null;
            synchronized(lock)
            {
                loadedTracks.set(index, List.copyOf(tracks));
                failed += failedForItem;
                inFlight--;
                remaining--;
                if(remaining == 0)
                {
                    completed = true;
                    result = resultLocked();
                }
                else
                {
                    scheduleDrainLocked(0L);
                }
            }

            if(result != null)
                callback.accept(result);
        }

        private Result resultLocked()
        {
            List<List<AudioTrack>> ordered = new ArrayList<>();
            for(int i = 0; i < loadedTracks.length(); i++)
            {
                List<AudioTrack> tracks = loadedTracks.get(i);
                ordered.add(tracks == null ? Collections.emptyList() : tracks);
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            return new Result(Collections.unmodifiableList(ordered), failed, retries, elapsedMillis);
        }
    }

    private static boolean reserveGlobalDispatchSlot(long nowNanos, long dispatchIntervalMillis)
    {
        synchronized(GLOBAL_DISPATCH_LOCK)
        {
            if(nowNanos < globalNextDispatchAtNanos)
                return false;
            globalNextDispatchAtNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(dispatchIntervalMillis);
            return true;
        }
    }

    private static long globalReadyAtNanos()
    {
        synchronized(GLOBAL_DISPATCH_LOCK)
        {
            return globalNextDispatchAtNanos;
        }
    }

    private static void pauseGlobalDispatchUntil(long readyAtNanos)
    {
        synchronized(GLOBAL_DISPATCH_LOCK)
        {
            globalNextDispatchAtNanos = Math.max(globalNextDispatchAtNanos, readyAtNanos);
        }
    }

    private static class LoadAttempt
    {
        private final int index;
        private final int retry;
        private final long readyAtNanos;
        private final boolean fallback;

        private LoadAttempt(int index, int retry, long readyAtNanos, boolean fallback)
        {
            this.index = index;
            this.retry = retry;
            this.readyAtNanos = readyAtNanos;
            this.fallback = fallback;
        }

        private String loadQuery(PlaylistTrack item)
        {
            return fallback ? item.getFallbackLoadQuery() : item.getLoadQuery();
        }
    }

    private static class LoadOutcome
    {
        private final List<AudioTrack> tracks;
        private final int failed;

        private LoadOutcome(List<AudioTrack> tracks, int failed)
        {
            this.tracks = tracks;
            this.failed = failed;
        }
    }
}
