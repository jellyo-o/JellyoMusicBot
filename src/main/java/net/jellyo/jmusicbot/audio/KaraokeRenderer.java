package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.lyrics.LrcLyrics;

/**
 * Pure rendering of the karaoke "rolling window": a few lines of context around the
 * line that is currently being sung. The current line is emphasised; instrumental gaps
 * and the pre-song intro show a musical note so the message is never blank.
 */
public final class KaraokeRenderer
{
    private static final String NOTE = "♪"; // ♪
    private static final String MARKER = "▶"; // ▶

    private KaraokeRenderer() {}

    /**
     * Builds the window text for {@code currentIndex}, showing up to {@code before} lines
     * above and {@code after} lines below. A {@code currentIndex} of -1 means the song has
     * not reached its first lyric yet (intro).
     */
    public static String window(LrcLyrics lrc, int currentIndex, int before, int after)
    {
        StringBuilder sb = new StringBuilder();
        if(currentIndex < 0)
        {
            // Intro: no line to highlight yet. Show a note, then the first upcoming lines.
            sb.append(NOTE);
            for(int i = 0; i < after && i < lrc.size(); i++)
                sb.append('\n').append(display(lrc.line(i)));
            return sb.toString();
        }

        for(int i = Math.max(0, currentIndex - before); i < currentIndex; i++)
            sb.append(display(lrc.line(i))).append('\n');
        sb.append(MARKER).append(" **").append(display(lrc.line(currentIndex))).append("**");
        for(int i = currentIndex + 1; i <= currentIndex + after && i < lrc.size(); i++)
            sb.append('\n').append(display(lrc.line(i)));
        return sb.toString();
    }

    private static String display(String text)
    {
        return text == null || text.isBlank() ? NOTE : text;
    }
}
