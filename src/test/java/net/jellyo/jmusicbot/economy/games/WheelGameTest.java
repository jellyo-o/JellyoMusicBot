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
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WheelGameTest
{
    @Test
    public void keepsAHouseEdge()
    {
        Random rng = new Random(1);
        long wager = 100;
        long iterations = 300_000;
        long total = 0;
        for(long i = 0; i < iterations; i++)
            total += WheelGame.play(wager, rng).getPayout();
        double rtp = total / (double) (iterations * wager);
        assertTrue("wheel RTP was " + rtp, rtp > 0.7 && rtp < 1.0);
    }

    @Test
    public void topMultiplierAtMaxStakeStaysWithinReturnCap()
    {
        // The wheel is sized to its true top multiplier, so a jackpot at max stake
        // exactly reaches (and never exceeds) the per-round return cap.
        long maxBet = Payouts.maxBetFor(WheelGame.TOP_MULTIPLIER);
        long jackpot = (long) Math.floor(maxBet * WheelGame.TOP_MULTIPLIER);
        assertTrue(jackpot <= Payouts.MAX_RETURN_PER_ROUND);
        assertEquals(jackpot, Payouts.clampReturn(jackpot));
    }
}
