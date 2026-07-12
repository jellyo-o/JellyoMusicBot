package com.jagrosh.jmusicbot.lyrics;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.*;

public class LyricsServiceFetchTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static class FakeProvider implements DirectLyricsProvider {
        int calls = 0;
        boolean throwError = false;
        Optional<LyricsResult> next = Optional.empty();
        public Optional<LyricsResult> search(String q, boolean f) throws IOException {
            calls++;
            if(throwError) throw new IOException("boom");
            return next;
        }
        public Optional<LyricsResult> fetchByUrl(String url) { return Optional.empty(); }
    }

    private static LyricsResult result(String artist, String title) {
        return new LyricsResult("p", artist+title, "p:"+artist+title,
                "https://example/"+artist+title, artist, title, "the lyrics", Set.of());
    }

    private LyricsService service(FakeProvider primary, FakeProvider fallback) throws Exception {
        LyricsCache cache = new LyricsCache(tmp.newFile("lyrics.db").toPath());
        return new LyricsService(cache, primary, fallback);
    }

    @Test public void fallsThroughToGeniusWhenLrclibHasNoMatch() throws Exception {
        FakeProvider primary = new FakeProvider();               // LRCLIB: empty
        FakeProvider fallback = new FakeProvider();              // Genius: has it
        fallback.next = Optional.of(result("NF", "Time"));
        LyricsService svc = service(primary, fallback);

        Optional<LyricsCache.CachedLyrics> found = svc.fetchAndCache("NF - Time", true);
        assertTrue(found.isPresent());
        assertEquals("NF", found.get().artist());
        assertEquals(1, primary.calls);
        assertEquals(1, fallback.calls);
    }

    @Test public void cleanMissIsNegativeCachedAndSkipsProvidersNextTime() throws Exception {
        FakeProvider primary = new FakeProvider();   // both empty = clean miss
        FakeProvider fallback = new FakeProvider();
        LyricsService svc = service(primary, fallback);

        assertFalse(svc.fetchAndCache("Unknown Song", true).isPresent());
        assertEquals(1, primary.calls);
        assertEquals(1, fallback.calls);

        // second identical lookup must NOT hit providers again (negative cache)
        assertFalse(svc.fetchAndCache("Unknown Song", true).isPresent());
        assertEquals(1, primary.calls);
        assertEquals(1, fallback.calls);
    }

    @Test public void providerErrorIsNotNegativeCachedSoItRetries() throws Exception {
        FakeProvider primary = new FakeProvider();
        primary.throwError = true;                    // network error, not a clean miss
        FakeProvider fallback = new FakeProvider();   // empty
        LyricsService svc = service(primary, fallback);

        assertFalse(svc.fetchAndCache("Flaky Song", true).isPresent());
        assertEquals(1, fallback.calls);

        // an error must not suppress retries — providers are consulted again
        assertFalse(svc.fetchAndCache("Flaky Song", true).isPresent());
        assertEquals(2, fallback.calls);
    }

    @Test public void cacheHitSkipsProviders() throws Exception {
        FakeProvider primary = new FakeProvider();
        primary.next = Optional.of(result("Adele", "Hello"));
        FakeProvider fallback = new FakeProvider();
        LyricsService svc = service(primary, fallback);

        assertTrue(svc.fetchAndCache("Adele - Hello", true).isPresent());
        assertEquals(1, primary.calls);
        // second call served from the cache
        assertTrue(svc.fetchAndCache("Adele - Hello", true).isPresent());
        assertEquals(1, primary.calls);
        assertEquals(0, fallback.calls);
    }

    @Test public void preloadModeSkipsRateLimitedFallbackOnPrimaryMiss() throws Exception {
        FakeProvider primary = new FakeProvider();               // LRCLIB: empty
        FakeProvider fallback = new FakeProvider();              // Genius: would have it
        fallback.next = Optional.of(result("NF", "Time"));
        LyricsService svc = service(primary, fallback);

        // Preload path (allowFallbackProvider=false) consults only LRCLIB, never the
        // rate-limited Genius fallback, so background warming cannot block on the limiter.
        Optional<LyricsCache.CachedLyrics> found = svc.fetchAndCache("NF - Time", true, false);
        assertFalse(found.isPresent());
        assertEquals(1, primary.calls);
        assertEquals(0, fallback.calls);
    }

    @Test public void preloadModeStillReturnsPrimaryHitWithoutTouchingFallback() throws Exception {
        FakeProvider primary = new FakeProvider();
        primary.next = Optional.of(result("Adele", "Hello"));
        FakeProvider fallback = new FakeProvider();
        LyricsService svc = service(primary, fallback);

        Optional<LyricsCache.CachedLyrics> found = svc.fetchAndCache("Adele - Hello", true, false);
        assertTrue(found.isPresent());
        assertEquals("Adele", found.get().artist());
        assertEquals(1, primary.calls);
        assertEquals(0, fallback.calls);
    }

    @Test public void preloadMissIsNotNegativeCachedSoLaterFullFetchStillTriesFallback() throws Exception {
        FakeProvider primary = new FakeProvider();               // LRCLIB: empty
        FakeProvider fallback = new FakeProvider();              // Genius: has it
        fallback.next = Optional.of(result("NF", "Time"));
        LyricsService svc = service(primary, fallback);

        // A preload miss (LRCLIB-only) must NOT record a negative cache, because the providers
        // were not fully consulted; otherwise a later on-demand fetch would be wrongly suppressed.
        assertFalse(svc.fetchAndCache("NF - Time", true, false).isPresent());
        assertEquals(0, fallback.calls);

        // The later full fetch is still allowed to reach Genius and succeed.
        Optional<LyricsCache.CachedLyrics> found = svc.fetchAndCache("NF - Time", true);
        assertTrue(found.isPresent());
        assertEquals("NF", found.get().artist());
        assertEquals(1, fallback.calls);
    }
}
