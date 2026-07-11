package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.lyrics.LrcLyrics;
import com.jagrosh.jmusicbot.lyrics.LyricsCache;
import com.jagrosh.jmusicbot.lyrics.LyricsResult;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end check of the karaoke data path with real components (no mocks): synced lyrics
 * are cached, read back, parsed, and rendered exactly as they would be at runtime.
 */
public class KaraokePipelineTest
{
    private static final String SYNCED =
            "[00:00.00] \n" +                 // intro gap
            "[00:04.00] first line\n" +
            "[00:08.50] second line\n" +
            "[00:12.00] third line\n";

    @Test public void syncedLyricsFlowFromCacheToRenderedWindow() throws Exception {
        Path db = Files.createTempFile("karaoke-pipeline-test", ".db");
        db.toFile().deleteOnExit();
        LyricsCache cache = new LyricsCache(db);
        cache.init();

        LyricsResult result = new LyricsResult("lrclib", "42", "lrclib:42", "https://lrclib.net/lyrics/42",
                "Some Artist", "Some Song", "first line\nsecond line\nthird line", Set.of("Some Artist - Some Song"),
                SYNCED);
        cache.insertOrUpdate(result, Set.of("Some Artist - Some Song"));

        LyricsCache.CachedLyrics cached = cache.findByPath("lrclib:42").orElseThrow();
        assertTrue(cached.hasSyncedLyrics());

        LrcLyrics lrc = LrcLyrics.parse(cached.syncedLyrics());
        assertEquals(4, lrc.size());

        // Playing at 9s: the second line should be current, with context around it.
        int idx = lrc.lineIndexAt(9_000L);
        assertEquals(2, idx);
        assertEquals("first line\n▶ **second line**\nthird line", KaraokeRenderer.window(lrc, idx, 1, 2));

        // During the intro gap before the first sung line.
        assertEquals(0, lrc.lineIndexAt(1_000L)); // the gap entry itself is index 0
        assertEquals("▶ **♪**\nfirst line\nsecond line", KaraokeRenderer.window(lrc, 0, 1, 2));
    }
}
