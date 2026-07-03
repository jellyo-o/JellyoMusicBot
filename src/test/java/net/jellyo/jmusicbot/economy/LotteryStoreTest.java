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

import com.jagrosh.jmusicbot.economy.LotteryStore.DrawInfo;
import com.jagrosh.jmusicbot.economy.LotteryStore.DrawResult;
import com.jagrosh.jmusicbot.economy.LotteryStore.TicketHolder;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LotteryStoreTest
{
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private EconomyStore economy;
    private LotteryStore lottery;

    @Before
    public void setUp() throws Exception
    {
        Path db = folder.getRoot().toPath().resolve("jmusicbot.db");
        economy = new EconomyStore(db);
        economy.init();
        lottery = new LotteryStore(db);
        lottery.init();
    }

    @After
    public void tearDown()
    {
        lottery.close();
        economy.close();
    }

    @Test
    public void weightedPickCoversCumulativeRanges()
    {
        List<TicketHolder> holders = List.of(new TicketHolder(10, 3), new TicketHolder(20, 1));
        assertEquals(10, LotteryStore.weightedPick(holders, 0));
        assertEquals(10, LotteryStore.weightedPick(holders, 2));
        assertEquals(20, LotteryStore.weightedPick(holders, 3));
    }

    @Test
    public void dueDrawsReflectTheDrawEpoch()
    {
        lottery.buyTickets(1L, 9L, 100L, 1, 100, 0, 1000); // draw_epoch = 1000
        assertTrue(lottery.dueDraws(1000).contains(1L));
        assertFalse(lottery.dueDraws(999).contains(1L));
    }

    @Test
    public void buyingOpensARoundAndGrowsThePot()
    {
        DrawInfo info = lottery.buyTickets(1L, 9L, 100L, 3, 100, 86_400, 1000);
        assertEquals(300, info.getPot());
        assertEquals(3, info.getUserTickets());
        assertEquals(3, info.getTotalTickets());
        DrawInfo info2 = lottery.buyTickets(1L, 9L, 200L, 2, 100, 86_400, 1000);
        assertEquals(500, info2.getPot());
        assertEquals(5, info2.getTotalTickets());
    }

    @Test
    public void resolveDrawCreditsWinnerAtomicallyAndIsIdempotent()
    {
        long winner = 100L;
        economy.addCurrency(winner, 1000); // give the winner a profile + starting balance
        lottery.buyTickets(1L, 9L, winner, 5, 100, 86_400, 1000); // pot 500, sole participant

        DrawResult result = lottery.resolveDraw(1L, new Random(7));
        assertTrue(result.hasWinner());
        assertEquals(winner, result.getWinnerId());
        assertEquals(500, result.getPot());
        assertEquals("winner credited the pot atomically", 1500, economy.getBalance(winner));

        // A second resolution (e.g. a double boot) finds no open round and pays nothing.
        assertNull(lottery.resolveDraw(1L, new Random(7)));
        assertEquals("no double payout", 1500, economy.getBalance(winner));
        assertNull("round is closed", lottery.getDraw(1L, winner));
    }
}
