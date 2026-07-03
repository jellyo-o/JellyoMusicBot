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

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InteractiveGamesLogicTest
{
    // ---- Blackjack ---------------------------------------------------------

    @Test
    public void blackjackHandValues()
    {
        assertEquals(21, BlackjackGame.bestValue(Arrays.asList(1, 13)));   // A + K
        assertEquals(12, BlackjackGame.bestValue(Arrays.asList(1, 1)));    // A + A -> 11 + 1
        assertEquals(21, BlackjackGame.bestValue(Arrays.asList(1, 1, 9))); // 11 + 1 + 9
        assertEquals(25, BlackjackGame.bestValue(Arrays.asList(10, 10, 5)));
        assertTrue(BlackjackGame.isBlackjack(Arrays.asList(1, 10)));
        assertFalse(BlackjackGame.isBlackjack(Arrays.asList(1, 5, 5)));    // 21 but three cards
        assertTrue(BlackjackGame.isBust(Arrays.asList(10, 10, 5)));
        assertTrue(BlackjackGame.dealerStands(Arrays.asList(10, 7)));
        assertFalse(BlackjackGame.dealerStands(Arrays.asList(10, 6)));
    }

    // ---- Crash -------------------------------------------------------------

    @Test
    public void crashPointStaysInBounds()
    {
        Random rng = new Random(4);
        for(int i = 0; i < 100_000; i++)
        {
            double m = CrashGame.crashPoint(rng);
            assertTrue(m >= 1.0 && m <= CrashGame.MAX_MULTIPLIER);
        }
    }

    @Test
    public void crashKeepsAConstantHouseEdge()
    {
        Random rng = new Random(8);
        long wager = 100;
        long iterations = 1_000_000;
        double target = 2.0;
        long total = 0;
        for(long i = 0; i < iterations; i++)
            total += CrashGame.payout(wager, target, CrashGame.crashPoint(rng));
        double rtp = total / (double) (iterations * wager);
        assertTrue("crash RTP was " + rtp, rtp > 0.9 && rtp < 1.0);
    }

    // ---- Mines -------------------------------------------------------------

    @Test
    public void minesMultiplierGrowsWithReveals()
    {
        double prev = MinesGame.multiplier(3, 0);
        assertEquals(0.98, prev, 0.0001); // house edge before any reveal
        for(int r = 1; r <= MinesGame.safeTiles(3); r++)
        {
            double m = MinesGame.multiplier(3, r);
            assertTrue("reveal " + r + " should grow", m > prev);
            prev = m;
        }
        assertTrue("first reveal already beats the stake", MinesGame.multiplier(3, 1) > 1.0);
    }

    @Test
    public void minesPlacesExactlyTheRequestedBombs()
    {
        Random rng = new Random(2);
        boolean[] mines = MinesGame.placeBombs(4, rng);
        int count = 0;
        for(boolean m : mines)
            if(m)
                count++;
        assertEquals(4, count);
        assertEquals(MinesGame.TILES, mines.length);
    }

    // ---- Hi-Lo -------------------------------------------------------------

    @Test
    public void hiLoStepReturnsConstantEdge()
    {
        for(int card = 2; card <= 12; card++)
        {
            double up = HiLoGame.higherChance(card) * HiLoGame.stepMultiplier(card, true);
            double down = HiLoGame.lowerChance(card) * HiLoGame.stepMultiplier(card, false);
            assertEquals("higher RTP on " + card, 1.0 - HiLoGame.EDGE, up, 0.0001);
            assertEquals("lower RTP on " + card, 1.0 - HiLoGame.EDGE, down, 0.0001);
        }
        assertFalse(HiLoGame.higherPossible(13));
        assertFalse(HiLoGame.lowerPossible(1));
    }

    // ---- Double or Nothing -------------------------------------------------

    @Test
    public void doubleIsAlwaysNegativeExpectationAndDecays()
    {
        double prev = DoubleGame.winChance(0);
        assertTrue("first double is -EV", 2.0 * prev < 1.0);
        for(int step = 1; step <= DoubleGame.MAX_STREAK; step++)
        {
            double p = DoubleGame.winChance(step);
            assertTrue("win chance decays", p < prev);
            assertTrue("each double is -EV", 2.0 * p < 1.0);
            prev = p;
        }
        assertEquals(800L, DoubleGame.potFor(100, 3));
        assertTrue(DoubleGame.canContinue(DoubleGame.MAX_STREAK - 1));
        assertFalse(DoubleGame.canContinue(DoubleGame.MAX_STREAK));
    }
}
