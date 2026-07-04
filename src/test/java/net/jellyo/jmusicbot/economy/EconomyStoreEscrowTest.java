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

import com.jagrosh.jmusicbot.economy.EconomyStore.PendingWager;
import java.nio.file.Path;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The crash-safe wager escrow: debit+record, resolve/settle, and boot reclaim are all atomic and once-only. */
public class EconomyStoreEscrowTest
{
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private EconomyStore store;

    @Before
    public void setUp() throws Exception
    {
        Path db = folder.getRoot().toPath().resolve("jmusicbot.db");
        store = new EconomyStore(db);
        store.init();
    }

    @After
    public void tearDown()
    {
        store.close();
    }

    @Test
    public void escrowDebitsAndRecordsAStake()
    {
        store.addCurrency(1L, 1_000);
        assertTrue(store.escrow(1L, 100, "e1", "wheel"));
        assertEquals("stake removed from balance", 900, store.getBalance(1L));
        assertEquals("a recovery row is held", 1, store.pendingWagerCount());
    }

    @Test
    public void escrowRefusesWhenUnaffordableAndRecordsNothing()
    {
        store.addCurrency(1L, 50);
        assertFalse(store.escrow(1L, 100, "e1", "wheel"));
        assertEquals("balance untouched", 50, store.getBalance(1L));
        assertEquals("no recovery row", 0, store.pendingWagerCount());
    }

    @Test
    public void resolveEscrowCreditsThePayoutAndClearsTheRowExactlyOnce()
    {
        store.addCurrency(1L, 1_000);
        store.escrow(1L, 100, "e1", "wheel"); // balance 900
        assertEquals(250, store.resolveEscrow("e1", 250)); // win: credit the 250 payout
        assertEquals("stake gone, payout credited", 1_150, store.getBalance(1L));
        assertEquals(0, store.pendingWagerCount());
        // Idempotent: a second resolve for the same id pays nothing.
        assertEquals(0, store.resolveEscrow("e1", 250));
        assertEquals("no double payout", 1_150, store.getBalance(1L));
    }

    @Test
    public void resolveEscrowWithNoCreditIsALostStake()
    {
        store.addCurrency(1L, 1_000);
        store.escrow(1L, 100, "e1", "blackjack"); // balance 900
        assertEquals(0, store.resolveEscrow("e1", 0)); // loss: clear the row, credit nothing
        assertEquals("stake stays lost on a loss", 900, store.getBalance(1L));
        assertEquals(0, store.pendingWagerCount());
    }

    @Test
    public void increaseEscrowGrowsTheStakeSoTheDoubleIsRecoverableToo()
    {
        store.addCurrency(1L, 1_000);
        store.escrow(1L, 100, "e1", "blackjack"); // balance 900, escrow holds 100
        assertTrue(store.increaseEscrow("e1", 100)); // double-down: balance 800, escrow holds 200
        assertEquals(800, store.getBalance(1L));
        assertEquals(1, store.pendingWagerCount());
        List<PendingWager> refunded = store.reclaimPending();
        assertEquals("the doubled stake is recovered in full", 200, refunded.get(0).getAmount());
        assertEquals(1_000, store.getBalance(1L));
    }

    @Test
    public void increaseEscrowRefusesWhenUnaffordableAndLeavesTheStakeUnchanged()
    {
        store.addCurrency(1L, 150);
        store.escrow(1L, 100, "e1", "blackjack"); // balance 50, escrow holds 100
        assertFalse(store.increaseEscrow("e1", 100)); // can't afford another 100
        assertEquals("balance untouched", 50, store.getBalance(1L));
        assertEquals(1, store.pendingWagerCount());
        assertEquals("escrow still holds the original stake", 100, store.reclaimPending().get(0).getAmount());
    }

    @Test
    public void reclaimPendingRefundsEveryUnresolvedWager()
    {
        store.addCurrency(1L, 1_000);
        store.addCurrency(2L, 1_000);
        store.escrow(1L, 100, "e1", "crash");  // balance 900
        store.escrow(2L, 300, "e2", "mines");  // balance 700
        store.escrow(1L, 50, "e3", "hilo");    // balance 850 (user 1 has two live wagers at once)

        List<PendingWager> refunded = store.reclaimPending();
        assertEquals(3, refunded.size());
        assertEquals("user 1 gets both interrupted stakes back", 1_000, store.getBalance(1L));
        assertEquals("user 2 gets its stake back", 1_000, store.getBalance(2L));
        assertEquals(0, store.pendingWagerCount());
        // A second boot has nothing left to refund.
        assertTrue(store.reclaimPending().isEmpty());
    }

    @Test
    public void settleDuelClearsBothAntesAndPaysTheWinnerAtomically()
    {
        store.addCurrency(1L, 1_000); // challenger
        store.addCurrency(2L, 1_000); // opponent
        store.escrow(1L, 100, "c", "duel"); // challenger balance 900
        store.escrow(2L, 100, "o", "duel"); // opponent balance 900
        assertEquals(2, store.pendingWagerCount());

        store.settleDuel("c", "o", 1L, 200); // challenger wins the 200 pot
        assertEquals("winner nets +100 (-100 stake, +200 pot)", 1_100, store.getBalance(1L));
        assertEquals("loser nets -100", 900, store.getBalance(2L));
        assertEquals(0, store.pendingWagerCount());
        // Idempotent: re-settling pays nothing (rows already gone).
        store.settleDuel("c", "o", 1L, 200);
        assertEquals(1_100, store.getBalance(1L));
    }
}
