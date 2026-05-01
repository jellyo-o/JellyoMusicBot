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
package com.jagrosh.jmusicbot.audio.filter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class AudioFilterPresetTest
{
    @Test
    public void parsesPrimaryPresetIds()
    {
        assertEquals(AudioFilterPreset.BASS_BOOST, AudioFilterPreset.fromInput("bassboost").get());
        assertEquals(AudioFilterPreset.NIGHTCORE, AudioFilterPreset.fromInput("nightcore").get());
        assertEquals(AudioFilterPreset.EIGHT_D, AudioFilterPreset.fromInput("8d").get());
        assertEquals(AudioFilterPreset.VAPORWAVE, AudioFilterPreset.fromInput("vaporwave").get());
        assertEquals(AudioFilterPreset.TREMOLO, AudioFilterPreset.fromInput("tremolo").get());
        assertEquals(AudioFilterPreset.KARAOKE, AudioFilterPreset.fromInput("karaoke").get());
        assertEquals(AudioFilterPreset.OFF, AudioFilterPreset.fromInput("off").get());
    }

    @Test
    public void parsesUserFriendlyAliases()
    {
        assertEquals(AudioFilterPreset.BASS_BOOST, AudioFilterPreset.fromInput("bass boost").get());
        assertEquals(AudioFilterPreset.EIGHT_D, AudioFilterPreset.fromInput("eight-d").get());
        assertEquals(AudioFilterPreset.VAPORWAVE, AudioFilterPreset.fromInput("slowed").get());
        assertEquals(AudioFilterPreset.KARAOKE, AudioFilterPreset.fromInput("vocal remover").get());
        assertEquals(AudioFilterPreset.OFF, AudioFilterPreset.fromInput("clear").get());
    }

    @Test
    public void rejectsUnknownPreset()
    {
        assertFalse(AudioFilterPreset.fromInput("underwater").isPresent());
    }
}
