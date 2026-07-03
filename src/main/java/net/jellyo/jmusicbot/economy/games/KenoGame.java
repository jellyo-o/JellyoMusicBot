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

import java.util.Arrays;
import java.util.Random;
import java.util.Set;

/**
 * Keno: the player picks {@value #PICKS} numbers from 1-{@value #POOL}; the house
 * draws {@value #DRAW}. The payout scales with the number of matches — 2 hits pays
 * {@code 2x}, 3 hits {@code 8x}, and all 4 a {@code 100x} jackpot (a ~1/435 shot).
 * The overall return-to-player is ~0.97.
 *
 * <p>This is a jackpot game: its max stake is sized to the {@link #PRACTICAL_TOP_MULTIPLIER}
 * (the common high tier), and the rare all-hit jackpot is bounded by the settlement
 * return cap. Payouts here are the raw, pre-cap, stake-inclusive returns.
 */
public final class KenoGame
{
    public static final int POOL = 40;
    public static final int PICKS = 4;
    public static final int DRAW = 10;

    /** Sizing multiplier for the table limit — the common high (3-hit) tier, not the jackpot. */
    public static final double PRACTICAL_TOP_MULTIPLIER = 8.0;
    /** The rare all-hit jackpot multiplier (bounded by the settlement return cap). */
    public static final double JACKPOT_MULTIPLIER = 100.0;

    private KenoGame() {}

    /** Stake-inclusive return multiplier for a given number of hits. */
    public static double multiplierForHits(int hits)
    {
        switch(hits)
        {
            case 2: return 2.0;
            case 3: return PRACTICAL_TOP_MULTIPLIER;
            case 4: return JACKPOT_MULTIPLIER;
            default: return 0.0;
        }
    }

    public static final class Result
    {
        private final int[] drawn;
        private final int hits;
        private final double multiplier;
        private final long payout;

        Result(int[] drawn, int hits, double multiplier, long payout)
        {
            this.drawn = drawn;
            this.hits = hits;
            this.multiplier = multiplier;
            this.payout = payout;
        }

        public int[] getDrawn() { return drawn.clone(); }
        public int getHits() { return hits; }
        public double getMultiplier() { return multiplier; }
        public boolean isWon() { return payout > 0; }
        public long getPayout() { return payout; }
    }

    public static Result play(Set<Integer> picks, long wager, Random rng)
    {
        int[] drawn = drawDistinct(POOL, DRAW, rng);
        int hits = 0;
        for(int n : drawn)
            if(picks.contains(n))
                hits++;
        double multiplier = multiplierForHits(hits);
        long payout = (long) Math.floor(wager * multiplier);
        return new Result(drawn, hits, multiplier, payout);
    }

    /** Draws {@code count} distinct values in 1..{@code pool} via a partial Fisher-Yates shuffle. */
    static int[] drawDistinct(int pool, int count, Random rng)
    {
        int[] bag = new int[pool];
        for(int i = 0; i < pool; i++)
            bag[i] = i + 1;
        for(int i = 0; i < count; i++)
        {
            int j = i + rng.nextInt(pool - i);
            int tmp = bag[i];
            bag[i] = bag[j];
            bag[j] = tmp;
        }
        return Arrays.copyOf(bag, count);
    }
}
