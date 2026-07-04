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
package com.jagrosh.jmusicbot.economy;

import java.util.Locale;
import java.util.Random;

/**
 * Pure gambling game logic. Each game takes a wager and a {@link Random} and
 * returns the total payout (0 on a loss) plus a human-readable description. The
 * payout maths are deterministic given the rolls, so they can be unit tested in
 * isolation; the small house edges keep the currency from inflating without
 * feeling rigged.
 */
public final class GambleGames
{
    /** Coinflip pays slightly under 2x to give the house a ~2.5% edge. */
    private static final double COINFLIP_MULTIPLIER = 1.95;
    /** Dice: ties go to the player and the payout is tuned for a ~1% edge. */
    private static final double DICE_MULTIPLIER = 1.7;
    /** Any matching pair pays 1.3x, tuned with the triples for a ~7% slots edge. */
    private static final double SLOTS_PAIR_MULTIPLIER = 1.3;

    private GambleGames() {}

    public enum Game
    {
        COINFLIP,
        DICE,
        SLOTS;

        public static Game from(String value)
        {
            if(value == null || value.isBlank())
                return COINFLIP;
            switch(value.trim().toLowerCase(Locale.ROOT))
            {
                case "dice":
                case "roll":
                    return DICE;
                case "slots":
                case "slot":
                case "spin":
                    return SLOTS;
                case "coinflip":
                case "coin":
                case "flip":
                case "cf":
                    return COINFLIP;
                default:
                    return COINFLIP;
            }
        }
    }

    /** Slot reel symbols, with spin weight and the multiplier for three of a kind. */
    public enum Symbol
    {
        CHERRY("🍒", 30, 4),
        LEMON("🍋", 28, 4),
        BELL("🔔", 18, 8),
        STAR("⭐", 12, 12),
        DIAMOND("💎", 8, 20),
        SEVEN("7️⃣", 4, 40);

        private final String emoji;
        private final int weight;
        private final int tripleMultiplier;

        Symbol(String emoji, int weight, int tripleMultiplier)
        {
            this.emoji = emoji;
            this.weight = weight;
            this.tripleMultiplier = tripleMultiplier;
        }

        public String getEmoji() { return emoji; }
        public int getWeight() { return weight; }
        public int getTripleMultiplier() { return tripleMultiplier; }
    }

    public static final class Result
    {
        private final boolean won;
        private final long payout;
        private final String detail;

        public Result(boolean won, long payout, String detail)
        {
            this.won = won;
            this.payout = payout;
            this.detail = detail;
        }

        public boolean isWon() { return won; }
        /** Total coins returned to the player (0 on a loss, includes the stake on a win). */
        public long getPayout() { return payout; }
        public String getDetail() { return detail; }
    }

    public static Result play(Game game, long wager, Random rng)
    {
        switch(game == null ? Game.COINFLIP : game)
        {
            case DICE:
                return dice(wager, rng);
            case SLOTS:
                return slots(wager, rng);
            case COINFLIP:
            default:
                return coinflip(wager, rng);
        }
    }

    static Result coinflip(long wager, Random rng)
    {
        boolean heads = rng.nextBoolean();
        if(heads)
        {
            long payout = (long) Math.floor(wager * COINFLIP_MULTIPLIER);
            return new Result(true, payout, "🪙 The coin landed on **heads** — you win!");
        }
        return new Result(false, 0, "🪙 The coin landed on **tails** — you lose.");
    }

    static Result dice(long wager, Random rng)
    {
        int player = rng.nextInt(6) + 1;
        int house = rng.nextInt(6) + 1;
        boolean won = player >= house; // ties go to the player
        long payout = won ? (long) Math.floor(wager * DICE_MULTIPLIER) : 0;
        String detail = "🎲 You rolled **" + player + "**, the house rolled **" + house + "** — "
                + (won ? "you win!" : "you lose.");
        return new Result(won, payout, detail);
    }

    static Result slots(long wager, Random rng)
    {
        Symbol[] reels = spin(rng);
        double multiplier = slotMultiplier(reels);
        long payout = (long) Math.floor(wager * multiplier);
        String row = reels[0].getEmoji() + " " + reels[1].getEmoji() + " " + reels[2].getEmoji();
        String detail;
        if(multiplier >= 3)
            detail = "🎰 " + row + " — **JACKPOT!**";
        else if(multiplier > 1)
            detail = "🎰 " + row + " — a pair! You win!";
        else
            detail = "🎰 " + row + " — no match. You lose.";
        return new Result(payout > 0, payout, detail);
    }

    static Symbol[] spin(Random rng)
    {
        return new Symbol[] { spinReel(rng), spinReel(rng), spinReel(rng) };
    }

    private static Symbol spinReel(Random rng)
    {
        int total = 0;
        for(Symbol s : Symbol.values())
            total += s.getWeight();
        int roll = rng.nextInt(total);
        for(Symbol s : Symbol.values())
        {
            roll -= s.getWeight();
            if(roll < 0)
                return s;
        }
        return Symbol.CHERRY;
    }

    /** Multiplier for a given set of reels: triple &rarr; symbol multiplier, pair &rarr; 1.5x, else 0. */
    static double slotMultiplier(Symbol[] reels)
    {
        if(reels[0] == reels[1] && reels[1] == reels[2])
            return reels[0].getTripleMultiplier();
        if(reels[0] == reels[1] || reels[1] == reels[2] || reels[0] == reels[2])
            return SLOTS_PAIR_MULTIPLIER;
        return 0;
    }
}
