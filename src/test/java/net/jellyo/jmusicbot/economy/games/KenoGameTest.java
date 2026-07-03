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

import com.jagrosh.jmusicbot.economy.Payouts;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class KenoGameTest
{
    private static final Set<Integer> PICKS =
            IntStream.rangeClosed(1, KenoGame.PICKS).boxed().collect(Collectors.toSet());

    @Test
    public void drawsDistinctNumbersInRange()
    {
        Random rng = new Random(3);
        for(int t = 0; t < 500; t++)
        {
            int[] drawn = KenoGame.drawDistinct(KenoGame.POOL, KenoGame.DRAW, rng);
            assertEquals(KenoGame.DRAW, drawn.length);
            Set<Integer> seen = new HashSet<>();
            for(int n : drawn)
            {
                assertTrue("in range", n >= 1 && n <= KenoGame.POOL);
                assertTrue("distinct", seen.add(n));
            }
        }
    }

    @Test
    public void keepsAHouseEdgeAtRepresentativeStake()
    {
        // Run below the clamp point (jackpot 100x * 100 = 10,000 << cap), so this is the true RTP.
        Random rng = new Random(7);
        long wager = 100;
        long iterations = 2_000_000;
        long total = 0;
        for(long i = 0; i < iterations; i++)
            total += KenoGame.play(PICKS, wager, rng).getPayout();
        double rtp = total / (double) (iterations * wager);
        assertTrue("keno RTP was " + rtp, rtp > 0.7 && rtp < 1.0);
    }

    @Test
    public void jackpotAtMaxStakeIsBoundedByTheReturnCap()
    {
        // Sized to the practical (3-hit) tier; the rare all-hit jackpot at max stake
        // is the disclosed table-max, clamped to the per-round return cap.
        long maxBet = Payouts.maxBetFor(KenoGame.PRACTICAL_TOP_MULTIPLIER);
        long rawJackpot = (long) Math.floor(maxBet * KenoGame.JACKPOT_MULTIPLIER);
        assertTrue("raw jackpot exceeds the cap before clamping",
                rawJackpot > Payouts.MAX_RETURN_PER_ROUND);
        assertEquals(Payouts.MAX_RETURN_PER_ROUND, Payouts.clampReturn(rawJackpot));
    }

    @Test
    public void multiplierTable()
    {
        assertEquals(0.0, KenoGame.multiplierForHits(0), 0.0001);
        assertEquals(0.0, KenoGame.multiplierForHits(1), 0.0001);
        assertEquals(2.0, KenoGame.multiplierForHits(2), 0.0001);
        assertEquals(8.0, KenoGame.multiplierForHits(3), 0.0001);
        assertEquals(100.0, KenoGame.multiplierForHits(4), 0.0001);
    }
}
