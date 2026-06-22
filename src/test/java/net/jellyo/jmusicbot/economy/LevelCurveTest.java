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
package com.jagrosh.jmusicbot.economy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LevelCurveTest
{
    @Test
    public void xpForLevelUpFollowsQuadratic()
    {
        assertEquals(100L, LevelCurve.xpForLevelUp(0));
        assertEquals(155L, LevelCurve.xpForLevelUp(1));
        assertEquals(220L, LevelCurve.xpForLevelUp(2));
    }

    @Test
    public void totalXpAccumulates()
    {
        assertEquals(0L, LevelCurve.totalXpForLevel(0));
        assertEquals(100L, LevelCurve.totalXpForLevel(1));
        assertEquals(255L, LevelCurve.totalXpForLevel(2));
        assertEquals(475L, LevelCurve.totalXpForLevel(3));
    }

    @Test
    public void levelForXpRespectsBoundaries()
    {
        assertEquals(0, LevelCurve.levelForXp(0));
        assertEquals(0, LevelCurve.levelForXp(99));
        assertEquals(1, LevelCurve.levelForXp(100));
        assertEquals(1, LevelCurve.levelForXp(254));
        assertEquals(2, LevelCurve.levelForXp(255));
    }

    @Test
    public void progressWithinLevel()
    {
        assertEquals(50L, LevelCurve.xpIntoCurrentLevel(150));
        assertEquals(155L, LevelCurve.xpToNextLevel(150));
        assertEquals(0L, LevelCurve.xpIntoCurrentLevel(100));
    }

    @Test
    public void negativeXpIsTreatedAsZero()
    {
        assertEquals(0, LevelCurve.levelForXp(-50));
        assertEquals(0L, LevelCurve.xpIntoCurrentLevel(-50));
    }
}
