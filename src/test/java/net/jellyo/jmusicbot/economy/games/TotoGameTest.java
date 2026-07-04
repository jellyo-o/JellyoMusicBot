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

import com.jagrosh.jmusicbot.economy.games.TotoGame.Draw;
import com.jagrosh.jmusicbot.economy.games.TotoGame.Group;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TotoGameTest
{
    private static Set<Integer> set(int... values)
    {
        Set<Integer> s = new LinkedHashSet<>();
        for(int v : values)
            s.add(v);
        return s;
    }

    @Test
    public void keepsAHouseEdgeFromTheExactPrizeTable()
    {
        // Group hit counts for an ordinary entry over C(49,6) = 13,983,816 draws.
        long denom = 13_983_816L;
        long[] numerators = {1, 6, 252, 630, 12_915, 17_220, 229_600};
        Group[] groups = {Group.G1, Group.G2, Group.G3, Group.G4, Group.G5, Group.G6, Group.G7};
        double expectedReturn = 0;
        for(int i = 0; i < groups.length; i++)
            expectedReturn += (numerators[i] / (double) denom) * TotoGame.multiplier(groups[i]);
        assertTrue("toto RTP was " + expectedReturn, expectedReturn > 0.7 && expectedReturn < 1.0);
    }

    @Test
    public void groupEvaluationMatchesSingaporeDefinitions()
    {
        Draw draw = new Draw(set(1, 2, 3, 4, 5, 6), 7); // winning 1-6, additional 7
        assertEquals(Group.G1, TotoGame.groupOf(set(1, 2, 3, 4, 5, 6), draw));
        assertEquals(Group.G2, TotoGame.groupOf(set(1, 2, 3, 4, 5, 7), draw)); // 5 + additional
        assertEquals(Group.G3, TotoGame.groupOf(set(1, 2, 3, 4, 5, 8), draw)); // 5
        assertEquals(Group.G4, TotoGame.groupOf(set(1, 2, 3, 4, 7, 8), draw)); // 4 + additional
        assertEquals(Group.G5, TotoGame.groupOf(set(1, 2, 3, 4, 8, 9), draw)); // 4
        assertEquals(Group.G6, TotoGame.groupOf(set(1, 2, 3, 7, 8, 9), draw)); // 3 + additional
        assertEquals(Group.G7, TotoGame.groupOf(set(1, 2, 3, 8, 9, 10), draw)); // 3
        assertEquals(Group.NONE, TotoGame.groupOf(set(1, 2, 8, 9, 10, 11), draw)); // 2
    }

    @Test
    public void systemCombinationCounts()
    {
        assertEquals(1, TotoGame.combinations(List.of(1, 2, 3, 4, 5, 6)).size());   // Ordinary
        assertEquals(7, TotoGame.combinations(List.of(1, 2, 3, 4, 5, 6, 7)).size()); // System 7
        assertEquals(924, TotoGame.combinations(
                List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)).size());             // System 12
    }

    @Test
    public void rollProduces44Combinations()
    {
        List<Set<Integer>> combos = TotoGame.roll(List.of(1, 2, 3, 4, 5));
        assertEquals(44, combos.size());
        for(Set<Integer> combo : combos)
            assertEquals(6, combo.size());
    }

    @Test
    public void drawProducesSixWinningPlusDistinctAdditional()
    {
        Random rng = new Random(5);
        for(int t = 0; t < 1000; t++)
        {
            Draw draw = TotoGame.draw(rng);
            assertEquals(6, draw.getWinning().size());
            Set<Integer> all = new HashSet<>(draw.getWinning());
            assertTrue("additional is distinct", all.add(draw.getAdditional()));
            for(int n : all)
                assertTrue("in range", n >= 1 && n <= TotoGame.MAX_NUMBER);
        }
    }
}
