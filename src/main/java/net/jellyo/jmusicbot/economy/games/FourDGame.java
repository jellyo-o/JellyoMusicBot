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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Singapore-style 4-D, played fixed-odds against the house (which is how real 4-D
 * pays). A draw produces 23 winning numbers — 1st/2nd/3rd prize plus 10 Starter and
 * 10 Consolation numbers. A <b>Big</b> bet wins on any of the 23 tiers (smaller
 * payouts); a <b>Small</b> bet wins only on 1st/2nd/3rd (larger payouts). Payouts
 * are stake-inclusive multiples of the unit stake and carry a small house edge
 * (Big ~0.95, Small ~0.93 return-to-player). System and Roll entries are just
 * expansions to a set of numbers, each played as its own unit bet.
 */
public final class FourDGame
{
    public static final int STARTERS = 10;
    public static final int CONSOLATIONS = 10;

    public enum BetType { BIG, SMALL }

    public enum Tier { FIRST, SECOND, THIRD, STARTER, CONSOLATION, NONE }

    // Stake-inclusive payout multipliers (per unit stake). Big pays all tiers; Small only the top 3.
    private static long bigMultiplier(Tier tier)
    {
        switch(tier)
        {
            case FIRST: return 3000;
            case SECOND: return 1500;
            case THIRD: return 800;
            case STARTER: return 300;
            case CONSOLATION: return 120;
            default: return 0;
        }
    }

    private static long smallMultiplier(Tier tier)
    {
        switch(tier)
        {
            case FIRST: return 5000;
            case SECOND: return 2800;
            case THIRD: return 1500;
            default: return 0; // Small does not win Starter/Consolation
        }
    }

    /** The top multiplier a single unit can pay (Small 1st prize). */
    public static final long TOP_MULTIPLIER = 5000;
    /** Practical sizing tier for the unit-stake table limit; rare top prizes are return-capped. */
    public static final long PRACTICAL_TOP_MULTIPLIER = 300;

    private FourDGame() {}

    public static final class Draw
    {
        private final int first;
        private final int second;
        private final int third;
        private final Set<Integer> starters;
        private final Set<Integer> consolations;

        Draw(int first, int second, int third, Set<Integer> starters, Set<Integer> consolations)
        {
            this.first = first;
            this.second = second;
            this.third = third;
            this.starters = starters;
            this.consolations = consolations;
        }

        public int getFirst() { return first; }
        public int getSecond() { return second; }
        public int getThird() { return third; }
        public List<Integer> getStarters() { return new ArrayList<>(starters); }
        public List<Integer> getConsolations() { return new ArrayList<>(consolations); }
    }

    public static Draw draw(Random rng)
    {
        Set<Integer> used = new LinkedHashSet<>();
        while(used.size() < 3 + STARTERS + CONSOLATIONS)
            used.add(rng.nextInt(10000));
        List<Integer> all = new ArrayList<>(used);
        int first = all.get(0);
        int second = all.get(1);
        int third = all.get(2);
        Set<Integer> starters = new LinkedHashSet<>(all.subList(3, 3 + STARTERS));
        Set<Integer> consolations = new LinkedHashSet<>(all.subList(3 + STARTERS, 3 + STARTERS + CONSOLATIONS));
        return new Draw(first, second, third, starters, consolations);
    }

    public static Tier tierOf(int number, Draw draw)
    {
        if(number == draw.first) return Tier.FIRST;
        if(number == draw.second) return Tier.SECOND;
        if(number == draw.third) return Tier.THIRD;
        if(draw.starters.contains(number)) return Tier.STARTER;
        if(draw.consolations.contains(number)) return Tier.CONSOLATION;
        return Tier.NONE;
    }

    /** Stake-inclusive payout for one number matching {@code tier} under {@code betType}. */
    public static long prize(Tier tier, BetType betType, long unitStake)
    {
        long multiplier = betType == BetType.SMALL ? smallMultiplier(tier) : bigMultiplier(tier);
        return multiplier * Math.max(0, unitStake);
    }

    public static final class Win
    {
        private final int number;
        private final Tier tier;
        private final long payout;

        Win(int number, Tier tier, long payout)
        {
            this.number = number;
            this.tier = tier;
            this.payout = payout;
        }

        public int getNumber() { return number; }
        public Tier getTier() { return tier; }
        public long getPayout() { return payout; }
    }

    public static final class Outcome
    {
        private final long totalStake;
        private final long totalPayout;
        private final List<Win> wins;

        Outcome(long totalStake, long totalPayout, List<Win> wins)
        {
            this.totalStake = totalStake;
            this.totalPayout = totalPayout;
            this.wins = wins;
        }

        public long getTotalStake() { return totalStake; }
        public long getTotalPayout() { return totalPayout; }
        public List<Win> getWins() { return wins; }
        public boolean isWon() { return totalPayout > 0; }
    }

    /** Settles a bet over one or more numbers (system/roll expansions) against a single draw. */
    public static Outcome settle(Set<Integer> numbers, BetType betType, long unitStake, Draw draw)
    {
        long totalStake = (long) numbers.size() * unitStake;
        long totalPayout = 0;
        List<Win> wins = new ArrayList<>();
        for(int number : numbers)
        {
            Tier tier = tierOf(number, draw);
            long payout = prize(tier, betType, unitStake);
            if(payout > 0)
            {
                totalPayout += payout;
                wins.add(new Win(number, tier, payout));
            }
        }
        return new Outcome(totalStake, totalPayout, wins);
    }

    // ---- system / roll expansions ------------------------------------------

    /** All distinct 4-digit permutations of the given four digits (System 24/12/6/4/1). */
    public static Set<Integer> permutations(int[] digits)
    {
        Set<Integer> result = new LinkedHashSet<>();
        permute(digits, 0, result);
        return result;
    }

    private static void permute(int[] digits, int index, Set<Integer> out)
    {
        if(index == digits.length)
        {
            out.add(digits[0] * 1000 + digits[1] * 100 + digits[2] * 10 + digits[3]);
            return;
        }
        for(int i = index; i < digits.length; i++)
        {
            swap(digits, index, i);
            permute(digits, index + 1, out);
            swap(digits, index, i);
        }
    }

    private static void swap(int[] a, int i, int j)
    {
        int tmp = a[i];
        a[i] = a[j];
        a[j] = tmp;
    }

    /**
     * The 10 numbers of a 4-D Roll: three fixed digits with one rolling position.
     *
     * @param fixed  the four digits with the roll position ignored
     * @param rollAt the index (0-3) that rolls 0-9
     */
    public static Set<Integer> roll(int[] fixed, int rollAt)
    {
        Set<Integer> result = new LinkedHashSet<>();
        int[] work = fixed.clone();
        for(int d = 0; d <= 9; d++)
        {
            work[rollAt] = d;
            result.add(work[0] * 1000 + work[1] * 100 + work[2] * 10 + work[3]);
        }
        return result;
    }

    public static String format(int number)
    {
        return String.format("%04d", number);
    }
}
