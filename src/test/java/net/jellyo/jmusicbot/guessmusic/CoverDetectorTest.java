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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CoverDetectorTest
{
    @Test
    public void disabledDetectorNeverReportsCovers()
    {
        CoverDetector detector = new CoverDetector(false);
        assertFalse(detector.isEnabled());
        assertFalse(detector.isCover("Against The Current", "22"));
    }

    @Test
    public void enabledDetectorIgnoresBlankInput()
    {
        CoverDetector detector = new CoverDetector(true);
        assertTrue(detector.isEnabled());
        assertFalse(detector.isCover("", "22"));
        assertFalse(detector.isCover("Against The Current", ""));
        assertFalse(detector.isCover(null, null));
    }

    @Test
    public void parseRecordingIdRequiresExactArtistAndTitleMatch()
    {
        String json = "{\"recordings\":["
                + "{\"id\":\"rec-other\",\"title\":\"22\",\"artist-credit\":[{\"name\":\"Taylor Swift\"}]},"
                + "{\"id\":\"rec-atc\",\"title\":\"22\",\"artist-credit\":[{\"artist\":{\"name\":\"Against The Current\"}}]}"
                + "]}";
        // We are asking specifically about the Against The Current recording of "22", so its id is returned.
        assertEquals("rec-atc", CoverDetector.parseRecordingId(json, "Against The Current", "22"));
    }

    @Test
    public void parseRecordingIdRejectsSubstringArtistCollisions()
    {
        // "Queen" must NOT match "Queens of the Stone Age" — a loose substring match would drop a real original.
        String json = "{\"recordings\":["
                + "{\"id\":\"rec-qotsa\",\"title\":\"Go With The Flow\",\"artist-credit\":[{\"name\":\"Queens of the Stone Age\"}]}"
                + "]}";
        assertNull(CoverDetector.parseRecordingId(json, "Queen", "Go With The Flow"));
    }

    @Test
    public void parseRecordingIdRequiresTitleMatch()
    {
        String json = "{\"recordings\":["
                + "{\"id\":\"rec-atc\",\"title\":\"Gravity\",\"artist-credit\":[{\"name\":\"Against The Current\"}]}"
                + "]}";
        // Right artist, wrong title -> not the recording we asked about.
        assertNull(CoverDetector.parseRecordingId(json, "Against The Current", "22"));
    }

    @Test
    public void parseRecordingIdReturnsNullWhenNothingMatches()
    {
        String json = "{\"recordings\":["
                + "{\"id\":\"rec-other\",\"title\":\"22\",\"artist-credit\":[{\"name\":\"Taylor Swift\"}]}"
                + "]}";
        // Conservative: no recording credited to the wanted artist -> don't guess.
        assertNull(CoverDetector.parseRecordingId(json, "Against The Current", "22"));
        assertNull(CoverDetector.parseRecordingId("{\"recordings\":[]}", "x", "y"));
        assertNull(CoverDetector.parseRecordingId(null, "x", "y"));
    }

    @Test
    public void parseIsCoverDetectsCoverPerformanceAttribute()
    {
        String cover = "{\"relations\":["
                + "{\"type\":\"performance\",\"attributes\":[\"cover\"],\"work\":{\"title\":\"22\"}}"
                + "]}";
        assertTrue(CoverDetector.parseIsCover(cover));
    }

    @Test
    public void parseIsCoverFalseForOriginalPerformance()
    {
        String original = "{\"relations\":["
                + "{\"type\":\"performance\",\"attributes\":[],\"work\":{\"title\":\"Blinding Lights\"}}"
                + "]}";
        assertFalse(CoverDetector.parseIsCover(original));
        assertFalse(CoverDetector.parseIsCover("{\"relations\":[]}"));
        assertFalse(CoverDetector.parseIsCover("{}"));
        assertFalse(CoverDetector.parseIsCover(null));
    }
}
