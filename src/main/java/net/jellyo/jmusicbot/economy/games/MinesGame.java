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
 * "Mines": a grid of {@value #TILES} tiles hides {@code bombs} mines. Each safe tile
 * revealed grows the cash-out multiplier; hitting a mine ends the round with nothing.
 * The multiplier is the fair inverse-probability of the reveals so far, less a small
 * house edge. Bomb placement is deterministic given the {@link Random}.
 */
public final class MinesGame
{
    public static final int TILES = 20; // rendered as a 5x4 grid (leaves a row for Cash Out)
    public static final int DEFAULT_BOMBS = 3;
    public static final int MIN_BOMBS = 1;
    public static final int MAX_BOMBS = 10;
    static final double EDGE = 0.02;

    private MinesGame() {}

    /** Places {@code bombs} distinct mines among the {@value #TILES} tiles. */
    public static boolean[] placeBombs(int bombs, Random rng)
    {
        boolean[] mine = new boolean[TILES];
        int placed = 0;
        while(placed < bombs)
        {
            int i = rng.nextInt(TILES);
            if(!mine[i])
            {
                mine[i] = true;
                placed++;
            }
        }
        return mine;
    }

    /** Stake-inclusive cash-out multiplier after revealing {@code revealed} safe tiles. */
    public static double multiplier(int bombs, int revealed)
    {
        double survivalProbability = 1.0;
        for(int i = 0; i < revealed; i++)
        {
            int safeLeft = (TILES - bombs) - i;
            int tilesLeft = TILES - i;
            if(safeLeft <= 0)
                return 0;
            survivalProbability *= (double) safeLeft / tilesLeft;
        }
        return (1.0 - EDGE) / survivalProbability;
    }

    /** The number of safe tiles for a given bomb count (revealing them all is the max win). */
    public static int safeTiles(int bombs)
    {
        return TILES - bombs;
    }
}
