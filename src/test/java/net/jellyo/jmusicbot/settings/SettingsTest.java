package com.jagrosh.jmusicbot.settings;

import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsTest
{
    private Settings settings(boolean preload, boolean show) {
        return new Settings(null, 0L, 0L, 0L, 100, RepeatMode.OFF, AutoplayMode.OFF, null, -1, QueueType.FAIR, preload, show);
    }

    @Test public void defaultsPreloadOnAutoShowOff() {
        Settings s = settings(true, false);
        assertTrue(s.isAutoPreloadLyrics());
        assertFalse(s.isAutoShowLyrics());
    }

    @Test public void constructorCarriesFlags() {
        Settings s = settings(false, true);
        assertFalse(s.isAutoPreloadLyrics());
        assertTrue(s.isAutoShowLyrics());
    }
}
