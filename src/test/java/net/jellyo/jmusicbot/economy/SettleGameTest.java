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

import com.jagrosh.jmusicbot.economy.EconomyService.GameOutcome;
import java.nio.file.Path;
import java.time.ZoneOffset;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SettleGameTest
{
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private EconomyStore store;
    private EconomyService economy;

    @Before
    public void setUp() throws Exception
    {
        Path db = folder.getRoot().toPath().resolve("jmusicbot.db");
        store = new EconomyStore(db);
        store.init();
        economy = new EconomyService(store, true, ZoneOffset.UTC);
    }

    @After
    public void tearDown()
    {
        store.close();
    }

    @Test
    public void clampsSyntheticOverLimitPayout()
    {
        // A game-logic bug (or a legitimate jackpot) hands settlement a huge raw payout.
        GameOutcome o = economy.settleGame(1, 100, 40_000_000, null);
        assertEquals("payout capped at the per-round return cap",
                Payouts.MAX_RETURN_PER_ROUND, o.getPayout());
        assertEquals(Payouts.MAX_RETURN_PER_ROUND - 100, o.getNet());
        assertTrue(o.isWin());
        assertEquals("credited coins are also capped",
                Payouts.MAX_RETURN_PER_ROUND, store.getBalance(1));
    }

    @Test
    public void legalWinPaysExactlyAndAwardsXpAndStats()
    {
        long xpBefore = store.getProfile(2).getXp();
        GameOutcome o = economy.settleGame(2, 100, 195, null); // a normal coinflip win
        assertEquals(195, o.getPayout());
        assertEquals(95, o.getNet());
        assertTrue(o.getXpAwarded() > 0);
        UserProfile p = store.getProfile(2);
        assertTrue("xp increased", p.getXp() > xpBefore);
        assertEquals("games played incremented", 1, p.getGamesPlayed());
        assertEquals("win recorded", 1, p.getGambleWins());
        assertEquals("biggest win tracked", 95, p.getBiggestWin());
    }

    @Test
    public void lossAwardsXpAndNoRebateAtLowLevel()
    {
        GameOutcome o = economy.settleGame(3, 100, 0, null); // a loss
        assertEquals(-100, o.getNet());
        assertFalse(o.isWin());
        assertEquals("no rebate at level 0", 0, o.getRebate());
        UserProfile p = store.getProfile(3);
        assertEquals(1, p.getGamesPlayed());
        assertEquals(1, p.getGambleLosses());
        assertTrue("xp still awarded for playing", p.getXp() > 0);
    }

    @Test
    public void loyaltyRebateOnLossScalesWithLevelAndHitsDailyCap()
    {
        // Push the user to a high level so the rebate fraction is at its cap.
        store.addXp(4, LevelCurve.totalXpForLevel(200));
        // A net loss of 1,000,000 → raw rebate 10% = 100,000 → capped at the daily cap.
        GameOutcome o = economy.settleGame(4, 1_000_000, 0, null);
        assertEquals(Payouts.REBATE_DAILY_CAP, o.getRebate());
        // A second loss the same day gets nothing — the cap is spent.
        GameOutcome o2 = economy.settleGame(4, 1_000_000, 0, null);
        assertEquals(0, o2.getRebate());
    }
}
