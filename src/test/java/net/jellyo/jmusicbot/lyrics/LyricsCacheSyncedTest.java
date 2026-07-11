package com.jagrosh.jmusicbot.lyrics;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LyricsCacheSyncedTest
{
    @Test public void syncedLyricsRoundTrip() throws Exception {
        LyricsCache cache = newCache();
        LyricsResult r = new LyricsResult("lrclib", "1", "lrclib:1", "https://example.test/1",
                "A", "T", "plain line one\nplain line two", Set.of("A - T"), "[00:01.00] hi\n[00:05.00] bye");
        cache.insertOrUpdate(r, Set.of("A - T"));

        Optional<LyricsCache.CachedLyrics> m = cache.findByPath("lrclib:1");
        assertTrue(m.isPresent());
        assertEquals("[00:01.00] hi\n[00:05.00] bye", m.get().syncedLyrics());
    }

    @Test public void missingSyncedLyricsIsEmptyNotNull() throws Exception {
        LyricsCache cache = newCache();
        LyricsResult r = new LyricsResult("genius", "/a-lyrics", "/a-lyrics", "https://genius.com/a-lyrics",
                "A", "T", "plain", Set.of("A - T"));
        cache.insertOrUpdate(r, Set.of());

        assertEquals("", cache.findByPath("/a-lyrics").get().syncedLyrics());
    }

    private LyricsCache newCache() throws Exception {
        Path db = Files.createTempFile("lyrics-cache-synced-test", ".db");
        db.toFile().deleteOnExit();
        LyricsCache cache = new LyricsCache(db);
        cache.init();
        return cache;
    }
}
