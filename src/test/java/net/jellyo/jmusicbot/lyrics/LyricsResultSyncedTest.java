package com.jagrosh.jmusicbot.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LyricsResultSyncedTest
{
    @Test public void syncedLyricsDefaultsToEmptyWithLegacyConstructor() {
        LyricsResult r = new LyricsResult("p", "id", "key", "url", "artist", "title", "words", Set.of());
        assertEquals("", r.syncedLyrics());
        assertFalse(r.hasSyncedLyrics());
    }

    @Test public void syncedLyricsStoredWhenProvided() {
        LyricsResult r = new LyricsResult("p", "id", "key", "url", "artist", "title", "words", Set.of(),
                "[00:01.00] hi");
        assertEquals("[00:01.00] hi", r.syncedLyrics());
        assertTrue(r.hasSyncedLyrics());
    }

    @Test public void lrclibProviderCapturesSyncedLyrics() throws Exception {
        JsonNode node = new ObjectMapper().readTree(
                "{\"id\":123,\"artistName\":\"A\",\"trackName\":\"T\","
                + "\"plainLyrics\":\"la la\",\"syncedLyrics\":\"[00:01.00] la\"}");
        LyricsResult r = LrclibLyricsProvider.toResult(node);
        assertNotNull(r);
        assertEquals("[00:01.00] la", r.syncedLyrics());
        assertTrue(r.hasSyncedLyrics());
    }

    @Test public void lrclibProviderWithoutSyncedLyricsHasNone() throws Exception {
        JsonNode node = new ObjectMapper().readTree(
                "{\"id\":123,\"artistName\":\"A\",\"trackName\":\"T\",\"plainLyrics\":\"la la\"}");
        LyricsResult r = LrclibLyricsProvider.toResult(node);
        assertNotNull(r);
        assertFalse(r.hasSyncedLyrics());
    }

    @Test public void lrclibInstrumentalReturnsNull() throws Exception {
        JsonNode node = new ObjectMapper().readTree(
                "{\"id\":123,\"artistName\":\"A\",\"trackName\":\"T\",\"instrumental\":true,"
                + "\"plainLyrics\":\"\"}");
        assertNull(LrclibLyricsProvider.toResult(node));
    }
}
