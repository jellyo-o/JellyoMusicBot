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

import com.jagrosh.jmusicbot.guessmusic.GuessMusicTitleMatcher.ParsedTitle;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GuessMusicServiceTest
{
    @Test
    public void knownSongQueriesPreferAudioBeforeOriginalUrl()
    {
        ParsedTitle parsed = GuessMusicTitleMatcher.parse("Porter Robinson - Cheerleader (Official Music Video)", "Porter Robinson");

        List<String> queries = GuessMusicService.preferredAudioQueries(parsed, "https://youtube.com/watch?v=mv");

        assertEquals("ytsearch:Porter Robinson Cheerleader official audio", queries.get(0));
        assertEquals("ytsearch:Porter Robinson Cheerleader official lyric video", queries.get(1));
        assertEquals("ytsearch:Porter Robinson Cheerleader lyric video", queries.get(2));
        assertEquals("ytsearch:Porter Robinson Cheerleader lyrics", queries.get(3));
        assertEquals("ytsearch:Porter Robinson Cheerleader topic", queries.get(4));
        assertEquals("https://youtube.com/watch?v=mv", queries.get(5));
    }

    @Test
    public void nativeSongQueriesIncludeAliasAndLookupSearches()
    {
        ParsedTitle parsed = GuessMusicTitleMatcher.parse("Ado - 唱 (Show)", "Ado");

        List<String> queries = GuessMusicService.preferredAudioQueries(parsed, null);

        assertTrue(queries.contains("ytsearch:Ado 唱 (Show) official audio"));
        assertTrue(queries.contains("ytsearch:Ado Show official audio"));
        assertTrue(queries.contains("ytsearch:Ado 唱 (Show) romanized title"));
        assertTrue(queries.contains("ytsearch:Ado 唱 (Show) english title"));
    }

    @Test
    public void artistFilterAcceptsMatchingArtistAndRejectsCovers()
    {
        ParsedTitle actual = GuessMusicTitleMatcher.parse("NewJeans - Super Shy (Official Audio)", "NewJeans");
        ParsedTitle secondArtist = GuessMusicTitleMatcher.parse("Immortals", "Fall Out Boy");
        ParsedTitle cover = GuessMusicTitleMatcher.parse("Super Shy Cover", "Random Singer");

        assertTrue(GuessMusicService.matchesArtistFilter("NewJeans", actual, "https://open.spotify.com/track/1"));
        assertTrue(GuessMusicService.matchesArtistFilter("NewJeans, Fall Out Boy", secondArtist, "ytsearch:fall out boy immortals"));
        assertFalse(GuessMusicService.matchesArtistFilter("NewJeans", cover, "ytsearch:Random Singer Super Shy cover"));
    }

    @Test
    public void artistFiltersParseCommaSeparatedArtists()
    {
        assertEquals(List.of("Fall Out Boy", "Linkin Park", "Panic! At The Disco"),
                GuessMusicService.parseArtistFilters(" Fall Out Boy, Linkin Park; Panic! At The Disco "));
    }

    @Test
    public void artistModeRequiresArtistMetadataMatch()
    {
        ParsedTitle unofficial = GuessMusicTitleMatcher.parse(
                "Linkin Park - One More Light (Lyrics / Lyric Video)", "Royal Music");

        assertEquals("Royal Music", unofficial.getArtist());
        assertFalse(GuessMusicService.matchesArtistFilter("Linkin Park", unofficial,
                "ytsearch:Linkin Park One More Light lyric video"));
        assertFalse(GuessMusicService.artistMetadataMatches("Linkin Park", unofficial.getArtist()));
        assertTrue(GuessMusicService.artistMetadataMatches("Linkin Park", "Linkin Park - Topic"));
    }

    @Test
    public void artistModeBlocksRemixCandidates()
    {
        assertTrue(GuessMusicService.isRemixCandidate("newjeans super shy remix official audio"));
        assertTrue(GuessMusicService.isRemixCandidate("newjeans super shy sped up"));
        assertFalse(GuessMusicService.isRemixCandidate("newjeans super shy official audio"));
    }

    @Test
    public void songIdentityKeysTreatVersionVariantsAsDuplicates()
    {
        ParsedTitle official = GuessMusicTitleMatcher.parse("Immortals", "Fall Out Boy");
        ParsedTitle soundtrack = GuessMusicTitleMatcher.parse("Immortals (From Big Hero 6)", "Fall Out Boy");
        ParsedTitle acoustic = GuessMusicTitleMatcher.parse("Immortals (Acoustic Version)", "Fall Out Boy");
        ParsedTitle queenLyric = GuessMusicTitleMatcher.parse("Queen - Don't Stop Me Now (Official Lyric Video)", "Queen Official");
        ParsedTitle queenRevisited = GuessMusicTitleMatcher.parse("Don't Stop Me Now (...Revisited)", "Queen Official");

        assertFalse(Collections.disjoint(GuessMusicService.songIdentityKeys(official),
                GuessMusicService.songIdentityKeys(soundtrack)));
        assertFalse(Collections.disjoint(GuessMusicService.songIdentityKeys(official),
                GuessMusicService.songIdentityKeys(acoustic)));
        assertFalse(Collections.disjoint(GuessMusicService.songIdentityKeys(queenLyric),
                GuessMusicService.songIdentityKeys(queenRevisited)));
        assertEquals(GuessMusicService.duplicateKey("ytsearch:fall out boy immortals official audio",
                        official.getTitle(), official.getArtist()),
                GuessMusicService.duplicateKey("ytsearch:fall out boy immortals lyric video",
                        soundtrack.getTitle(), soundtrack.getArtist()));
        assertEquals(GuessMusicService.duplicateKey("ytsearch:queen dont stop me now official lyric video",
                        queenLyric.getTitle(), queenLyric.getArtist()),
                GuessMusicService.duplicateKey("ytsearch:queen dont stop me now revisited",
                        queenRevisited.getTitle(), queenRevisited.getArtist()));
    }

    @Test
    public void livePerformanceCandidatesAreBlockedWithoutBlockingLiveSongTitles()
    {
        assertTrue(GuessMusicService.isBlockedAudioCandidate(
                "Panic! At The Disco - Bohemian Rhapsody (Live) [from the Death Of A Bachelor Tour]",
                "Panic! At The Disco"));
        assertTrue(GuessMusicService.isBlockedAudioCandidate(
                GuessMusicTitleMatcher.normalize("Panic! At The Disco Bohemian Rhapsody live from death of bachelor tour")));
        assertFalse(GuessMusicService.isBlockedAudioCandidate(
                GuessMusicTitleMatcher.normalize("Guns N' Roses Live And Let Die official audio")));
        assertFalse(GuessMusicService.isBlockedAudioCandidate("Ado - Live", "Ado"));
    }

    @Test
    public void acousticVersionCandidatesAreBlockedWithoutBlockingRealTitles()
    {
        assertTrue(GuessMusicService.isBlockedAudioCandidate(
                "Fall Out Boy - Immortals (Acoustic Version)", "Fall Out Boy"));
        assertTrue(GuessMusicService.isBlockedAudioCandidate(
                "Linkin Park - One More Light - Piano Version", "Linkin Park"));
        assertTrue(GuessMusicService.isBlockedAudioCandidate(
                "NewJeans - Super Shy - Acoustic", "NewJeans"));
        assertFalse(GuessMusicService.isBlockedAudioCandidate("Ado - Acoustic", "Ado"));
        assertFalse(GuessMusicService.isBlockedAudioCandidate(
                "Billy Joel - Piano Man (Official Audio)", "Billy Joel"));
    }

    @Test
    public void roundPointsUseFloorWithMinimumPoint()
    {
        assertEquals(3, GuessMusicService.calculateRoundPoints(3, 1));
        assertEquals(2, GuessMusicService.calculateRoundPoints(3, 2));
        assertEquals(1, GuessMusicService.calculateRoundPoints(3, 3));
        assertEquals(1, GuessMusicService.calculateRoundPoints(3, 4));
        assertEquals(2, GuessMusicService.calculateRoundPoints(8, 4));
    }
}
