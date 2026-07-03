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

import com.jagrosh.jmusicbot.economy.EconomyService.CooldownDecision;
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

public class EarnersTest
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
    public void decideCooldownIsElapsedBased()
    {
        long now = 1_000_000;
        assertTrue("never used before is ready", EconomyService.decideCooldown(0, now, 1200).isReady());
        CooldownDecision waiting = EconomyService.decideCooldown(now - 100, now, 1200);
        assertFalse(waiting.isReady());
        assertEquals(1100, waiting.getSecondsUntilNext());
        assertTrue("exactly elapsed is ready", EconomyService.decideCooldown(now - 1200, now, 1200).isReady());
        assertTrue("past elapsed is ready", EconomyService.decideCooldown(now - 5000, now, 1200).isReady());
    }

    @Test
    public void claimWorkPaysThenGoesOnCooldown()
    {
        long userId = 100;
        long before = store.getBalance(userId);
        var first = economy.claimWork(userIdUser(userId), null);
        assertTrue(first.isWorked());
        assertTrue("payout in the level-0 range",
                first.getAmount() >= EconomyService.WORK_MIN_COINS
                        && first.getAmount() <= EconomyService.WORK_MAX_COINS + EconomyService.WORK_MAX_LEVEL_BONUS);
        assertEquals(before + first.getAmount(), store.getBalance(userId));
        assertTrue("xp awarded", store.getProfile(userId).getXp() > 0);

        var second = economy.claimWork(userIdUser(userId), null);
        assertFalse("immediately on cooldown", second.isWorked());
        assertTrue(second.getSecondsUntilNext() > 0);
    }

    @Test
    public void triviaCooldownConsumesTheAttempt()
    {
        long userId = 200;
        net.dv8tion.jda.api.entities.User user = userIdUser(userId);
        assertEquals("first attempt starts", 0, economy.tryStartTrivia(user));
        assertTrue("second attempt is on cooldown", economy.tryStartTrivia(user) > 0);
    }

    /** Minimal User stub — claimWork/tryStartTrivia only need id, name and avatar url. */
    private static net.dv8tion.jda.api.entities.User userIdUser(long id)
    {
        return (net.dv8tion.jda.api.entities.User) java.lang.reflect.Proxy.newProxyInstance(
                EarnersTest.class.getClassLoader(),
                new Class[]{net.dv8tion.jda.api.entities.User.class},
                (proxy, method, args) ->
                {
                    switch(method.getName())
                    {
                        case "getIdLong": return id;
                        case "getId": return String.valueOf(id);
                        case "getName": return "user" + id;
                        case "getEffectiveName": return "user" + id;
                        case "getEffectiveAvatarUrl": return "https://example.com/a.png";
                        case "getAvatarUrl": return null;
                        default: return defaultFor(method.getReturnType());
                    }
                });
    }

    private static Object defaultFor(Class<?> type)
    {
        if(type == boolean.class) return false;
        if(type == long.class) return 0L;
        if(type == int.class) return 0;
        return null;
    }
}
