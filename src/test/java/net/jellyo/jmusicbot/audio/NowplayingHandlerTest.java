/*
 * Copyright 2026 Jellyo.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.audio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NowplayingHandlerTest
{
    @Test
    public void panelUpdateDelayUsesDebounceWhenIdle()
    {
        assertEquals(250L, NowplayingHandler.computePanelUpdateDelayMillis(
                10_000L, 0L, 2_000L, 250L));
    }

    @Test
    public void panelUpdateDelayHonorsInteractiveIntervalAfterRecentEdit()
    {
        assertEquals(500L, NowplayingHandler.computePanelUpdateDelayMillis(
                11_500L, 10_000L, 2_000L, 250L));
    }

    @Test
    public void panelUpdateDelayKeepsStandardUpdatesOnLongerInterval()
    {
        assertEquals(12_000L, NowplayingHandler.computePanelUpdateDelayMillis(
                13_000L, 10_000L, 15_000L, 0L));
    }

    @Test
    public void panelDistanceInspectionOnlyRunsWhenLatestCanBeNewer()
    {
        assertFalse(NowplayingHandler.shouldInspectPanelDistance(true, 100L, 100L));
        assertFalse(NowplayingHandler.shouldInspectPanelDistance(true, 99L, 100L));
        assertTrue(NowplayingHandler.shouldInspectPanelDistance(true, 101L, 100L));
        assertTrue(NowplayingHandler.shouldInspectPanelDistance(true, 0L, 100L));
    }

    @Test
    public void panelDistanceInspectionSkipsNonTrackUpdates()
    {
        assertFalse(NowplayingHandler.shouldInspectPanelDistance(false, 101L, 100L));
        assertFalse(NowplayingHandler.shouldInspectPanelDistance(false, 0L, 100L));
    }

    @Test
    public void panelMovesWhenThresholdIsReached()
    {
        assertFalse(NowplayingHandler.shouldMovePanel(4, 5));
        assertTrue(NowplayingHandler.shouldMovePanel(5, 5));
        assertTrue(NowplayingHandler.shouldMovePanel(6, 5));
    }

    @Test
    public void nowplayingOnlySearchesOtherVisiblePanelsWhenCurrentChannelHasNoPanel()
    {
        assertFalse(NowplayingHandler.shouldSearchVisiblePanel(true, true, true));
        assertTrue(NowplayingHandler.shouldSearchVisiblePanel(true, true, false));
        assertFalse(NowplayingHandler.shouldSearchVisiblePanel(true, false, false));
        assertFalse(NowplayingHandler.shouldSearchVisiblePanel(false, true, false));
    }

    @Test
    public void panelUpdateDelayBypassesCooldownForImmediateUpdates()
    {
        assertEquals(0L, NowplayingHandler.computePanelUpdateDelayMillis(
                11_500L, 10_000L, 10_000L, 250L, true));
    }

    @Test
    public void outputChannelPrefersConfiguredThenTrackThenRemembered()
    {
        // A configured settc channel always wins.
        assertEquals(5L, NowplayingHandler.resolveOutputChannelId(5L, 7L, 9L));
        assertEquals(5L, NowplayingHandler.resolveOutputChannelId(5L, 0L, 0L));
        // No configured channel: use the track's own channel (manual/playlist plays carry this).
        assertEquals(7L, NowplayingHandler.resolveOutputChannelId(0L, 7L, 9L));
        assertEquals(7L, NowplayingHandler.resolveOutputChannelId(0L, 7L, 0L));
    }

    @Test
    public void outputChannelFallsBackToRememberedWhenTrackHasNone()
    {
        // Autoplay tracks carry channel 0; with no settc, fall back to the last resolved channel
        // so their lyrics still land in the channel the manual songs used.
        assertEquals(9L, NowplayingHandler.resolveOutputChannelId(0L, 0L, 9L));
    }

    @Test
    public void outputChannelIsNoneWhenNothingIsKnown()
    {
        assertEquals(0L, NowplayingHandler.resolveOutputChannelId(0L, 0L, 0L));
    }
}
