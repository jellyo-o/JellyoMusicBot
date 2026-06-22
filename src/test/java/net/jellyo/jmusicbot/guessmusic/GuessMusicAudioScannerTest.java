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
}
