package com.jagrosh.jmusicbot.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class KaraokeManagerTest
{
    @Test public void shortLyricsReturnedAsIs() {
        assertEquals("hello\nworld", KaraokeManager.plainDescription("hello\nworld", 100));
    }

    @Test public void longLyricsTruncateOnLineBoundaryWithEllipsis() {
        String out = KaraokeManager.plainDescription("aaaa\nbbbb\ncccc\ndddd", 12);
        assertEquals("aaaa\nbbbb…", out);
        assertTrue(out.length() <= 12);
    }

    @Test public void longLyricsWithoutBoundaryHardTruncate() {
        String out = KaraokeManager.plainDescription("abcdefghij", 5);
        assertEquals("abcd…", out);
        assertTrue(out.length() <= 5);
    }

    @Test public void nullIsEmpty() {
        assertEquals("", KaraokeManager.plainDescription(null, 10));
    }
}
