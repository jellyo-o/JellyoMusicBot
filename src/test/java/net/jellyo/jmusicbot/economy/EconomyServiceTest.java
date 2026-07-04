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
        // The 20h floor (09:00 + 20h = 05:00 next day) outlasts next midnight, so 17 hours remain.
        assertEquals(17 * 3600L, d.secondsUntilNext);
    }

    @Test
    public void consecutiveDayExtendsStreak()
    {
        // A different calendar day AND >= 20h since the last claim -> claimable, streak extends.
        long yesterday = TODAY.minusDays(1).atTime(9, 0).atZone(UTC).toEpochSecond();
        EconomyService.DailyDecision d = EconomyService.decideDaily(yesterday, 5, NOON, UTC);
        assertTrue(d.claimable);
        assertEquals(6, d.streak);
    }

    @Test
    public void midnightStraddleCannotDoubleClaim()
    {
        // The reported exploit: claim at 23:59:30, then again ~50s later at 00:00:20. It is a new local
        // day (so the calendar check alone would allow it), but the 20h elapsed floor still blocks it.
        long lateYesterday = TODAY.minusDays(1).atTime(23, 59, 30).atZone(UTC).toEpochSecond();
        long justAfterMidnight = TODAY.atTime(0, 0, 20).atZone(UTC).toEpochSecond();
        EconomyService.DailyDecision d = EconomyService.decideDaily(lateYesterday, 3, justAfterMidnight, UTC);
        assertFalse("a claim seconds after midnight is still on cooldown", d.claimable);
        assertEquals(3, d.streak);
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
