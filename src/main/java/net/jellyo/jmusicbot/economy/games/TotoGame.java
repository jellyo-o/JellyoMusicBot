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
 * Singapore-style TOTO: pick 6 numbers from 1-{@value #MAX_NUMBER}; a draw produces 6
 * winning numbers plus 1 additional number. Prizes fall into seven groups exactly as
 * Singapore Pools defines them — G1 (6), G2 (5+additional), G3 (5), G4 (4+additional),
 * G5 (4), G6 (3+additional), G7 (3).
 *
 * <p>This class provides the draw, the group evaluation and the System/Roll expansions,
 * shared by both the instant fixed-odds {@code /toto} and the pooled pari-mutuel lottery.
 * The instant game uses {@link #multiplier} (fixed odds, ~0.9 RTP); the pool uses the
 * same group evaluation but splits a shared pot.
 */
public final class TotoGame
{
    public static final int MAX_NUMBER = 49;
    public static final int PICK = 6;
    public static final int MIN_SYSTEM = 7;
    public static final int MAX_SYSTEM = 12;

    public enum Group { G1, G2, G3, G4, G5, G6, G7, NONE }

    /** Fixed-odds stake-inclusive multipliers for the instant game (per unit combination). */
    public static long multiplier(Group group)
    {
        switch(group)
        {
            case G1: return 2_000_000;
            case G2: return 80_000;
            case G3: return 4_000;
            case G4: return 800;
            case G5: return 150;
            case G6: return 60;
            case G7: return 22;
            default: return 0;
        }
    }

    /** Practical sizing tier (the common G5); the rare top groups are bounded by the return cap. */
    public static final long PRACTICAL_TOP_MULTIPLIER = 150;

    private TotoGame() {}

    public static final class Draw
    {
        private final Set<Integer> winning;
        private final int additional;

        Draw(Set<Integer> winning, int additional)
        {
            this.winning = winning;
            this.additional = additional;
        }

        public List<Integer> getWinning() { return new ArrayList<>(winning); }
        public int getAdditional() { return additional; }
    }

    public static Draw draw(Random rng)
    {
        Set<Integer> pool = new LinkedHashSet<>();
        while(pool.size() < PICK + 1)
            pool.add(rng.nextInt(MAX_NUMBER) + 1);
        List<Integer> drawn = new ArrayList<>(pool);
        Set<Integer> winning = new LinkedHashSet<>(drawn.subList(0, PICK));
        int additional = drawn.get(PICK);
        return new Draw(winning, additional);
    }

    /** The prize group for a single 6-number combination against the draw. */
    public static Group groupOf(Set<Integer> combination, Draw draw)
    {
        int matches = 0;
        for(int n : combination)
            if(draw.winning.contains(n))
                matches++;
        boolean additional = combination.contains(draw.additional);
        switch(matches)
        {
            case 6: return Group.G1;
            case 5: return additional ? Group.G2 : Group.G3;
            case 4: return additional ? Group.G4 : Group.G5;
            case 3: return additional ? Group.G6 : Group.G7;
            default: return Group.NONE;
        }
    }

    public static long prize(Group group, long unitStake)
    {
        return multiplier(group) * Math.max(0, unitStake);
    }

    public static final class Win
    {
        private final Set<Integer> combination;
        private final Group group;
        private final long payout;

        Win(Set<Integer> combination, Group group, long payout)
        {
            this.combination = combination;
            this.group = group;
            this.payout = payout;
        }

        public Set<Integer> getCombination() { return combination; }
        public Group getGroup() { return group; }
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

    /** Settles a bet (one or more 6-combinations from a System/Ordinary entry) against a draw. */
    public static Outcome settle(List<Set<Integer>> combinations, long unitStake, Draw draw)
    {
        long totalStake = (long) combinations.size() * unitStake;
        long totalPayout = 0;
        List<Win> wins = new ArrayList<>();
        for(Set<Integer> combination : combinations)
        {
            Group group = groupOf(combination, draw);
            long payout = prize(group, unitStake);
            if(payout > 0)
            {
                totalPayout += payout;
                wins.add(new Win(combination, group, payout));
            }
        }
        return new Outcome(totalStake, totalPayout, wins);
    }

    // ---- entry expansion ---------------------------------------------------

    /** All C(n,6) six-number combinations of the picked numbers (Ordinary = 1, System 7-12). */
    public static List<Set<Integer>> combinations(List<Integer> numbers)
    {
        List<Set<Integer>> result = new ArrayList<>();
        combine(numbers, 0, new ArrayList<>(), result);
        return result;
    }

    private static void combine(List<Integer> numbers, int start, List<Integer> current, List<Set<Integer>> out)
    {
        if(current.size() == PICK)
        {
            out.add(new LinkedHashSet<>(current));
            return;
        }
        for(int i = start; i < numbers.size(); i++)
        {
            current.add(numbers.get(i));
            combine(numbers, i + 1, current, out);
            current.remove(current.size() - 1);
        }
    }

    /** System Roll: five fixed numbers, the sixth rolls over every other number (44 combinations). */
    public static List<Set<Integer>> roll(List<Integer> fiveFixed)
    {
        List<Set<Integer>> result = new ArrayList<>();
        for(int n = 1; n <= MAX_NUMBER; n++)
        {
            if(fiveFixed.contains(n))
                continue;
            Set<Integer> combo = new LinkedHashSet<>(fiveFixed);
            combo.add(n);
            result.add(combo);
        }
        return result;
    }

    /** A random pick of {@code count} distinct numbers (Quick Pick), for Ordinary or System. */
    public static List<Integer> quickPick(int count, Random rng)
    {
        Set<Integer> picks = new LinkedHashSet<>();
        while(picks.size() < count)
            picks.add(rng.nextInt(MAX_NUMBER) + 1);
        return new ArrayList<>(picks);
    }
}
