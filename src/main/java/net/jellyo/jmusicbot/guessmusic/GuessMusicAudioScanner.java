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

    private GuessMusicAudioScanner()
    {
    }

    static ScanResult findFirstAudible(AudioTrack source)
    {
        return findFirstAudible(source, DEFAULT_SCAN_LIMIT_MS);
    }

    static ScanResult findFirstAudible(AudioTrack source, long scanLimitMillis)
    {
        if(source == null)
            return ScanResult.fallback();

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
            int audibleFrames = 0;
            long candidateTimecode = -1L;
            long latestTimecode = 0L;

            while(System.currentTimeMillis() < deadline && player.getPlayingTrack() != null)
            {
                AudioFrame frame = player.provide(500, TimeUnit.MILLISECONDS);
                if(frame == null)
                    continue;
                latestTimecode = Math.max(latestTimecode, frame.getTimecode());
                if(frame.getTimecode() > scanLimit)
                    break;

                if(isAudiblePcm16Le(frame.getData(), frame.getDataLength()))
                {
                    if(audibleFrames == 0)
                        candidateTimecode = frame.getTimecode();
                    audibleFrames++;
                    if(audibleFrames >= REQUIRED_AUDIBLE_FRAMES)
                        return ScanResult.detected(Math.max(0L, candidateTimecode - PRE_ROLL_MS));
                }
                else
                {
                    audibleFrames = 0;
                    candidateTimecode = -1L;
                }
            }

            LOG.debug("No audible frame found while scanning track {}; scanned={}ms", source.getIdentifier(), latestTimecode);
            return ScanResult.fallback();
        }
        catch(TimeoutException ex)
        {
            LOG.debug("Timed out while scanning first audible frame for {}", source.getIdentifier(), ex);
            return ScanResult.fallback();
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while scanning first audible frame for {}", source.getIdentifier(), ex);
            return ScanResult.fallback();
        }
        catch(RuntimeException ex)
        {
            LOG.debug("Failed to scan first audible frame for {}", source.getIdentifier(), ex);
            return ScanResult.fallback();
        }
        finally
        {
            if(player != null)
                player.destroy();
            manager.shutdown();
        }
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
