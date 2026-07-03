package com.jagrosh.jmusicbot.lyrics;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.*;

public class LyricsServicePreloadTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static class CountingPrimary implements LyricsProvider {
        int calls = 0;
        Optional<LyricsResult> next = Optional.empty();
        public Optional<LyricsResult> search(String q, boolean f) { calls++; return next; }
    }
    static class ThrowingFallback implements DirectLyricsProvider {
        public Optional<LyricsResult> search(String q, boolean f) { throw new AssertionError("Genius must not be used during preload"); }
        public Optional<LyricsResult> fetchByUrl(String url) { throw new AssertionError("Genius must not be used during preload"); }
    }

    private LyricsService service(CountingPrimary primary) throws Exception {
        LyricsCache cache = new LyricsCache(tmp.newFile("lyrics.db").toPath());
        return new LyricsService(cache, primary, new ThrowingFallback());
    }

    @Test public void preloadUsesPrimaryAndCaches() throws Exception {
        CountingPrimary primary = new CountingPrimary();
        primary.next = Optional.of(new LyricsResult("lrclib", "1", "lrclib:1",
                "https://lrclib.net/lyrics/1", "NF", "Time", "the lyrics", Set.of()));
        LyricsService svc = service(primary);

        Optional<LyricsCache.CachedLyrics> first = svc.preloadPrimary("NF - Time");
        assertTrue(first.isPresent());
        assertEquals(1, primary.calls);

        // second identical call is served from cache — no additional provider hit
        Optional<LyricsCache.CachedLyrics> second = svc.preloadPrimary("NF - Time");
        assertTrue(second.isPresent());
        assertEquals(1, primary.calls);
    }

    @Test public void preloadNeverInvokesFallbackOnMiss() throws Exception {
        CountingPrimary primary = new CountingPrimary(); // returns empty
        LyricsService svc = service(primary);
        Optional<LyricsCache.CachedLyrics> result = svc.preloadPrimary("Unknown Song");
        assertFalse(result.isPresent());
        assertEquals(1, primary.calls); // and ThrowingFallback was never called (no AssertionError)
    }
}
