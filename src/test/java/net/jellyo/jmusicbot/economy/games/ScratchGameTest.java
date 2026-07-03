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
import com.jagrosh.jmusicbot.economy.games.ScratchGame.Result;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScratchGameTest
{
    @Test
    public void keepsAHouseEdge()
    {
        Random rng = new Random(1);
        long wager = 100;
        long iterations = 2_000_000;
        long total = 0;
        for(long i = 0; i < iterations; i++)
            total += ScratchGame.play(wager, rng).getPayout();
        double rtp = total / (double) (iterations * wager);
        assertTrue("scratch RTP was " + rtp, rtp > 0.7 && rtp < 1.0);
    }

    @Test
    public void gridHasNineCellsAndLuckyCountMatchesPayout()
    {
        Random rng = new Random(9);
        for(int i = 0; i < 5000; i++)
        {
            Result r = ScratchGame.play(100, rng);
            assertEquals(ScratchGame.CELLS, r.getGrid().length);
            long expected = (long) Math.floor(100 * ScratchGame.multiplierForLucky(r.getLuckyCount()));
            assertEquals(expected, r.getPayout());
        }
    }

    @Test
    public void jackpotAtMaxStakeIsBoundedByReturnCap()
    {
        long maxBet = Payouts.maxBetFor(ScratchGame.PRACTICAL_TOP_MULTIPLIER);
        long rawJackpot = (long) Math.floor(maxBet * ScratchGame.JACKPOT_MULTIPLIER);
        assertTrue(rawJackpot > Payouts.MAX_RETURN_PER_ROUND);
        assertEquals(Payouts.MAX_RETURN_PER_ROUND, Payouts.clampReturn(rawJackpot));
    }
}
