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

import com.jagrosh.jmusicbot.economy.games.RouletteGame.BetType;
import com.jagrosh.jmusicbot.economy.games.RouletteGame.Color;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RouletteGameTest
{
    @Test
    public void colorMapping()
    {
        assertEquals(Color.GREEN, RouletteGame.colorOf(0));
        assertEquals(Color.RED, RouletteGame.colorOf(1));
        assertEquals(Color.BLACK, RouletteGame.colorOf(2));
        assertEquals(Color.RED, RouletteGame.colorOf(36));
    }

    @Test
    public void everyBetTypeKeepsAHouseEdge()
    {
        assertEdge(BetType.RED, 0, new Random(1));
        assertEdge(BetType.EVEN, 0, new Random(2));
        assertEdge(BetType.HIGH, 0, new Random(3));
        assertEdge(BetType.DOZEN2, 0, new Random(4));
        assertEdge(BetType.STRAIGHT, 17, new Random(5));
    }

    @Test
    public void straightUpZeroWinsOnlyOnZero()
    {
        assertTrue(RouletteGame.isWin(BetType.STRAIGHT, 0, 0));
        assertTrue(!RouletteGame.isWin(BetType.RED, 0, 0));
        assertTrue(!RouletteGame.isWin(BetType.EVEN, 0, 0));
    }

    private static void assertEdge(BetType bet, int target, Random rng)
    {
        long wager = 100;
        long iterations = 300_000;
        long total = 0;
        for(long i = 0; i < iterations; i++)
            total += RouletteGame.play(bet, target, wager, rng).getPayout();
        double rtp = total / (double) (iterations * wager);
        assertTrue(bet + " RTP was " + rtp, rtp > 0.7 && rtp < 1.0);
    }
}
