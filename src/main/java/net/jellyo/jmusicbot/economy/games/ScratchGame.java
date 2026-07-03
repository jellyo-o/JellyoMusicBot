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
 * A nine-cell scratch card. Each cell is either the lucky 💎 (~18% chance) or a
 * filler symbol; the payout scales with how many 💎 turn up: 3 pay {@code 2x},
 * 4 pay {@code 6x}, 5 pay {@code 20x} and 6+ a {@code 50x} jackpot. Overall RTP
 * ~0.9. Payouts are the raw, pre-cap, stake-inclusive returns.
 */
public final class ScratchGame
{
    public static final int CELLS = 9;
    private static final int LUCKY_PERCENT = 18;
    private static final String LUCKY = "💎";
    private static final String[] FILLERS = {"🍒", "🍋", "🔔", "🍀", "🪙"};

    /** Sizing multiplier for the table limit — the common high (4-lucky) tier. */
    public static final double PRACTICAL_TOP_MULTIPLIER = 6.0;
    /** The rare 6+ jackpot multiplier (bounded by the settlement return cap). */
    public static final double JACKPOT_MULTIPLIER = 50.0;

    private ScratchGame() {}

    /** Stake-inclusive return multiplier for a given count of 💎. */
    public static double multiplierForLucky(int lucky)
    {
        if(lucky >= 6) return JACKPOT_MULTIPLIER;
        if(lucky == 5) return 20.0;
        if(lucky == 4) return PRACTICAL_TOP_MULTIPLIER;
        if(lucky == 3) return 2.0;
        return 0.0;
    }

    public static final class Result
    {
        private final String[] grid;
        private final int lucky;
        private final double multiplier;
        private final long payout;

        Result(String[] grid, int lucky, double multiplier, long payout)
        {
            this.grid = grid;
            this.lucky = lucky;
            this.multiplier = multiplier;
            this.payout = payout;
        }

        public String[] getGrid() { return grid.clone(); }
        public int getLuckyCount() { return lucky; }
        public double getMultiplier() { return multiplier; }
        public boolean isWon() { return payout > 0; }
        public long getPayout() { return payout; }
    }

    public static Result play(long wager, Random rng)
    {
        String[] grid = new String[CELLS];
        int lucky = 0;
        for(int i = 0; i < CELLS; i++)
        {
            int r = rng.nextInt(100);
            if(r < LUCKY_PERCENT)
            {
                grid[i] = LUCKY;
                lucky++;
            }
            else
            {
                grid[i] = FILLERS[r % FILLERS.length];
            }
        }
        double multiplier = multiplierForLucky(lucky);
        long payout = (long) Math.floor(wager * multiplier);
        return new Result(grid, lucky, multiplier, payout);
    }
}
