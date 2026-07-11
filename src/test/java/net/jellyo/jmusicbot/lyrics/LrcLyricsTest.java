package com.jagrosh.jmusicbot.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LrcLyricsTest
{
    private static final String BASIC =
            "[00:12.00] line one\n" +
            "[00:15.50] line two\n" +
            "[01:05.30] line three\n";

    @Test public void parsesTimestampsAndText() {
        LrcLyrics lrc = LrcLyrics.parse(BASIC);
        assertFalse(lrc.isEmpty());
        assertEquals(3, lrc.size());
        assertEquals("line one", lrc.line(0));
        assertEquals("line two", lrc.line(1));
        assertEquals("line three", lrc.line(2));
        assertEquals(12_000L, lrc.timeMs(0));
        assertEquals(15_500L, lrc.timeMs(1));
        assertEquals(65_300L, lrc.timeMs(2));
    }

    @Test public void currentLineIsLastTimestampAtOrBeforePosition() {
        LrcLyrics lrc = LrcLyrics.parse(BASIC);
        assertEquals(0, lrc.lineIndexAt(12_000L)); // exactly on the first line
        assertEquals(0, lrc.lineIndexAt(14_000L)); // between first and second
        assertEquals(1, lrc.lineIndexAt(15_500L));
        assertEquals(1, lrc.lineIndexAt(60_000L)); // still second, before third
        assertEquals(2, lrc.lineIndexAt(65_300L));
        assertEquals(2, lrc.lineIndexAt(999_000L)); // past the end stays on last line
    }

    @Test public void beforeFirstTimestampReturnsMinusOne() {
        LrcLyrics lrc = LrcLyrics.parse(BASIC);
        assertEquals(-1, lrc.lineIndexAt(0L));
        assertEquals(-1, lrc.lineIndexAt(11_999L));
    }

    @Test public void instrumentalGapLineIsKeptAsEmpty() {
        LrcLyrics lrc = LrcLyrics.parse(
                "[00:01.00] sing\n" +
                "[00:05.00]\n" +           // instrumental break, no text
                "[00:09.00] sing again\n");
        assertEquals(3, lrc.size());
        assertEquals("", lrc.line(1));
        assertEquals(5_000L, lrc.timeMs(1));
    }

    @Test public void metadataTagsAreIgnored() {
        LrcLyrics lrc = LrcLyrics.parse(
                "[ar:Some Artist]\n" +
                "[ti:Some Title]\n" +
                "[length:03:21]\n" +
                "[00:02.00] real line\n");
        assertEquals(1, lrc.size());
        assertEquals("real line", lrc.line(0));
        assertEquals(2_000L, lrc.timeMs(0));
    }

    @Test public void multipleTimestampsOnOneLineExpandAndSort() {
        LrcLyrics lrc = LrcLyrics.parse("[00:10.00][00:40.00] chorus\n[00:25.00] verse\n");
        assertEquals(3, lrc.size());
        assertEquals(10_000L, lrc.timeMs(0));
        assertEquals("chorus", lrc.line(0));
        assertEquals(25_000L, lrc.timeMs(1));
        assertEquals("verse", lrc.line(1));
        assertEquals(40_000L, lrc.timeMs(2));
        assertEquals("chorus", lrc.line(2));
    }

    @Test public void millisecondPrecisionFractionsSupported() {
        LrcLyrics two = LrcLyrics.parse("[00:12.50] x\n");
        assertEquals(12_500L, two.timeMs(0));
        LrcLyrics three = LrcLyrics.parse("[00:12.500] x\n");
        assertEquals(12_500L, three.timeMs(0));
        LrcLyrics none = LrcLyrics.parse("[00:12] x\n");
        assertEquals(12_000L, none.timeMs(0));
    }

    @Test public void blankOrPlainInputIsEmpty() {
        assertTrue(LrcLyrics.parse(null).isEmpty());
        assertTrue(LrcLyrics.parse("").isEmpty());
        assertTrue(LrcLyrics.parse("just plain lyrics\nwith no timestamps\n").isEmpty());
        assertEquals(0, LrcLyrics.parse("").size());
    }
}
