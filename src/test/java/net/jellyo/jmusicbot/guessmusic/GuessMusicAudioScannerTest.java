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

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuessMusicAudioScannerTest
{
    @Test
    public void pcmScanTreatsSilenceAsNotAudible()
    {
        byte[] silence = new byte[3840];

        assertFalse(GuessMusicAudioScanner.isAudiblePcm16Le(silence, silence.length));
    }

    @Test
    public void pcmScanDetectsAudibleSamples()
    {
        byte[] audio = new byte[3840];
        short sample = (short)(32767 * 0.2);
        for(int i = 0; i < audio.length; i += 2)
        {
            audio[i] = (byte)(sample & 0xFF);
            audio[i + 1] = (byte)((sample >> 8) & 0xFF);
        }

        assertTrue(GuessMusicAudioScanner.isAudiblePcm16Le(audio, audio.length));
    }

    @Test
    public void clipStartWindowRequiresSustainedAudibleEnergy()
    {
        // A clearly audible, mostly-continuous window qualifies for a normal clip.
        assertTrue(GuessMusicAudioScanner.qualifiesAsClipStart(0.05, 1.0, 5_000L));
        // Too quiet (average below the sustained bar) — the old bug where a clip starts on near-silence.
        assertFalse(GuessMusicAudioScanner.qualifiesAsClipStart(0.005, 1.0, 5_000L));
        // Too gappy (mostly silent frames) even if a few are loud.
        assertFalse(GuessMusicAudioScanner.qualifiesAsClipStart(0.05, 0.4, 5_000L));
    }

    @Test
    public void shortClipsDemandLouderWindowThanLongClips()
    {
        // 0.017 average clears the normal bar (0.016) but not the boosted impossible-mode bar (0.02).
        assertTrue(GuessMusicAudioScanner.qualifiesAsClipStart(0.017, 0.8, 5_000L));
        assertFalse(GuessMusicAudioScanner.qualifiesAsClipStart(0.017, 0.8, 1_000L));
        // A genuinely loud window still qualifies for a one-second impossible clip.
        assertTrue(GuessMusicAudioScanner.qualifiesAsClipStart(0.05, 0.8, 1_000L));
    }
}
