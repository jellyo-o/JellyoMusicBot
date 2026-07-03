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
 * "Wheel of Fortune": a single spin lands on a weighted multiplier segment. Roughly
 * half the wheel loses (0x); the rest pays from 1.2x up to a 25x jackpot. Weighted
 * for a ~2.5% house edge. Multipliers are stake-inclusive returns. Pure and
 * deterministic given the {@link Random}.
 */
public final class WheelGame
{
    /** Segments in wheel order, each a stake-inclusive return multiplier with a spin weight. */
    public enum Segment
    {
        LOSE(0.0, 100),
        LOW(1.2, 50),
        MID(1.5, 24),
        DOUBLE(2.0, 16),
        TRIPLE(3.0, 6),
        BIG(8.0, 3),
        JACKPOT(25.0, 1);

        private final double multiplier;
        private final int weight;

        Segment(double multiplier, int weight)
        {
            this.multiplier = multiplier;
            this.weight = weight;
        }

        public double getMultiplier() { return multiplier; }
        public int getWeight() { return weight; }
    }

    /** The largest multiplier the wheel can pay (used to size the table limit). */
    public static final double TOP_MULTIPLIER = Segment.JACKPOT.getMultiplier();

    private WheelGame() {}

    public static final class Result
    {
        private final Segment segment;
        private final long payout;

        Result(Segment segment, long payout)
        {
            this.segment = segment;
            this.payout = payout;
        }

        public Segment getSegment() { return segment; }
        public double getMultiplier() { return segment.getMultiplier(); }
        public boolean isWon() { return payout > 0; }
        public long getPayout() { return payout; }
    }

    public static Result play(long wager, Random rng)
    {
        Segment segment = spin(rng);
        long payout = (long) Math.floor(wager * segment.getMultiplier());
        return new Result(segment, payout);
    }

    static Segment spin(Random rng)
    {
        int total = 0;
        for(Segment s : Segment.values())
            total += s.getWeight();
        int roll = rng.nextInt(total);
        for(Segment s : Segment.values())
        {
            roll -= s.getWeight();
            if(roll < 0)
                return s;
        }
        return Segment.LOSE;
    }
}
