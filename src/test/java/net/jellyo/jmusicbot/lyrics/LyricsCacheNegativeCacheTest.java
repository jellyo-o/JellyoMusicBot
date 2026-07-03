package com.jagrosh.jmusicbot.lyrics;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LyricsCacheNegativeCacheTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private LyricsCache newCache() throws Exception {
        LyricsCache cache = new LyricsCache(tmp.newFile("lyrics.db").toPath());
        cache.init();
        return cache;
    }

    @Test public void recordedMissIsRecentWithinTtl() throws Exception {
        LyricsCache cache = newCache();
        assertFalse(cache.isRecentMiss("nf - time", 60_000L)); // unknown
        cache.recordMiss("nf - time");
        assertTrue(cache.isRecentMiss("nf - time", 60_000L));
    }

    @Test public void missIsNotRecentOnceTtlElapsed() throws Exception {
        LyricsCache cache = newCache();
        cache.recordMiss("nf - time");
        // ttl of 0 means anything is already expired (age >= 0 is not < 0)
        assertFalse(cache.isRecentMiss("nf - time", 0L));
    }

    @Test public void clearMissRemovesIt() throws Exception {
        LyricsCache cache = newCache();
        cache.recordMiss("nf - time");
        cache.clearMiss("nf - time");
        assertFalse(cache.isRecentMiss("nf - time", 60_000L));
    }

    @Test public void blankQueryIsNeverAMiss() throws Exception {
        LyricsCache cache = newCache();
        cache.recordMiss("   ");
        cache.recordMiss(null);
        assertFalse(cache.isRecentMiss("", 60_000L));
        assertFalse(cache.isRecentMiss(null, 60_000L));
    }
}
