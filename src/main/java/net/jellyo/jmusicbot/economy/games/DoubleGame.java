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
package com.jagrosh.jmusicbot.economy.games;

/**
 * "Double or Nothing": bank a win, then keep flipping to double it. The win chance
 * <b>decays</b> each step and there is a hard streak cap, so every double is
 * negative-EV and increasingly so — chaining up to a fortune is exponentially
 * unlikely, not "easy".
 */
public final class DoubleGame
{
    /** Hard cap on consecutive doubles (also caps the reachable multiplier at 2^cap). */
    public static final int MAX_STREAK = 6;
    static final double BASE_WIN = 0.49;
    static final double DECAY = 0.02;

    private DoubleGame() {}

    /** Win chance for the flip at {@code step} (0 = the very first flip). */
    public static double winChance(int step)
    {
        return Math.max(0.05, BASE_WIN - DECAY * Math.max(0, step));
    }

    /** Stake-inclusive pot after {@code wins} successful doubles: {@code wager * 2^wins}. */
    public static long potFor(long wager, int wins)
    {
        return wager << Math.min(Math.max(0, wins), MAX_STREAK);
    }

    /** The largest multiplier reachable (used to size the table limit). */
    public static double topMultiplier()
    {
        return Math.pow(2, MAX_STREAK);
    }

    /** Whether another double is allowed after {@code wins} wins. */
    public static boolean canContinue(int wins)
    {
        return wins < MAX_STREAK;
    }
}
