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

import com.jagrosh.jmusicbot.economy.games.DiceGame.Mode;
import com.jagrosh.jmusicbot.economy.games.DiceGame.Result;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DiceGameTest
{
    @Test
    public void exactPaysFixedMultiplierOrNothing()
    {
        Random rng = new Random(5);
        for(int i = 0; i < 5000; i++)
        {
            Result r = DiceGame.play(Mode.EXACT, 4, 100, rng);
            if(r.getRoll() == 4)
                assertEquals(570, r.getPayout());
            else
                assertEquals(0, r.getPayout());
        }
    }

    @Test
    public void everyModeKeepsAHouseEdge()
    {
        assertEdge(Mode.EXACT, 3, new Random(1));
        assertEdge(Mode.EVEN, 0, new Random(2));
        assertEdge(Mode.ODD, 0, new Random(3));
        assertEdge(Mode.HIGH, 0, new Random(4));
        assertEdge(Mode.LOW, 0, new Random(6));
    }

    private static void assertEdge(Mode mode, int target, Random rng)
    {
        long wager = 100;
        long iterations = 300_000;
        long total = 0;
        for(long i = 0; i < iterations; i++)
            total += DiceGame.play(mode, target, wager, rng).getPayout();
        double rtp = total / (double) (iterations * wager);
        assertTrue(mode + " RTP was " + rtp, rtp > 0.7 && rtp < 1.0);
    }
}
