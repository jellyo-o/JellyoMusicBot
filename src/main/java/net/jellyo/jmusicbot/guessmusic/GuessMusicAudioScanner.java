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

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GuessMusicAudioScanner
{
    private static final Logger LOG = LoggerFactory.getLogger(GuessMusicAudioScanner.class);
    private static final long DEFAULT_SCAN_LIMIT_MS = 45_000L;
    private static final long WALL_TIMEOUT_MS = 15_000L;
    private static final double AUDIBLE_RMS_THRESHOLD = 0.012;
    private static final int REQUIRED_AUDIBLE_FRAMES = 3;
    private static final long PRE_ROLL_MS = 80L;
    // Sustained-window scan: pick the first clip-length window that is mostly audible so a short
    // (hardcore/impossible) clip never lands on a brief transient or a near-silent intro.
    private static final double SUSTAINED_RMS_BAR = 0.016;
    private static final double SUSTAINED_AUDIBLE_RATIO = 0.6;
    private static final long MAX_VALIDATE_MS = 5_000L;
    private static final long MIN_VALIDATE_MS = 600L;
    private static final long SHORT_CLIP_MS = 1_500L;
    private static final double SHORT_CLIP_RMS_MULTIPLIER = 1.25;
    private static final long FRAME_TOLERANCE_MS = 40L;

    private GuessMusicAudioScanner()
    {
    }

    /**
     * Finds the first position whose entire clip-length window is sustained-audible, so a short clip
     * (hardcore/impossible mode) does not start on a brief transient or a near-silent intro. Falls back
     * to the loudest window seen, then to the first audible frame (the old behaviour), then to 0 — so it
     * is never worse than a plain first-audible scan.
     */
    static ScanResult findClipStart(AudioTrack source, long clipMs)
    {
        return findClipStart(source, clipMs, DEFAULT_SCAN_LIMIT_MS);
    }

    static ScanResult findClipStart(AudioTrack source, long clipMs, long scanLimitMillis)
    {
        if(source == null)
            return ScanResult.fallback();

        long validateMs = Math.max(MIN_VALIDATE_MS, Math.min(MAX_VALIDATE_MS, clipMs));

        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        AudioPlayer player = null;
        try
        {
            manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.DISCORD_PCM_S16_LE);
            manager.setFrameBufferDuration(500);
            player = manager.createPlayer();
            player.setVolume(100);

            AudioTrack track = source.makeClone();
            if(track.isSeekable())
                track.setPosition(0L);
            player.playTrack(track);

            long scanLimit = Math.max(1_000L, Math.min(scanLimitMillis, safeDurationLimit(source)));
            long deadline = System.currentTimeMillis() + WALL_TIMEOUT_MS;
            java.util.ArrayDeque<long[]> window = new java.util.ArrayDeque<>();
            double windowRms = 0d;
            int windowAudible = 0;
            long bestWindowStart = -1L;
            double bestWindowRms = -1d;
            int consecutive = 0;
            long consecutiveStart = -1L;
            long firstAudible = -1L;

            while(System.currentTimeMillis() < deadline && player.getPlayingTrack() != null)
            {
                AudioFrame frame = player.provide(500, TimeUnit.MILLISECONDS);
                if(frame == null)
                    continue;
                long timecode = frame.getTimecode();
                if(timecode > scanLimit)
                    break;

                double rms = rmsPcm16Le(frame.getData(), frame.getDataLength());

                // Track the plain first-audible position (3 consecutive frames) as a last-resort fallback.
                if(rms >= AUDIBLE_RMS_THRESHOLD)
                {
                    if(consecutive == 0)
                        consecutiveStart = timecode;
                    consecutive++;
                    if(consecutive >= REQUIRED_AUDIBLE_FRAMES && firstAudible < 0L)
                        firstAudible = Math.max(0L, consecutiveStart - PRE_ROLL_MS);
                }
                else
                {
                    consecutive = 0;
                    consecutiveStart = -1L;
                }

                window.addLast(new long[]{timecode, Double.doubleToRawLongBits(rms)});
                windowRms += rms;
                if(rms >= SUSTAINED_RMS_BAR)
                    windowAudible++;
                while(!window.isEmpty() && timecode - window.peekFirst()[0] >= validateMs)
                {
                    long[] evicted = window.removeFirst();
                    double evictedRms = Double.longBitsToDouble(evicted[1]);
                    windowRms -= evictedRms;
                    if(evictedRms >= SUSTAINED_RMS_BAR)
                        windowAudible--;
                }

                long windowStart = window.peekFirst()[0];
                if(timecode - windowStart >= validateMs - FRAME_TOLERANCE_MS)
                {
                    int frames = window.size();
                    double averageRms = windowRms / frames;
                    double audibleRatio = (double)windowAudible / frames;
                    if(qualifiesAsClipStart(averageRms, audibleRatio, clipMs))
                        return ScanResult.detected(windowStart);
                    if(averageRms > bestWindowRms)
                    {
                        bestWindowRms = averageRms;
                        bestWindowStart = windowStart;
                    }
                }
            }

            if(bestWindowStart >= 0L)
                return ScanResult.detected(bestWindowStart);
            if(firstAudible >= 0L)
                return ScanResult.detected(firstAudible);
            LOG.debug("No sustained audible window found while scanning track {}", source.getIdentifier());
            return ScanResult.fallback();
        }
        catch(TimeoutException ex)
        {
            LOG.debug("Timed out while scanning clip start for {}", source.getIdentifier(), ex);
            return ScanResult.fallback();
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while scanning clip start for {}", source.getIdentifier(), ex);
            return ScanResult.fallback();
        }
        catch(RuntimeException ex)
        {
            LOG.debug("Failed to scan clip start for {}", source.getIdentifier(), ex);
            return ScanResult.fallback();
        }
        finally
        {
            if(player != null)
                player.destroy();
            manager.shutdown();
        }
    }

    // A clip-length window is a good start point when it is mostly audible; very short clips
    // (impossible mode) demand a louder window so the one second isn't a quiet pocket of the song.
    static boolean qualifiesAsClipStart(double averageRms, double audibleRatio, long clipMs)
    {
        double requiredRms = clipMs <= SHORT_CLIP_MS ? SUSTAINED_RMS_BAR * SHORT_CLIP_RMS_MULTIPLIER : SUSTAINED_RMS_BAR;
        return audibleRatio >= SUSTAINED_AUDIBLE_RATIO && averageRms >= requiredRms;
    }

    static boolean isAudiblePcm16Le(byte[] data, int length)
    {
        return rmsPcm16Le(data, length) >= AUDIBLE_RMS_THRESHOLD;
    }

    static double rmsPcm16Le(byte[] data, int length)
    {
        if(data == null || length < 2)
            return 0d;

        int samples = Math.min(length, data.length) / 2;
        if(samples == 0)
            return 0d;

        double sumSquares = 0.0;
        for(int i = 0; i < samples * 2; i += 2)
        {
            int lo = data[i] & 0xFF;
            int hi = data[i + 1];
            short sample = (short)((hi << 8) | lo);
            double normalized = sample / 32768.0;
            sumSquares += normalized * normalized;
        }

        return Math.sqrt(sumSquares / samples);
    }

    private static long safeDurationLimit(AudioTrack track)
    {
        long duration = track.getDuration();
        if(duration <= 0L || duration == Long.MAX_VALUE)
            return DEFAULT_SCAN_LIMIT_MS;
        return Math.min(DEFAULT_SCAN_LIMIT_MS, duration);
    }

    static final class ScanResult
    {
        private final long positionMillis;
        private final boolean detected;

        private ScanResult(long positionMillis, boolean detected)
        {
            this.positionMillis = Math.max(0L, positionMillis);
            this.detected = detected;
        }

        static ScanResult detected(long positionMillis)
        {
            return new ScanResult(positionMillis, true);
        }

        static ScanResult fallback()
        {
            return new ScanResult(0L, false);
        }

        long getPositionMillis()
        {
            return positionMillis;
        }

        boolean isDetected()
        {
            return detected;
        }
    }
}
