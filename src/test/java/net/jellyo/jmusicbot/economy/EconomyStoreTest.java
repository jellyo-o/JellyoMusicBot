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

public class EconomyStoreTest
{
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

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
        if(store != null)
            store.close();
    }

    @Test
    public void unknownUserHasEmptyProfile()
    {
        UserProfile p = store.getProfile(42L);
        assertEquals(42L, p.getUserId());
        assertEquals(0L, p.getCurrency());
        assertEquals(0, p.getLevel());
    }

    @Test
    public void currencyClampsAtZero()
    {
        store.addCurrency(1L, 100);
        assertEquals(100L, store.getBalance(1L));
        store.addCurrency(1L, -250);
        assertEquals(0L, store.getBalance(1L));
    }

    @Test
    public void trySpendIsAtomicAndChecksBalance()
    {
        store.addCurrency(1L, 50);
        assertFalse(store.trySpend(1L, 80));
        assertEquals(50L, store.getBalance(1L));
        assertTrue(store.trySpend(1L, 30));
        assertEquals(20L, store.getBalance(1L));
    }

    @Test
    public void listenedTimeAccumulates()
    {
        assertEquals(90_000L, store.addMsListened(1L, 90_000L));
        assertEquals(150_000L, store.addMsListened(1L, 60_000L));
        assertEquals(2L, store.getProfile(1L).getMinutesListened());
    }

    @Test
    public void achievementsGrantOnce()
    {
        assertTrue(store.grantAchievement(1L, "first_song", 1000L));
        assertFalse(store.grantAchievement(1L, "first_song", 2000L));
        assertTrue(store.hasAchievement(1L, "first_song"));
        assertEquals(1, store.earnedAchievementIds(1L).size());
    }

    @Test
    public void leaderboardAndRankByCurrency()
    {
        store.addCurrency(1L, 500);
        store.addCurrency(2L, 1500);
        store.addCurrency(3L, 1000);

        List<EconomyStore.LeaderEntry> top = store.topBy(EconomyStore.LeaderMetric.CURRENCY, 10);
        assertEquals(3, top.size());
        assertEquals(2L, top.get(0).getUserId());
        assertEquals(1500L, top.get(0).getValue());

        assertEquals(1, store.rankBy(EconomyStore.LeaderMetric.CURRENCY, 2L));
        assertEquals(2, store.rankBy(EconomyStore.LeaderMetric.CURRENCY, 3L));
        assertEquals(3, store.rankBy(EconomyStore.LeaderMetric.CURRENCY, 1L));
    }

    @Test
    public void ensureProfileCachesDisplayFields()
    {
        store.ensureProfile(7L, "Alice", "http://avatar");
        UserProfile p = store.getProfile(7L);
        assertEquals("Alice", p.getUsername());
        assertEquals("http://avatar", p.getAvatar());
        // A null username must not wipe the cached value.
        store.ensureProfile(7L, null, null);
        assertEquals("Alice", store.getProfile(7L).getUsername());
    }
}
