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

import java.util.List;
import java.util.Random;

/**
 * Pure blackjack helpers over an infinite shoe (ranks 1-13, drawn uniformly). Hand
 * value counts aces as 11 where it does not bust, else 1. A two-card 21 is a
 * natural blackjack (pays 3:2 = {@code 2.5x}); other wins pay {@code 2x}; the
 * dealer stands on all 17s. The dealer play-out is deterministic given the {@link Random}.
 */
public final class BlackjackGame
{
    public static final double WIN_MULTIPLIER = 2.0;      // even money, stake-inclusive
    public static final double BLACKJACK_MULTIPLIER = 2.5; // 3:2, stake-inclusive
    private static final int DEALER_STANDS_ON = 17;

    private BlackjackGame() {}

    /** Draws a rank 1-13. */
    public static int draw(Random rng)
    {
        return rng.nextInt(13) + 1;
    }

    /** Blackjack value of a rank: face cards are 10, aces are counted as 11 by {@link #bestValue}. */
    public static int cardValue(int rank)
    {
        return Math.min(rank, 10);
    }

    /** Best (non-busting where possible) total of a hand. */
    public static int bestValue(List<Integer> ranks)
    {
        int total = 0;
        int aces = 0;
        for(int rank : ranks)
        {
            total += cardValue(rank);
            if(rank == 1)
                aces++;
        }
        // Each ace already counted as 1; upgrade one to 11 (add 10) while it fits.
        int soft = total;
        int usable = aces;
        while(usable > 0 && soft + 10 <= 21)
        {
            soft += 10;
            usable--;
        }
        return soft;
    }

    public static boolean isBust(List<Integer> ranks)
    {
        return bestValue(ranks) > 21;
    }

    public static boolean isBlackjack(List<Integer> ranks)
    {
        return ranks.size() == 2 && bestValue(ranks) == 21;
    }

    /** True once the dealer must stop drawing (17+). */
    public static boolean dealerStands(List<Integer> ranks)
    {
        return bestValue(ranks) >= DEALER_STANDS_ON;
    }
}
