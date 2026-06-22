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

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EconomyServiceTest
{
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final LocalDate TODAY = LocalDate.of(2024, 1, 15);
    private static final long NOON = TODAY.atTime(12, 0).atZone(UTC).toEpochSecond();

    @Test
    public void firstDailyEverIsClaimableWithStreakOne()
    {
        EconomyService.DailyDecision d = EconomyService.decideDaily(0, 0, NOON, UTC);
        assertTrue(d.claimable);
        assertEquals(1, d.streak);
    }

    @Test
    public void secondClaimSameDayIsOnCooldown()
    {
        long earlierToday = TODAY.atTime(9, 0).atZone(UTC).toEpochSecond();
        EconomyService.DailyDecision d = EconomyService.decideDaily(earlierToday, 5, NOON, UTC);
        assertFalse(d.claimable);
        assertEquals(5, d.streak);
        // Until next UTC midnight: 12 hours.
        assertEquals(12 * 3600L, d.secondsUntilNext);
    }

    @Test
    public void consecutiveDayExtendsStreak()
    {
        long yesterday = TODAY.minusDays(1).atTime(20, 0).atZone(UTC).toEpochSecond();
        EconomyService.DailyDecision d = EconomyService.decideDaily(yesterday, 5, NOON, UTC);
        assertTrue(d.claimable);
        assertEquals(6, d.streak);
    }

    @Test
    public void skippedDayResetsStreak()
    {
        long threeDaysAgo = TODAY.minusDays(3).atTime(20, 0).atZone(UTC).toEpochSecond();
        EconomyService.DailyDecision d = EconomyService.decideDaily(threeDaysAgo, 5, NOON, UTC);
        assertTrue(d.claimable);
        assertEquals(1, d.streak);
    }

    @Test
    public void coinsFormatsWithEmoji()
    {
        assertEquals("1,234 " + EconomyService.CURRENCY_EMOJI, EconomyService.coins(1234));
    }
}
