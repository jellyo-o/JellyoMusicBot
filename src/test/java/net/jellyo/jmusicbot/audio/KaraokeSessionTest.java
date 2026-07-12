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

import com.jagrosh.jmusicbot.lyrics.LrcLyrics;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The live karaoke line is chosen a fixed lead ahead of the exact playback position so it does not
 * read as late once the message edit renders in Discord.
 */
public class KaraokeSessionTest
{
    // a@5s, b@10s, c@20s.
    private static final LrcLyrics LRC = LrcLyrics.parse("[00:05.00]a\n[00:10.00]b\n[00:20.00]c");

    @Test
    public void defaultLeadIsHalfASecond()
    {
        assertEquals(500L, KaraokeSession.LEAD_MILLIS);
    }

    @Test
    public void leadsIntoTheNextLineEarly()
    {
        long lead = KaraokeSession.LEAD_MILLIS;
        // Exactly `lead` ms before b's timestamp already shows b...
        assertEquals(1, KaraokeSession.lineIndexFor(LRC, 10_000L - lead));
        // ...and one ms earlier still shows a, so the switch happens exactly `lead` early.
        assertEquals(0, KaraokeSession.lineIndexFor(LRC, 10_000L - lead - 1));
    }

    @Test
    public void withoutLeadTheRawLookupStillTrailsTheBoundary()
    {
        // Baseline sanity: the pure lookup only advances at the timestamp itself.
        assertEquals(0, LRC.lineIndexAt(9_999L));
        assertEquals(1, LRC.lineIndexAt(10_000L));
    }

    @Test
    public void introBeforeFirstLineStaysBeforeItEvenWithLead()
    {
        // Far enough before the first line that even the lead does not reach it: index -1 (intro).
        assertEquals(-1, KaraokeSession.lineIndexFor(LRC, 0L));
        // The lead pulls the first line in early too: `lead` ms before its timestamp shows a.
        assertEquals(0, KaraokeSession.lineIndexFor(LRC, 5_000L - KaraokeSession.LEAD_MILLIS));
    }
}
