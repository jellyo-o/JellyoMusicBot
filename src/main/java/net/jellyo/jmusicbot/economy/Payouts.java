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

import java.time.Instant;
import java.time.ZoneId;

/**
 * Pure, tunable balancing + XP maths shared by every casino game. Kept separate
 * from the individual game logic so the anti-runaway policy lives in exactly one
 * place and can be unit tested without a database.
 *
 * <p><b>Stake convention:</b> a game's {@code rawPayout} is the total coins
 * returned to the player, stake-inclusive, {@code 0} on a total loss (matching
 * {@link GambleGames.Result#getPayout()}). So {@code profit = rawPayout - wager}.
 *
 * <p><b>Anti-runaway = per-game table limits.</b> Rather than clamping payouts
 * after the fact (which silently guts high-multiplier games at normal stakes and
 * feels rigged), we bound the input stake per bet type via {@link #maxBetFor} so
 * the maximum possible <i>return</i> is bounded while the odds stay exact. The
 * single value {@link #MAX_RETURN_PER_ROUND} is used identically as a
 * <i>return</i> cap by both {@link #maxBetFor} and {@link #clampReturn}, so
 * retuning it can never desync the two.
 */
public final class Payouts
{
    /** Smallest legal wager on any game. */
    public static final long MIN_BET = 10;
    /** Hardest ceiling on any single wager, regardless of the game's multiplier. */
    public static final long HARD_MAX_BET = 1_000_000;
    /**
     * The most any single round may <b>return</b> to the player (stake included).
     * Doubles as the per-bet-type stake sizing input ({@link #maxBetFor}) and the
     * settle-time return cap ({@link #clampReturn}) — one meaning, both places.
     */
    public static final long MAX_RETURN_PER_ROUND = 2_500_000;

    // ---- participation XP (playing grants XP, scaled with stake, capped) ------
    static final long GAME_XP_BASE = 5;
    static final long GAME_XP_MAX = 50;
    static final double GAME_XP_PER_COIN = 0.01;

    // ---- loyalty rebate (level-based, long-horizon, daily-capped) -------------
    /** Rebate fraction gained per level. ~level 150 reaches the cap (a long horizon). */
    static final double REBATE_PER_LEVEL = 0.00067;
    /** Maximum rebate fraction of a net loss, regardless of level. */
    static final double REBATE_CAP = 0.10;
    /** Absolute coins of rebate a user may accrue per day, so it never becomes income. */
    public static final long REBATE_DAILY_CAP = 50_000;

    private Payouts() {}

    /**
     * Largest legal wager for a bet whose top stake-inclusive return is
     * {@code sizingMultiplier}. For fixed-outcome games this is the game's only
     * multiplier (so the return cap never bites); for jackpot games it is a
     * practical high tier (the rare mega-jackpot is then bounded by
     * {@link #clampReturn}).
     */
    public static long maxBetFor(double sizingMultiplier)
    {
        if(sizingMultiplier <= 1.0)
            return HARD_MAX_BET;
        long byReturn = (long) Math.floor(MAX_RETURN_PER_ROUND / sizingMultiplier);
        return Math.max(MIN_BET, Math.min(HARD_MAX_BET, byReturn));
    }

    /**
     * Largest legal per-unit stake for an entry of {@code boards} independent
     * boards/numbers that settle together as one {@link #MAX_RETURN_PER_ROUND}-capped
     * round (a TOTO/4-D System or Roll). Beyond bounding a single board's return via
     * {@link #maxBetFor}, this bounds the <b>whole entry's</b> stake to the per-round
     * return cap, so a multi-board entry can never stake more than it could ever win
     * back — i.e. it can never be a guaranteed loss even on the top prize (which the
     * settle-time {@link #clampReturn} would otherwise make possible).
     */
    public static long maxUnitForEntry(double sizingMultiplier, int boards)
    {
        long perBoard = maxBetFor(sizingMultiplier);
        if(boards <= 1)
            return perBoard;
        long byEntryCap = MAX_RETURN_PER_ROUND / boards; // floor: boards * unit <= MAX_RETURN_PER_ROUND
        return Math.max(MIN_BET, Math.min(perBoard, byEntryCap));
    }

    /** True if {@code wager} is within [{@link #MIN_BET}, {@link #maxBetFor}] for the multiplier. */
    public static boolean isLegalBet(long wager, double sizingMultiplier)
    {
        return wager >= MIN_BET && wager <= maxBetFor(sizingMultiplier);
    }

    /**
     * Clamps a stake-inclusive payout to {@link #MAX_RETURN_PER_ROUND}. This is
     * the disclosed table-max for jackpot games and a runaway bug-guard for the
     * rest; it never lowers a payout below the stake on a win.
     */
    public static long clampReturn(long rawPayout)
    {
        return Math.min(rawPayout, MAX_RETURN_PER_ROUND);
    }

    /** Participation XP for a round with the given wager (scaled with stake, capped). */
    public static long gameXp(long wager)
    {
        long w = Math.max(0, wager);
        long xp = GAME_XP_BASE + Math.round(w * GAME_XP_PER_COIN);
        return Math.max(GAME_XP_BASE, Math.min(GAME_XP_MAX, xp));
    }

    /** Rebate fraction of a net loss for a given level, before the daily cap. */
    public static double rebateFraction(int level)
    {
        double f = Math.max(0, level) * REBATE_PER_LEVEL;
        return Math.min(REBATE_CAP, f);
    }

    /**
     * Coins to rebate for a net loss at a given level, before the per-day cap
     * (which is enforced atomically in {@link EconomyStore#addRebateCapped}).
     *
     * @param netLoss the coins lost this round (positive); {@code <= 0} yields no rebate
     */
    public static long loyaltyRebate(long netLoss, int level)
    {
        if(netLoss <= 0)
            return 0;
        return (long) Math.floor(netLoss * rebateFraction(level));
    }

    /** Host-local calendar day bucket used for the daily rebate cap. */
    public static long dayKey(long nowEpochSeconds, ZoneId zone)
    {
        return Instant.ofEpochSecond(nowEpochSeconds).atZone(zone).toLocalDate().toEpochDay();
    }
}
