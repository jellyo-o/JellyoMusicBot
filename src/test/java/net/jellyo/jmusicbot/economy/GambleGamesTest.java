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

import com.jagrosh.jmusicbot.economy.GambleGames.Game;
import com.jagrosh.jmusicbot.economy.GambleGames.Result;
import com.jagrosh.jmusicbot.economy.GambleGames.Symbol;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GambleGamesTest
{
    @Test
    public void slotMultiplierTable()
    {
        assertEquals(40.0, GambleGames.slotMultiplier(
                new Symbol[]{Symbol.SEVEN, Symbol.SEVEN, Symbol.SEVEN}), 0.0001);
        assertEquals(4.0, GambleGames.slotMultiplier(
                new Symbol[]{Symbol.CHERRY, Symbol.CHERRY, Symbol.CHERRY}), 0.0001);
        assertEquals(1.3, GambleGames.slotMultiplier(
                new Symbol[]{Symbol.BELL, Symbol.BELL, Symbol.STAR}), 0.0001);
        assertEquals(0.0, GambleGames.slotMultiplier(
                new Symbol[]{Symbol.CHERRY, Symbol.LEMON, Symbol.BELL}), 0.0001);
    }

    @Test
    public void coinflipPaysFixedMultiplierOrNothing()
    {
        Random rng = new Random(7);
        for(int i = 0; i < 2000; i++)
        {
            Result r = GambleGames.coinflip(100, rng);
            if(r.isWon())
                assertEquals(195, r.getPayout());
            else
                assertEquals(0, r.getPayout());
        }
    }

    @Test
    public void dicePaysFixedMultiplierOrNothing()
    {
        Random rng = new Random(11);
        for(int i = 0; i < 2000; i++)
        {
            Result r = GambleGames.dice(100, rng);
            assertTrue(r.getPayout() == 0 || r.getPayout() == 170);
        }
    }

    @Test
    public void coinflipFloorsPayoutSoTheEdgeSurvivesAtTheMinBet()
    {
        // floor(10 * 1.95) = 19, not round's 20 — round-half-up would make a 10-coin coinflip a 2.0x
        // break-even (RTP 100%), erasing the house edge at the minimum bet.
        Random rng = new Random(3);
        boolean sawWin = false;
        for(int i = 0; i < 200; i++)
        {
            Result r = GambleGames.coinflip(10, rng);
            if(r.isWon())
            {
                assertEquals(19, r.getPayout());
                sawWin = true;
            }
            else
                assertEquals(0, r.getPayout());
        }
        assertTrue(sawWin);
    }

    @Test
    public void diceFloorsPayoutSoNoWagerBecomesPositiveEv()
    {
        // floor(15 * 1.7) = 25, not round's 26 — round-half-up would flip a 15-coin dice bet to a
        // positive expected value (26/15 * 21/36 > 1) that the house loses long-term.
        Random rng = new Random(9);
        boolean sawWin = false;
        for(int i = 0; i < 400; i++)
        {
            Result r = GambleGames.dice(15, rng);
            if(r.getPayout() > 0)
            {
                assertEquals(25, r.getPayout());
                sawWin = true;
            }
        }
        assertTrue(sawWin);
    }

    @Test
    public void everyGameKeepsAHouseEdge()
    {
        assertEdge(Game.COINFLIP, new Random(1));
        assertEdge(Game.DICE, new Random(2));
        assertEdge(Game.SLOTS, new Random(3));
    }

    private static void assertEdge(Game game, Random rng)
    {
        long wager = 100;
        long iterations = 300_000;
        long totalPayout = 0;
        for(long i = 0; i < iterations; i++)
            totalPayout += GambleGames.play(game, wager, rng).getPayout();
        double returnToPlayer = totalPayout / (double) (iterations * wager);
        // Player return should be below 1 (house wins long-term) but still generous.
        assertTrue(game + " return-to-player was " + returnToPlayer, returnToPlayer > 0.7 && returnToPlayer < 1.0);
    }
}
