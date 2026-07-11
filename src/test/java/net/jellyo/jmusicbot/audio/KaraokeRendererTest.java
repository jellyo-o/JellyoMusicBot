package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.lyrics.LrcLyrics;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class KaraokeRendererTest
{
    private static LrcLyrics five()
    {
        return LrcLyrics.parse(
                "[00:00.00] l0\n[00:01.00] l1\n[00:02.00] l2\n[00:03.00] l3\n[00:04.00] l4\n");
    }

    @Test public void middleLineShowsPreviousCurrentBoldAndUpcoming() {
        String out = KaraokeRenderer.window(five(), 2, 1, 2);
        assertEquals("l1\n▶ **l2**\nl3\nl4", out);
    }

    @Test public void atStartHasNoPreviousLine() {
        String out = KaraokeRenderer.window(five(), 0, 1, 2);
        assertEquals("▶ **l0**\nl1\nl2", out);
    }

    @Test public void nearEndHasNoUpcomingLines() {
        String out = KaraokeRenderer.window(five(), 4, 1, 2);
        assertEquals("l3\n▶ **l4**", out);
    }

    @Test public void introBeforeFirstLineShowsNoteAndUpcoming() {
        String out = KaraokeRenderer.window(five(), -1, 1, 2);
        assertEquals("♪\nl0\nl1", out);
    }

    @Test public void instrumentalGapCurrentLineRendersAsNote() {
        LrcLyrics lrc = LrcLyrics.parse("[00:00.00] a\n[00:02.00]\n[00:04.00] c\n");
        String out = KaraokeRenderer.window(lrc, 1, 1, 1);
        assertEquals("a\n▶ **♪**\nc", out);
    }
}
