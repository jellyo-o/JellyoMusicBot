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
import java.util.Set;

/**
 * European single-zero roulette (numbers 0-36). Every bet type carries the same
 * ~2.7% house edge: even-money bets (red/black, even/odd, low/high) pay {@code 2x},
 * dozens pay {@code 3x}, and a straight-up number pays {@code 36x} (the classic
 * 35:1). Payouts are stake-inclusive. Pure and deterministic given the {@link Random}.
 */
public final class RouletteGame
{
    /** European red numbers. */
    private static final Set<Integer> RED = Set.of(
            1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);

    public static final double EVEN_MONEY_MULTIPLIER = 2.0;
    public static final double DOZEN_MULTIPLIER = 3.0;
    public static final double STRAIGHT_MULTIPLIER = 36.0;

    private RouletteGame() {}

    public enum Color { RED, BLACK, GREEN }

    public enum BetType
    {
        RED, BLACK, EVEN, ODD, LOW, HIGH, DOZEN1, DOZEN2, DOZEN3, STRAIGHT;

        public static BetType from(String value)
        {
            if(value == null)
                return RED;
            switch(value.trim().toLowerCase(Locale.ROOT))
            {
                case "black": return BLACK;
                case "even": return EVEN;
                case "odd": return ODD;
                case "low": case "1-18": return LOW;
                case "high": case "19-36": return HIGH;
                case "dozen1": case "1st12": case "d1": return DOZEN1;
                case "dozen2": case "2nd12": case "d2": return DOZEN2;
                case "dozen3": case "3rd12": case "d3": return DOZEN3;
                case "number": case "straight": return STRAIGHT;
                default: return RED;
            }
        }

        public double multiplier()
        {
            switch(this)
            {
                case DOZEN1: case DOZEN2: case DOZEN3: return DOZEN_MULTIPLIER;
                case STRAIGHT: return STRAIGHT_MULTIPLIER;
                default: return EVEN_MONEY_MULTIPLIER;
            }
        }
    }

    public static Color colorOf(int number)
    {
        if(number == 0)
            return Color.GREEN;
        return RED.contains(number) ? Color.RED : Color.BLACK;
    }

    public static final class Result
    {
        private final int number;
        private final Color color;
        private final boolean won;
        private final long payout;

        Result(int number, Color color, boolean won, long payout)
        {
            this.number = number;
            this.color = color;
            this.won = won;
            this.payout = payout;
        }

        public int getNumber() { return number; }
        public Color getColor() { return color; }
        public boolean isWon() { return won; }
        public long getPayout() { return payout; }
    }

    /**
     * Spins the wheel and settles the bet.
     *
     * @param straightTarget the chosen number for a {@link BetType#STRAIGHT} bet (0-36); ignored otherwise
     */
    public static Result play(BetType bet, int straightTarget, long wager, Random rng)
    {
        int number = rng.nextInt(37); // 0..36
        boolean won = isWin(bet, straightTarget, number);
        long payout = won ? (long) Math.floor(wager * bet.multiplier()) : 0;
        return new Result(number, colorOf(number), won, payout);
    }

    static boolean isWin(BetType bet, int straightTarget, int number)
    {
        if(number == 0)
            return bet == BetType.STRAIGHT && straightTarget == 0;
        switch(bet)
        {
            case RED: return colorOf(number) == Color.RED;
            case BLACK: return colorOf(number) == Color.BLACK;
            case EVEN: return number % 2 == 0;
            case ODD: return number % 2 == 1;
            case LOW: return number >= 1 && number <= 18;
            case HIGH: return number >= 19 && number <= 36;
            case DOZEN1: return number >= 1 && number <= 12;
            case DOZEN2: return number >= 13 && number <= 24;
            case DOZEN3: return number >= 25 && number <= 36;
            case STRAIGHT: return number == straightTarget;
            default: return false;
        }
    }
}
