package com.jagrosh.jmusicbot.lyrics;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.*;

public class LrclibRankingTest
{
    private static LyricsResult r(String artist, String title) {
        return new LyricsResult("lrclib", artist + title, "lrclib:" + artist + title,
                "https://lrclib.net/lyrics/1", artist, title, "la la la", Set.of());
    }

    @Test public void artistAwareQueryPrefersCorrectArtist() {
        List<LyricsResult> candidates = Arrays.asList(r("Drake", "From Time"), r("NF", "Time"));
        Optional<LyricsResult> best = LrclibLyricsProvider.selectBest("NF - Time", "NF Time", candidates);
        assertTrue(best.isPresent());
        assertEquals("NF", best.get().artist());
        assertEquals("Time", best.get().title());
    }

    @Test public void bareTitleStillReturnsExactTitleMatch() {
        List<LyricsResult> candidates = Arrays.asList(r("Drake", "From Time"), r("NF", "Time"));
        Optional<LyricsResult> best = LrclibLyricsProvider.selectBest("Time", "Time", candidates);
        assertTrue(best.isPresent());
        assertEquals("Time", best.get().title()); // exact-title (0.85) beats "From Time" fuzzy
    }

    @Test public void noCandidatesReturnsEmpty() {
        assertFalse(LrclibLyricsProvider.selectBest("x", "x", Arrays.asList()).isPresent());
    }
}
