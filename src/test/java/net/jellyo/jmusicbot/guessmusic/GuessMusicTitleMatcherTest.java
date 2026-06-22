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

import com.jagrosh.jmusicbot.guessmusic.GuessMusicTitleMatcher.MatchMode;
import com.jagrosh.jmusicbot.guessmusic.GuessMusicTitleMatcher.ParsedTitle;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuessMusicTitleMatcherTest
{
    @Test
    public void parsesArtistTitleAndStripsVideoLabels()
    {
        ParsedTitle parsed = GuessMusicTitleMatcher.parse("Porter Robinson - Cheerleader (Official Music Video)", "Porter Robinson");

        assertEquals("Cheerleader", parsed.getTitle());
        assertEquals("Porter Robinson", parsed.getArtist());
        assertEquals("cheerleader", parsed.getNormalizedTitle());
    }

    @Test
    public void forgivingModeAcceptsPartialTitleButStrictModeDoesNot()
    {
        ParsedTitle parsed = GuessMusicTitleMatcher.parse("Against The Current - weapon (Official Lyric Video)", "AgainstTheCurrentNY");

        assertTrue(GuessMusicTitleMatcher.matches("weapon", parsed, MatchMode.FORGIVING));
        assertTrue(GuessMusicTitleMatcher.matches("weapn", parsed, MatchMode.FORGIVING));
        assertFalse(GuessMusicTitleMatcher.matches("weap", parsed, MatchMode.STRICT));
    }

    @Test
    public void artistOnlyGuessIsNotAccepted()
    {
        ParsedTitle parsed = GuessMusicTitleMatcher.parse("Madeon - All My Friends", "Madeon");

        assertFalse(GuessMusicTitleMatcher.matches("Madeon", parsed, MatchMode.FORGIVING));
    }

    @Test
    public void meaningfulParenthesesStayInParsedTitle()
    {
        ParsedTitle parsed = GuessMusicTitleMatcher.parse("Eurythmics - Sweet Dreams (Are Made of This)", "Eurythmics");

        assertEquals("Sweet Dreams (Are Made of This)", parsed.getTitle());
        assertTrue(GuessMusicTitleMatcher.matches("sweet dreams are made of this", parsed, MatchMode.FORGIVING));
    }

    @Test
    public void strictModeAcceptsNormalizedFullTitleOnly()
    {
        ParsedTitle parsed = GuessMusicTitleMatcher.parse("Eurythmics - Sweet Dreams (Are Made of This)", "Eurythmics");

        assertTrue(GuessMusicTitleMatcher.matches("SWEET DREAMS ARE MADE OF THIS", parsed, MatchMode.STRICT));
        assertFalse(GuessMusicTitleMatcher.matches("sweet dreams", parsed, MatchMode.STRICT));
    }

    @Test
    public void forgivingModeAcceptsMissingSmallWords()
    {
        ParsedTitle parsed = GuessMusicTitleMatcher.parse("Coldplay - A Sky Full of Stars", "Coldplay");

        assertTrue(GuessMusicTitleMatcher.matches("sky full stars", parsed, MatchMode.FORGIVING));
        assertTrue(GuessMusicTitleMatcher.matches("a sky full of stars", parsed, MatchMode.STRICT));
        assertFalse(GuessMusicTitleMatcher.matches("sky full stars", parsed, MatchMode.STRICT));
    }

    @Test
    public void mixSuffixDoesNotBecomeSongTitleWhenAuthorIsKnown()
    {
        ParsedTitle dashed = GuessMusicTitleMatcher.parse("Treasure - Cash Cash Radio Mix", "Bruno Mars");
        ParsedTitle bracketed = GuessMusicTitleMatcher.parse("Treasure (Cash Cash Radio Mix)", "Bruno Mars");

        assertEquals("Treasure", dashed.getTitle());
        assertEquals("Bruno Mars", dashed.getArtist());
        assertEquals("treasure", dashed.getNormalizedTitle());
        assertEquals("Treasure", bracketed.getTitle());
        assertTrue(GuessMusicTitleMatcher.matches("Treasure", dashed, MatchMode.STRICT));
    }

    @Test
    public void reversedOfficialVideoTitleUsesSongName()
    {
        ParsedTitle parsed = GuessMusicTitleMatcher.parse(
                "Heavy [Official Music Video] - Linkin Park (feat. Kiiara)", "Linkin Park");

        assertEquals("Heavy", parsed.getTitle());
        assertEquals("Linkin Park", parsed.getArtist());
        assertTrue(GuessMusicTitleMatcher.matches("Heavy", parsed, MatchMode.STRICT));
        assertFalse(GuessMusicTitleMatcher.matches("feat Kiiara", parsed, MatchMode.FORGIVING));
    }

    @Test
    public void soundtrackAndVersionDecorationsDoNotBecomeSongTitles()
    {
        ParsedTitle soundtrack = GuessMusicTitleMatcher.parse("Immortals (From Big Hero 6)", "Fall Out Boy");
        ParsedTitle acoustic = GuessMusicTitleMatcher.parse("One More Light (Acoustic Version)", "Linkin Park");
        ParsedTitle revisited = GuessMusicTitleMatcher.parse("Don't Stop Me Now (...Revisited)", "Queen Official");
        ParsedTitle actualTitle = GuessMusicTitleMatcher.parse("Queen - Revisited", "Queen");

        assertEquals("Immortals", soundtrack.getTitle());
        assertEquals("One More Light", acoustic.getTitle());
        assertEquals("Don't Stop Me Now", revisited.getTitle());
        assertEquals("Revisited", actualTitle.getTitle());
    }

    @Test
    public void nativeTitlesKeepNativeCharactersAndAcceptAliases()
    {
        ParsedTitle show = GuessMusicTitleMatcher.parse("Ado - 唱 (Show)", "Ado");
        ParsedTitle slash = GuessMusicTitleMatcher.parse("Ado - 私は最強 / I'm invincible", "Ado");

        assertEquals("唱 (Show)", show.getTitle());
        assertEquals("唱 show", show.getNormalizedTitle());
        assertTrue(show.getAliases().contains("Show"));
        assertTrue(GuessMusicTitleMatcher.matches("唱", show, MatchMode.FORGIVING));
        assertTrue(GuessMusicTitleMatcher.matches("Show", show, MatchMode.STRICT));
        assertTrue(GuessMusicTitleMatcher.matches("im invincible", slash, MatchMode.FORGIVING));
    }

    @Test
    public void knownNativeTitleCanAcceptAliasFromLoadedTrack()
    {
        ParsedTitle known = GuessMusicTitleMatcher.parse("唱", "Ado");
        ParsedTitle loaded = GuessMusicTitleMatcher.parse("Ado - 唱 (Show)", "Ado");
        ParsedTitle merged = known.withAliasesFrom(loaded);

        assertTrue(GuessMusicTitleMatcher.matches("唱", merged, MatchMode.STRICT));
        assertTrue(GuessMusicTitleMatcher.matches("Show", merged, MatchMode.STRICT));
    }
}
