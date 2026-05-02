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
    public void panelUpdateDelayKeepsRoutineUpdatesOnLongerInterval()
    {
        assertEquals(12_000L, NowplayingHandler.computePanelUpdateDelayMillis(
                13_000L, 10_000L, 15_000L, 0L));
    }

    @Test
    public void panelDistanceInspectionOnlyRunsWhenLatestCanBeNewer()
    {
        assertFalse(NowplayingHandler.shouldInspectPanelDistance(100L, 100L));
        assertFalse(NowplayingHandler.shouldInspectPanelDistance(99L, 100L));
        assertTrue(NowplayingHandler.shouldInspectPanelDistance(101L, 100L));
        assertTrue(NowplayingHandler.shouldInspectPanelDistance(0L, 100L));
    }

    @Test
    public void panelMovesWhenThresholdIsReached()
    {
        assertFalse(NowplayingHandler.shouldMovePanel(4, 5));
        assertTrue(NowplayingHandler.shouldMovePanel(5, 5));
        assertTrue(NowplayingHandler.shouldMovePanel(6, 5));
    }
}
