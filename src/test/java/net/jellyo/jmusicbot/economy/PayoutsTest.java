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

import java.time.ZoneOffset;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PayoutsTest
{
    @Test
    public void maxBetForSizesByReturnMultiplier()
    {
        // Low-multiplier fixed games are bounded by the hard bet ceiling, not the return cap.
        assertEquals(Payouts.HARD_MAX_BET, Payouts.maxBetFor(1.95)); // coinflip
        assertEquals(Payouts.HARD_MAX_BET, Payouts.maxBetFor(2.0));  // red/black
        // High-multiplier bets get a lower table limit so the max return stays bounded.
        assertEquals(833_333, Payouts.maxBetFor(3.0));               // dozen
        assertEquals(69_444, Payouts.maxBetFor(36.0));               // straight-up
        // A legal max bet on any type can never return more than the per-round cap.
        assertTrue(Payouts.maxBetFor(36.0) * 36L <= Payouts.MAX_RETURN_PER_ROUND);
        assertTrue(Payouts.maxBetFor(3.0) * 3L <= Payouts.MAX_RETURN_PER_ROUND);
    }

    @Test
    public void isLegalBetEnforcesBounds()
    {
        assertFalse("below min", Payouts.isLegalBet(Payouts.MIN_BET - 1, 2.0));
        assertTrue("at min", Payouts.isLegalBet(Payouts.MIN_BET, 2.0));
        assertTrue("at max", Payouts.isLegalBet(Payouts.maxBetFor(36.0), 36.0));
        assertFalse("over table limit", Payouts.isLegalBet(Payouts.maxBetFor(36.0) + 1, 36.0));
    }

    @Test
    public void clampReturnNeverLowersAWinBelowStakeButBoundsRunaway()
    {
        // A normal win passes through untouched.
        assertEquals(1_950, Payouts.clampReturn(1_950));
        // The disclosed jackpot / bug-guard cap engages only past the ceiling.
        assertEquals(Payouts.MAX_RETURN_PER_ROUND, Payouts.clampReturn(40_000_000));
        assertEquals(Payouts.MAX_RETURN_PER_ROUND, Payouts.clampReturn(Payouts.MAX_RETURN_PER_ROUND));
    }

    @Test
    public void gameXpScalesWithStakeAndClamps()
    {
        assertEquals(Payouts.GAME_XP_BASE, Payouts.gameXp(Payouts.MIN_BET)); // tiny bet -> base
        assertEquals(15, Payouts.gameXp(1_000));                            // 5 + 1% of 1000
        assertEquals(Payouts.GAME_XP_MAX, Payouts.gameXp(1_000_000));       // capped
        assertEquals(Payouts.GAME_XP_BASE, Payouts.gameXp(0));
    }

    @Test
    public void loyaltyRebateScalesWithLevelAndHonoursCap()
    {
        assertEquals(0, Payouts.loyaltyRebate(1_000, 0));               // level 0 -> nothing
        assertEquals(0, Payouts.loyaltyRebate(0, 200));                // no loss -> nothing
        assertEquals(0, Payouts.loyaltyRebate(-500, 200));             // a win is not rebated
        // Fraction is capped at REBATE_CAP no matter how high the level.
        assertEquals(Payouts.REBATE_CAP, Payouts.rebateFraction(100_000), 0.0);
        assertEquals((long) Math.floor(10_000 * Payouts.REBATE_CAP),
                Payouts.loyaltyRebate(10_000, 100_000));
        // A mid level gives a proportional-but-smaller cut.
        double midFraction = Payouts.rebateFraction(50);
        assertTrue(midFraction > 0 && midFraction < Payouts.REBATE_CAP);
    }

    @Test
    public void dayKeyRollsOverOnLocalCalendarDay()
    {
        long midDay = 1_700_000_000L; // some instant
        long sameDayLater = midDay + 3_600; // +1h, same UTC day
        long nextDay = midDay + 86_400;     // +1d
        assertEquals(Payouts.dayKey(midDay, ZoneOffset.UTC), Payouts.dayKey(sameDayLater, ZoneOffset.UTC));
        assertEquals(Payouts.dayKey(midDay, ZoneOffset.UTC) + 1, Payouts.dayKey(nextDay, ZoneOffset.UTC));
    }
}
