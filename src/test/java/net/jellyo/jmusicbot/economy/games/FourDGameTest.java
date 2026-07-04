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
package com.jagrosh.jmusicbot.economy.games;

import com.jagrosh.jmusicbot.economy.games.FourDGame.BetType;
import com.jagrosh.jmusicbot.economy.games.FourDGame.Draw;
import com.jagrosh.jmusicbot.economy.games.FourDGame.Tier;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FourDGameTest
{
    /** Return-to-player computed exactly from the prize table and tier probabilities (n/10000). */
    private static double rtp(BetType type)
    {
        long single = FourDGame.prize(Tier.FIRST, type, 1) + FourDGame.prize(Tier.SECOND, type, 1)
                + FourDGame.prize(Tier.THIRD, type, 1);
        long starters = 10 * FourDGame.prize(Tier.STARTER, type, 1);
        long consolations = 10 * FourDGame.prize(Tier.CONSOLATION, type, 1);
        return (single + starters + consolations) / 10000.0;
    }

    @Test
    public void bigAndSmallKeepAHouseEdge()
    {
        assertEquals(0.95, rtp(BetType.BIG), 0.0001);
        assertEquals(0.93, rtp(BetType.SMALL), 0.0001);
        assertTrue(rtp(BetType.BIG) < 1.0);
        assertTrue(rtp(BetType.SMALL) < 1.0);
    }

    @Test
    public void smallDoesNotWinStarterOrConsolation()
    {
        assertEquals(0, FourDGame.prize(Tier.STARTER, BetType.SMALL, 100));
        assertEquals(0, FourDGame.prize(Tier.CONSOLATION, BetType.SMALL, 100));
        assertTrue(FourDGame.prize(Tier.STARTER, BetType.BIG, 100) > 0);
    }

    @Test
    public void drawHas23DistinctNumbersAllFoundByTier()
    {
        Random rng = new Random(3);
        for(int t = 0; t < 500; t++)
        {
            Draw draw = FourDGame.draw(rng);
            Set<Integer> all = new HashSet<>();
            all.add(draw.getFirst());
            all.add(draw.getSecond());
            all.add(draw.getThird());
            all.addAll(draw.getStarters());
            all.addAll(draw.getConsolations());
            assertEquals("23 distinct winning numbers", 23, all.size());
            assertEquals(Tier.FIRST, FourDGame.tierOf(draw.getFirst(), draw));
            assertEquals(Tier.SECOND, FourDGame.tierOf(draw.getSecond(), draw));
            assertEquals(Tier.THIRD, FourDGame.tierOf(draw.getThird(), draw));
            assertEquals(Tier.STARTER, FourDGame.tierOf(draw.getStarters().get(0), draw));
            assertEquals(Tier.CONSOLATION, FourDGame.tierOf(draw.getConsolations().get(0), draw));
        }
    }

    @Test
    public void systemPermutationCounts()
    {
        assertEquals(24, FourDGame.permutations(new int[]{1, 2, 3, 4}).size());
        assertEquals(12, FourDGame.permutations(new int[]{1, 1, 2, 3}).size());
        assertEquals(6, FourDGame.permutations(new int[]{1, 1, 2, 2}).size());
        assertEquals(4, FourDGame.permutations(new int[]{1, 1, 1, 2}).size());
        assertEquals(1, FourDGame.permutations(new int[]{1, 1, 1, 1}).size());
    }

    @Test
    public void rollProducesTenNumbers()
    {
        Set<Integer> numbers = FourDGame.roll(new int[]{1, 2, 3, 4}, 0); // roll the first digit
        assertEquals(10, numbers.size());
        assertTrue(numbers.contains(1234));
        assertTrue(numbers.contains(9234));
    }
}
