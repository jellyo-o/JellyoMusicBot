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

import java.util.Random;

/**
 * "Crash": a multiplier climbs from 1.00x until it randomly crashes. The crash
 * point is fixed at the start of the round; you win if you cash out at or below it.
 * The distribution gives every cash-out target the same ~3% house edge, with a ~3%
 * chance of an instant crash at 1.00x. Deterministic given the {@link Random}.
 */
public final class CrashGame
{
    static final double EDGE = 0.03;
    /** Ceiling on the crash point, and therefore on any cash-out target. */
    public static final double MAX_MULTIPLIER = 100.0;

    private CrashGame() {}

    /** Rolls the (hidden) crash point for a round; always in [1.00, {@link #MAX_MULTIPLIER}]. */
    public static double crashPoint(Random rng)
    {
        double u = rng.nextDouble(); // [0,1)
        double m = (1.0 - EDGE) / (1.0 - u);
        if(m < 1.0)
            m = 1.0; // instant crash (~EDGE probability)
        return Math.min(m, MAX_MULTIPLIER);
    }

    /** Payout for cashing out at {@code target} given the round's {@code crashPoint} (stake-inclusive). */
    public static long payout(long wager, double target, double crashPoint)
    {
        return crashPoint >= target ? (long) Math.floor(wager * target) : 0;
    }
}
