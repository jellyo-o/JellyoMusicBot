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
 * "Higher or Lower": guess whether the next card (rank 1-13, drawn uniformly) is
 * higher or lower than the current one. Each correct call multiplies the pot by the
 * fair inverse-probability of the call, less a ~3% edge, so every step returns ~0.97
 * — cash out any time before a wrong guess. A tie counts as a loss.
 */
public final class HiLoGame
{
    public static final int MAX_STREAK = 10;
    static final double EDGE = 0.03;

    private HiLoGame() {}

    public static int draw(Random rng)
    {
        return rng.nextInt(13) + 1;
    }

    public static double higherChance(int card)
    {
        return (13.0 - card) / 13.0;
    }

    public static double lowerChance(int card)
    {
        return (card - 1) / 13.0;
    }

    /** Whether "higher" is even possible on this card (false only for a King). */
    public static boolean higherPossible(int card)
    {
        return card < 13;
    }

    /** Whether "lower" is even possible on this card (false only for an Ace). */
    public static boolean lowerPossible(int card)
    {
        return card > 1;
    }

    /** Pot multiplier for a correct guess of the given direction on {@code card}. */
    public static double stepMultiplier(int card, boolean higher)
    {
        double p = higher ? higherChance(card) : lowerChance(card);
        if(p <= 0)
            return 0;
        return (1.0 - EDGE) / p;
    }

    public static boolean correct(int card, int next, boolean higher)
    {
        return higher ? next > card : next < card;
    }
}
