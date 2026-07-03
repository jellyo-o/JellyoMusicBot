package com.jagrosh.jmusicbot.lyrics;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class LyricsPreloaderTest
{
    private final List<String> warmed = new ArrayList<>();
    private LyricsPreloader newPreloader() {
        return new LyricsPreloader(Runnable::run, warmed::add, 100);
    }

    @Test public void warmsEachKeyOnce() {
        LyricsPreloader p = newPreloader();
        p.preloadKeys(Arrays.asList("NF - Time", "Adele - Hello"));
        assertEquals(Arrays.asList("NF - Time", "Adele - Hello"), warmed);
    }

    @Test public void skipsAlreadyAttemptedKeysAcrossOverlappingWindows() {
        LyricsPreloader p = newPreloader();
        p.preloadKeys(Arrays.asList("a", "b", "c"));
        p.preloadKeys(Arrays.asList("b", "c", "d")); // window advanced by one
        assertEquals(Arrays.asList("a", "b", "c", "d"), warmed); // only "d" is new
    }

    @Test public void ignoresBlankKeys() {
        LyricsPreloader p = newPreloader();
        p.preloadKeys(Arrays.asList("", "  ", "x"));
        assertEquals(Arrays.asList("x"), warmed);
    }

    @Test public void evictsOldestBeyondCapacitySoTheyCanReload() {
        LyricsPreloader p = new LyricsPreloader(Runnable::run, warmed::add, 2);
        p.preloadKeys(Arrays.asList("a", "b"));
        p.preloadKeys(Arrays.asList("c"));       // evicts "a"
        p.preloadKeys(Arrays.asList("a"));       // "a" no longer remembered -> warmed again
        assertEquals(Arrays.asList("a", "b", "c", "a"), warmed);
    }
}
