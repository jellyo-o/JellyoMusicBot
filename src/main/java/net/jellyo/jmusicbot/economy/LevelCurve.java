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

/**
 * Pure XP &harr; level math. XP is purely a measure of how much a user engages
 * with the bot; the curve is the familiar Mee6-style quadratic so that early
 * levels come quickly and later ones take progressively more activity.
 *
 * <p>The XP required to advance from level {@code L} to {@code L+1} is
 * {@code 5*L^2 + 50*L + 100}.
 */
public final class LevelCurve
{
    private static final int MAX_LEVEL = 1_000_000;

    private LevelCurve() {}

    /**
     * @param level the current level (clamped to &ge; 0)
     * @return XP needed to advance from {@code level} to {@code level + 1}
     */
    public static long xpForLevelUp(int level)
    {
        long l = Math.max(0, level);
        return 5L * l * l + 50L * l + 100L;
    }

    /**
     * @param level a level (level 0 needs 0 XP)
     * @return total cumulative XP needed to reach exactly {@code level}
     */
    public static long totalXpForLevel(int level)
    {
        long total = 0;
        for(int l = 0; l < level; l++)
            total += xpForLevelUp(l);
        return total;
    }

    /**
     * @param xp total accumulated XP
     * @return the highest level fully attained with that XP
     */
    public static int levelForXp(long xp)
    {
        if(xp <= 0)
            return 0;
        int level = 0;
        long remaining = xp;
        long needed = xpForLevelUp(level);
        while(remaining >= needed && level < MAX_LEVEL)
        {
            remaining -= needed;
            level++;
            needed = xpForLevelUp(level);
        }
        return level;
    }

    /**
     * @param xp total accumulated XP
     * @return XP earned into the current (incomplete) level
     */
    public static long xpIntoCurrentLevel(long xp)
    {
        if(xp <= 0)
            return 0;
        return xp - totalXpForLevel(levelForXp(xp));
    }

    /**
     * @param xp total accumulated XP
     * @return XP required to span the current level (i.e. to reach the next one)
     */
    public static long xpToNextLevel(long xp)
    {
        return xpForLevelUp(levelForXp(Math.max(0, xp)));
    }
}
