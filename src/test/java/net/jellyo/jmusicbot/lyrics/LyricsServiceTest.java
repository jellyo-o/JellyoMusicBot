package com.jagrosh.jmusicbot.lyrics;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricsServiceTest
{
    @Test
    public void cacheHitSkipsProviders() throws Exception
    {
        LyricsCache cache = newCache();
        cache.insertOrUpdate(result("lrclib", "1", "lrclib:1", "Queen", "Bohemian Rhapsody"), Set.of("Queen - Bohemian Rhapsody"));
        FakeProvider primary = new FakeProvider(result("lrclib", "2", "lrclib:2", "Other", "Song"));
        FakeDirectProvider fallback = new FakeDirectProvider(result("genius", "/Other-song-lyrics", "/Other-song-lyrics", "Other", "Song"));
        LyricsService service = new LyricsService(cache, primary, fallback);

        Optional<LyricsCache.CachedLyrics> result = service.fetchAndCache("Queen - Bohemian Rhapsody (Official Video)", true);

        assertTrue(result.isPresent());
        assertEquals("lrclib:1", result.get().path());
        assertEquals(0, primary.searchCalls);
        assertEquals(0, fallback.searchCalls);
    }

    @Test
    public void lrclibHitSkipsGeniusFallback() throws Exception
    {
        FakeProvider primary = new FakeProvider(result("lrclib", "1", "lrclib:1", "Adele", "Hello"));
        FakeDirectProvider fallback = new FakeDirectProvider(result("genius", "/Adele-hello-lyrics", "/Adele-hello-lyrics", "Adele", "Hello"));
        LyricsService service = newService(primary, fallback);

        Optional<LyricsCache.CachedLyrics> result = service.fetchAndCache("Adele - Hello", true);

        assertTrue(result.isPresent());
        assertEquals("lrclib", result.get().provider());
        assertEquals(1, primary.searchCalls);
        assertEquals(0, fallback.searchCalls);
    }

    @Test
    public void primaryFailureFallsBackToGenius() throws Exception
    {
        FakeProvider primary = new FakeProvider(null);
        primary.throwOnSearch = true;
        FakeDirectProvider fallback = new FakeDirectProvider(result("genius", "/Adele-hello-lyrics", "/Adele-hello-lyrics", "Adele", "Hello"));
        LyricsService service = newService(primary, fallback);

        Optional<LyricsCache.CachedLyrics> result = service.fetchAndCache("Adele - Hello", true);

        assertTrue(result.isPresent());
        assertEquals("genius", result.get().provider());
        assertEquals(1, primary.searchCalls);
        assertEquals(1, fallback.searchCalls);
    }

    @Test
    public void bothProvidersMissReturnsEmpty() throws Exception
    {
        LyricsService service = newService(new FakeProvider(null), new FakeDirectProvider(null));

        assertFalse(service.fetchAndCache("Definitely Missing Song", true).isPresent());
    }

    @Test
    public void correctionIsStoredForSpecifiedQuery() throws Exception
    {
        FakeDirectProvider fallback = new FakeDirectProvider(result("genius", "/Dua-lipa-levitating-lyrics", "/Dua-lipa-levitating-lyrics", "Dua Lipa", "Levitating"));
        LyricsService service = newService(new FakeProvider(null), fallback);

        Optional<LyricsCache.CachedLyrics> corrected = service.replaceForQueryWithGeniusUrl("https://genius.com/Dua-lipa-levitating-lyrics", "Dua Lipa - Levitating");
        Optional<LyricsCache.CachedLyrics> cached = service.fetchAndCache("Dua Lipa - Levitating (Official Music Video)", true);

        assertTrue(corrected.isPresent());
        assertTrue(cached.isPresent());
        assertEquals("/Dua-lipa-levitating-lyrics", cached.get().path());
    }

    private LyricsService newService(FakeProvider primary, FakeDirectProvider fallback) throws Exception
    {
        return new LyricsService(newCache(), primary, fallback);
    }

    private LyricsCache newCache() throws Exception
    {
        Path db = Files.createTempFile("lyrics-service-test", ".db");
        db.toFile().deleteOnExit();
        LyricsCache cache = new LyricsCache(db);
        cache.init();
        return cache;
    }

    private LyricsResult result(String provider, String sourceId, String sourceKey, String artist, String title)
    {
        return new LyricsResult(provider, sourceId, sourceKey, "https://example.test/" + sourceId,
                artist, title, "lyrics", Set.of(artist + " - " + title));
    }

    private static class FakeProvider implements LyricsProvider
    {
        protected final LyricsResult result;
        protected int searchCalls;
        private boolean throwOnSearch;

        private FakeProvider(LyricsResult result)
        {
            this.result = result;
        }

        @Override
        public Optional<LyricsResult> search(String query, boolean allowDifferentArtistFallback) throws IOException
        {
            searchCalls++;
            if(throwOnSearch)
                throw new IOException("boom");
            return Optional.ofNullable(result);
        }
    }

    private static class FakeDirectProvider extends FakeProvider implements DirectLyricsProvider
    {
        private int fetchCalls;

        private FakeDirectProvider(LyricsResult result)
        {
            super(result);
        }

        @Override
        public Optional<LyricsResult> fetchByUrl(String url)
        {
            fetchCalls++;
            return Optional.ofNullable(super.result);
        }
    }
}
