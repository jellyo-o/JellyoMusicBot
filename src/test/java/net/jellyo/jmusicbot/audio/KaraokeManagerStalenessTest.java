package com.jagrosh.jmusicbot.audio;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The auto-show lyrics fetch is asynchronous, so the playing song can change before it returns.
 * {@link KaraokeManager#isStaleAutoRender} decides whether a completed fetch is still worth
 * rendering: a manual {@code /karaoke} always renders, but an auto render is dropped when the
 * song has moved on so it never overwrites the current song's message with stale lyrics.
 */
public class KaraokeManagerStalenessTest
{
    @Test public void manualAlwaysRendersEvenIfSongMovedOn() {
        assertFalse(KaraokeManager.isStaleAutoRender(true, "track-a", "track-b"));
    }

    @Test public void autoRendersWhenStillTheSameSong() {
        assertFalse(KaraokeManager.isStaleAutoRender(false, "track-a", "track-a"));
    }

    @Test public void autoIsStaleWhenSongChangedDuringFetch() {
        assertTrue(KaraokeManager.isStaleAutoRender(false, "track-a", "track-b"));
    }

    @Test public void autoRendersWhenCurrentSongIsUnknown() {
        assertFalse(KaraokeManager.isStaleAutoRender(false, "track-a", null));
    }

    @Test public void autoRendersWhenFetchedIdentifierIsUnknown() {
        assertFalse(KaraokeManager.isStaleAutoRender(false, null, "track-b"));
    }
}
