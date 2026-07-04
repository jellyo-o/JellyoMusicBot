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

import com.jagrosh.jmusicbot.economy.LotteryStore.BuyOutcome;
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
    public void buyingDebitsCoinsOpensTheRoundAndGrowsThePot()
    {
        economy.addCurrency(100L, 10_000);
        economy.addCurrency(200L, 10_000);
        BuyOutcome o1 = lottery.buyTickets(100L, 3, 100, 86_400, 1000, 1000); // draw_epoch = 87400
        assertEquals(BuyOutcome.Status.BOUGHT, o1.getStatus());
        assertEquals(300, o1.getInfo().getPot());
        assertEquals(3, o1.getInfo().getUserTickets());
        assertEquals(3, o1.getInfo().getTotalTickets());
        assertEquals("coins debited for the tickets, in the same transaction", 9_700, economy.getBalance(100L));

        BuyOutcome o2 = lottery.buyTickets(200L, 2, 100, 86_400, 1000, 1000);
        assertEquals(BuyOutcome.Status.BOUGHT, o2.getStatus());
        assertEquals(500, o2.getInfo().getPot());
        assertEquals(5, o2.getInfo().getTotalTickets());
        assertEquals(2, o2.getInfo().getUserTickets());
        assertEquals(9_800, economy.getBalance(200L));
        assertEquals(3, lottery.getUserTickets(100L));
    }

    @Test
    public void buyingWithoutEnoughCoinsWritesNothing()
    {
        economy.addCurrency(100L, 250); // less than 3 * 100
        BuyOutcome o = lottery.buyTickets(100L, 3, 100, 86_400, 1000, 1000);
        assertEquals(BuyOutcome.Status.INSUFFICIENT, o.getStatus());
        assertEquals("coins untouched", 250, economy.getBalance(100L));
        assertEquals("no tickets granted", 0, lottery.getUserTickets(100L));
        assertNull("no round opened", lottery.getInfo(100L));
    }

    @Test
    public void isDueReflectsTheDrawEpoch()
    {
        economy.addCurrency(100L, 1_000);
        lottery.buyTickets(100L, 1, 100, 0, 1000, 1000); // draw_epoch = 1000
        assertTrue(lottery.isDue(1000));
        assertFalse(lottery.isDue(999));
    }

    @Test
    public void perUserCapIsEnforcedAtomicallyAndRejectionWritesNothing()
    {
        economy.addCurrency(100L, 10_000);
        assertEquals(BuyOutcome.Status.BOUGHT, lottery.buyTickets(100L, 40, 100, 86_400, 1000, 50).getStatus());
        assertEquals(40, lottery.getUserTickets(100L));
        assertEquals("debited for 40 tickets", 6_000, economy.getBalance(100L));

        // A buy that would exceed the cap is rejected and writes NOTHING — no tickets, no pot, no debit.
        assertEquals(BuyOutcome.Status.CAP_REACHED,
                lottery.buyTickets(100L, 20, 100, 86_400, 1000, 50).getStatus());
        assertEquals("tickets unchanged after a capped buy", 40, lottery.getUserTickets(100L));
        assertEquals("pot not grown by the rejected buy", 4000, lottery.getInfo(100L).getPot());
        assertEquals("coins not debited by the rejected buy", 6_000, economy.getBalance(100L));

        // A buy that lands exactly on the cap is allowed.
        assertEquals(BuyOutcome.Status.BOUGHT, lottery.buyTickets(100L, 10, 100, 86_400, 1000, 50).getStatus());
        assertEquals(50, lottery.getUserTickets(100L));
        assertEquals(5000, lottery.getInfo(100L).getPot());
        assertEquals(5_000, economy.getBalance(100L));
    }

    @Test
    public void resolveDrawCreditsWinnerAtomicallyAndIsIdempotent()
    {
        long winner = 100L;
        economy.addCurrency(winner, 1_000);
        lottery.buyTickets(winner, 5, 100, 86_400, 1000, 1000); // cost 500 -> balance 500, pot 500
        assertEquals("stake debited by the buy", 500, economy.getBalance(winner));

        DrawResult result = lottery.resolveDraw(new Random(7));
        assertTrue(result.hasWinner());
        assertEquals(winner, result.getWinnerId());
        assertEquals(500, result.getPot());
        assertEquals("sole participant wins their own pot back", 1_000, economy.getBalance(winner));

        // A second resolution (a double boot) finds no open round and pays nothing.
        assertNull(lottery.resolveDraw(new Random(7)));
        assertEquals("no double payout", 1_000, economy.getBalance(winner));
        assertNull("round is closed", lottery.getInfo(winner));
    }
}
