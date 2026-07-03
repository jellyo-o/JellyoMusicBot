package com.jagrosh.jmusicbot.lyrics;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LyricsQueryTest
{
    @Test public void artistAndTitleCombine() {
        assertEquals("NF - Time", LyricsQuery.forTitleAndAuthor("Time", "NF"));
    }
    @Test public void stripsYoutubeTopicSuffix() {
        assertEquals("NF - Time", LyricsQuery.forTitleAndAuthor("Time", "NF - Topic"));
    }
    @Test public void stripsVevoAndOfficial() {
        assertEquals("NF - Time", LyricsQuery.forTitleAndAuthor("Time", "NFVEVO"));
        assertEquals("Adele - Hello", LyricsQuery.forTitleAndAuthor("Hello", "Adele Official"));
    }
    @Test public void blankAuthorFallsBackToTitle() {
        assertEquals("Time", LyricsQuery.forTitleAndAuthor("Time", ""));
        assertEquals("Time", LyricsQuery.forTitleAndAuthor("Time", null));
    }
    @Test public void authorAlreadyInTitleIsNotDuplicated() {
        assertEquals("NF - Time", LyricsQuery.forTitleAndAuthor("NF - Time", "NF"));
    }
    @Test public void blankTitleReturnsEmpty() {
        assertEquals("", LyricsQuery.forTitleAndAuthor("", "NF"));
    }
}
