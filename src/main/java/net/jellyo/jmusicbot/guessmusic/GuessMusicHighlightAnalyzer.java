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
package com.jagrosh.jmusicbot.guessmusic;

import com.jagrosh.jmusicbot.guessmusic.GuessMusicHighlightStore.Highlight;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GuessMusicHighlightAnalyzer
{
    private static final Logger LOG = LoggerFactory.getLogger(GuessMusicHighlightAnalyzer.class);
    private static final long MIN_DURATION_MS = 60_000L;
    private static final long WINDOW_MS = 2_500L;
    private static final long REVEAL_CLIP_MS = 10_000L;
    private static final long ANALYSIS_WALL_TIMEOUT_MS = 18_000L;
    private static final long WINDOW_WALL_TIMEOUT_MS = 1_800L;
    private static final int MAX_CANDIDATES = 14;
    private static final double MIN_AVERAGE_RMS = 0.018d;
    private static final double MIN_AUDIBLE_RATIO = 0.65d;

    private GuessMusicHighlightAnalyzer()
    {
    }

    static Optional<Highlight> findHighlight(AudioTrack source, long firstAudibleMillis, long clueEndMillis)
    {
        if(source == null || source.getDuration() == Long.MAX_VALUE || source.getDuration() < MIN_DURATION_MS)
            return Optional.empty();

        List<Long> candidates = candidatePositions(source.getDuration(), firstAudibleMillis, clueEndMillis);
        if(candidates.isEmpty())
            return Optional.empty();

        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        AudioPlayer player = null;
        try
        {
            manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.DISCORD_PCM_S16_LE);
            manager.setFrameBufferDuration(500);
            player = manager.createPlayer();
            player.setVolume(100);

            long deadline = System.currentTimeMillis() + ANALYSIS_WALL_TIMEOUT_MS;
            WindowScore best = null;
            for(Long candidate : candidates)
            {
                if(System.currentTimeMillis() >= deadline)
                    break;
                WindowScore score = scoreWindow(player, source, candidate, source.getDuration());
                if(score.accepted() && (best == null || score.score > best.score))
                    best = score;
            }

            if(best == null)
                return Optional.empty();
            return Optional.of(new Highlight(best.positionMillis, Math.min(1d, best.score / 40d), false));
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        catch(TimeoutException | RuntimeException ex)
        {
            LOG.debug("Failed to analyze guess music highlight for {}", source.getIdentifier(), ex);
            return Optional.empty();
        }
        finally
        {
            if(player != null)
                player.destroy();
            manager.shutdown();
        }
    }

    static List<Long> candidatePositions(long durationMillis, long firstAudibleMillis, long clueEndMillis)
    {
        long usableEnd = Math.min(durationMillis - REVEAL_CLIP_MS - 5_000L, Math.round(durationMillis * 0.78d));
        long usableStart = Math.max(Math.max(30_000L, firstAudibleMillis + 25_000L), Math.round(durationMillis * 0.28d));
        usableStart = Math.max(usableStart, clueEndMillis + 1_000L);
        if(usableEnd <= usableStart)
            return List.of();

        List<Long> candidates = new ArrayList<>();
        double[] fractions = {0.34d, 0.42d, 0.50d, 0.58d, 0.66d};
        for(double fraction : fractions)
        {
            long position = Math.round(durationMillis * fraction);
            if(position >= usableStart && position <= usableEnd)
                candidates.add(position);
        }

        long step = Math.max(8_000L, (usableEnd - usableStart) / 8L);
        for(long position = usableStart; position <= usableEnd && candidates.size() < MAX_CANDIDATES * 2; position += step)
            candidates.add(position);

        return candidates.stream()
                .distinct()
                .sorted(Comparator.comparingLong(position -> Math.abs(position - Math.round(durationMillis * 0.52d))))
                .limit(MAX_CANDIDATES)
                .collect(java.util.stream.Collectors.toList());
    }

    private static WindowScore scoreWindow(AudioPlayer player, AudioTrack source, long positionMillis,
                                           long durationMillis) throws InterruptedException, TimeoutException
    {
        player.stopTrack();
        AudioTrack sample = source.makeClone();
        if(sample.isSeekable())
            sample.setPosition(Math.max(0L, positionMillis));
        player.playTrack(sample);

        long deadline = System.currentTimeMillis() + WINDOW_WALL_TIMEOUT_MS;
        long endPosition = positionMillis + WINDOW_MS;
        int frames = 0;
        int audibleFrames = 0;
        double sumRms = 0d;
        double sumSquares = 0d;

        while(System.currentTimeMillis() < deadline && player.getPlayingTrack() != null)
        {
            AudioFrame frame = player.provide(200, TimeUnit.MILLISECONDS);
            if(frame == null)
                continue;
            if(frame.getTimecode() > endPosition)
                break;

            double rms = GuessMusicAudioScanner.rmsPcm16Le(frame.getData(), frame.getDataLength());
            frames++;
            sumRms += rms;
            sumSquares += rms * rms;
            if(rms >= MIN_AVERAGE_RMS)
                audibleFrames++;
        }
        player.stopTrack();

        if(frames == 0)
            return WindowScore.rejected(positionMillis);

        double averageRms = sumRms / frames;
        double audibleRatio = (double)audibleFrames / frames;
        double variance = Math.max(0d, (sumSquares / frames) - (averageRms * averageRms));
        double stability = 1d - Math.min(1d, Math.sqrt(variance) / Math.max(0.001d, averageRms));
        double fraction = (double)positionMillis / durationMillis;
        double centerBias = Math.max(0d, 1d - Math.abs(fraction - 0.54d) * 2.1d);
        double score = averageRms * 115d + audibleRatio * 18d + stability * 5d + centerBias * 6d;
        return new WindowScore(positionMillis, score, averageRms, audibleRatio);
    }

    private static final class WindowScore
    {
        private final long positionMillis;
        private final double score;
        private final double averageRms;
        private final double audibleRatio;

        private WindowScore(long positionMillis, double score, double averageRms, double audibleRatio)
        {
            this.positionMillis = positionMillis;
            this.score = score;
            this.averageRms = averageRms;
            this.audibleRatio = audibleRatio;
        }

        private static WindowScore rejected(long positionMillis)
        {
            return new WindowScore(positionMillis, 0d, 0d, 0d);
        }

        private boolean accepted()
        {
            return averageRms >= MIN_AVERAGE_RMS && audibleRatio >= MIN_AUDIBLE_RATIO;
        }
    }
}
