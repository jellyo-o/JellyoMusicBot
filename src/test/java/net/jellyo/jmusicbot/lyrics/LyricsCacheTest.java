package com.jagrosh.jmusicbot.lyrics;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricsCacheTest
{
    @Test
    public void youtubeStyleTitleFindsExistingCachedSong() throws Exception
    {
        LyricsCache cache = newCache();
        cache.insertOrUpdate(result("lrclib", "1", "lrclib:1", "Dua Lipa", "Levitating"), Set.of("Dua Lipa - Levitating"));

        Optional<LyricsCache.CachedLyrics> match = cache.findBestMatch("Dua Lipa - Levitating Featuring DaBaby (Official Music Video)");

        assertTrue(match.isPresent());
        assertEquals("lrclib:1", match.get().path());
    }

    @Test
    public void titleOnlyLookupDoesNotPickAmbiguousArtistCollision() throws Exception
    {
        LyricsCache cache = newCache();
        cache.insertOrUpdate(result("lrclib", "1", "lrclib:1", "Artist One", "Hello"), Set.of("Artist One - Hello"));
        cache.insertOrUpdate(result("lrclib", "2", "lrclib:2", "Artist Two", "Hello"), Set.of("Artist Two - Hello"));

        assertFalse(cache.findBestMatch("Hello").isPresent());

        Optional<LyricsCache.CachedLyrics> match = cache.findBestMatch("Artist Two - Hello");
        assertTrue(match.isPresent());
        assertEquals("lrclib:2", match.get().path());
    }

    @Test
    public void correctionReplacesOnlyTheSpecifiedQueryTarget() throws Exception
    {
        LyricsCache cache = newCache();
        cache.insertOrUpdate(result("lrclib", "1", "lrclib:1", "Wrong Artist", "Levitating"), Set.of("Dua Lipa - Levitating"));
        LyricsResult correction = result("genius", "/Dua-lipa-levitating-lyrics", "/Dua-lipa-levitating-lyrics", "Dua Lipa", "Levitating");

        Optional<LyricsCache.CachedLyrics> updated = cache.replaceForQuery("Dua Lipa - Levitating", correction, Set.of("Dua Lipa - Levitating"));

        assertTrue(updated.isPresent());
        assertEquals("genius", updated.get().provider());
        assertEquals("/Dua-lipa-levitating-lyrics", updated.get().path());
        assertFalse(cache.findByPath("lrclib:1").isPresent());
        assertEquals("/Dua-lipa-levitating-lyrics", cache.findBestMatch("Dua Lipa - Levitating").get().path());
    }

    private LyricsCache newCache() throws Exception
    {
        Path db = Files.createTempFile("lyrics-cache-test", ".db");
        db.toFile().deleteOnExit();
        LyricsCache cache = new LyricsCache(db);
        cache.init();
        return cache;
    }

    private LyricsResult result(String provider, String sourceId, String sourceKey, String artist, String title)
    {
        return new LyricsResult(provider, sourceId, sourceKey, "https://example.test/" + sourceId,
                artist, title, "line one\nline two", Set.of(artist + " - " + title));
    }
}
