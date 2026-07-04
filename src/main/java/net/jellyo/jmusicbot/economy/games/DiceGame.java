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

import java.util.Locale;
import java.util.Random;

/**
 * "Dice Predict": the player predicts the roll of a single six-sided die. An exact
 * number (1-6) pays {@code ~5.7x} (a 1/6 shot), while the even-money guesses
 * (even/odd, high 4-6, low 1-3) pay {@code ~1.9x}. All payouts are stake-inclusive
 * and carry a small house edge (~5%). Pure and deterministic given the {@link Random}.
 */
public final class DiceGame
{
    /** 1/6 chance -> pays a little under the fair 6x. */
    public static final double EXACT_MULTIPLIER = 5.7;
    /** ~1/2 chance -> pays a little under the fair 2x. */
    public static final double EVEN_MONEY_MULTIPLIER = 1.9;

    private DiceGame() {}

    public enum Mode
    {
        EXACT, EVEN, ODD, HIGH, LOW;

        public static Mode from(String value)
        {
            if(value == null)
                return EXACT;
            switch(value.trim().toLowerCase(Locale.ROOT))
            {
                case "even": return EVEN;
                case "odd": return ODD;
                case "high": return HIGH;
                case "low": return LOW;
                default: return EXACT;
            }
        }

        /** The stake-inclusive multiplier a win on this mode pays. */
        public double multiplier()
        {
            return this == EXACT ? EXACT_MULTIPLIER : EVEN_MONEY_MULTIPLIER;
        }
    }

    public static final class Result
    {
        private final int roll;
        private final boolean won;
        private final long payout;

        Result(int roll, boolean won, long payout)
        {
            this.roll = roll;
            this.won = won;
            this.payout = payout;
        }

        public int getRoll() { return roll; }
        public boolean isWon() { return won; }
        /** Total coins returned (0 on a loss, stake-inclusive on a win). */
        public long getPayout() { return payout; }
    }

    /**
     * Rolls the die and settles the prediction.
     *
     * @param mode   the kind of prediction
     * @param target for {@link Mode#EXACT}, the guessed face (1-6); ignored otherwise
     */
    public static Result play(Mode mode, int target, long wager, Random rng)
    {
        int roll = rng.nextInt(6) + 1;
        boolean won = isWin(mode, target, roll);
        long payout = won ? (long) Math.floor(wager * mode.multiplier()) : 0;
        return new Result(roll, won, payout);
    }

    static boolean isWin(Mode mode, int target, int roll)
    {
        switch(mode)
        {
            case EXACT: return roll == target;
            case EVEN: return roll % 2 == 0;
            case ODD: return roll % 2 == 1;
            case HIGH: return roll >= 4;
            case LOW: return roll <= 3;
            default: return false;
        }
    }
}
