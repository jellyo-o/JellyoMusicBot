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
package com.jagrosh.jmusicbot.achievements;

import com.jagrosh.jmusicbot.economy.EconomyEvent;
import com.jagrosh.jmusicbot.economy.UserProfile;
import java.util.Optional;

/**
 * The catalogue of global, per-user achievements. Each one is earned exactly
 * once and grants bonus currency and XP the moment it unlocks. Most are simple
 * lifetime thresholds derived from the {@link UserProfile}; a few are
 * event-driven (e.g. {@link #NIGHT_OWL}, which checks the hour of the activity).
 *
 * <p>The {@link #getId()} string is the stable database key — never change it
 * for an existing achievement, even if the enum constant is renamed.
 */
public enum Achievement
{
    // ---- requesting --------------------------------------------------------
    FIRST_SONG("first_song", "First Request", "🎵", Category.REQUESTING,
            "Request your very first song", 50, 25,
            (p, e, h) -> p.getSongsRequested() >= 1),
    SONGS_10("songs_10", "Selector", "🎶", Category.REQUESTING,
            "Request 10 songs", 75, 40,
            (p, e, h) -> p.getSongsRequested() >= 10),
    SONGS_50("songs_50", "Tastemaker", "📀", Category.REQUESTING,
            "Request 50 songs", 150, 100,
            (p, e, h) -> p.getSongsRequested() >= 50),
    SONGS_250("songs_250", "Resident DJ", "🎧", Category.REQUESTING,
            "Request 250 songs", 400, 250,
            (p, e, h) -> p.getSongsRequested() >= 250),
    SONGS_1000("songs_1000", "Music Librarian", "📚", Category.REQUESTING,
            "Request 1,000 songs", 1500, 800,
            (p, e, h) -> p.getSongsRequested() >= 1000),

    // ---- listening ---------------------------------------------------------
    LISTEN_60("listen_60", "Warmed Up", "🔥", Category.LISTENING,
            "Listen for 1 hour in total", 100, 60,
            (p, e, h) -> p.getMinutesListened() >= 60),
    LISTEN_600("listen_600", "Marathoner", "🏃", Category.LISTENING,
            "Listen for 10 hours in total", 300, 200,
            (p, e, h) -> p.getMinutesListened() >= 600),
    LISTEN_3000("listen_3000", "Audiophile", "🎚️", Category.LISTENING,
            "Listen for 50 hours in total", 1000, 600,
            (p, e, h) -> p.getMinutesListened() >= 3000),
    NIGHT_OWL("night_owl", "Night Owl", "🦉", Category.LISTENING,
            "Be listening at 3 AM", 200, 100,
            (p, e, h) -> h == 3 && (e == EconomyEvent.LISTENED || e == EconomyEvent.SONG_REQUESTED)),

    // ---- levels ------------------------------------------------------------
    LEVEL_5("level_5", "Regular", "⭐", Category.LEVELS,
            "Reach level 5", 100, 0,
            (p, e, h) -> p.getLevel() >= 5),
    LEVEL_10("level_10", "Veteran", "🌟", Category.LEVELS,
            "Reach level 10", 250, 0,
            (p, e, h) -> p.getLevel() >= 10),
    LEVEL_25("level_25", "Legend", "💫", Category.LEVELS,
            "Reach level 25", 1000, 0,
            (p, e, h) -> p.getLevel() >= 25),
    LEVEL_50("level_50", "Mythic", "🌌", Category.LEVELS,
            "Reach level 50", 3000, 0,
            (p, e, h) -> p.getLevel() >= 50),

    // ---- daily chest -------------------------------------------------------
    FIRST_DAILY("first_daily", "Creature of Habit", "📅", Category.DAILY,
            "Claim your first daily chest", 50, 25,
            (p, e, h) -> p.getDailyStreak() >= 1),
    STREAK_7("streak_7", "Weekly Ritual", "🗓️", Category.DAILY,
            "Reach a 7-day daily streak", 300, 150,
            (p, e, h) -> p.getDailyStreak() >= 7),
    STREAK_30("streak_30", "Unbroken", "📆", Category.DAILY,
            "Reach a 30-day daily streak", 1500, 800,
            (p, e, h) -> p.getDailyStreak() >= 30),

    // ---- guess the song ----------------------------------------------------
    FIRST_GUESS("first_guess", "Good Ear", "👂", Category.GUESS,
            "Guess a song correctly for the first time", 50, 30,
            (p, e, h) -> p.getGuessesCorrect() >= 1),
    GUESS_50("guess_50", "Sharp Ear", "🎯", Category.GUESS,
            "Guess 50 songs correctly", 300, 200,
            (p, e, h) -> p.getGuessesCorrect() >= 50),
    GUESS_250("guess_250", "Shazam Killer", "🪄", Category.GUESS,
            "Guess 250 songs correctly", 1000, 600,
            (p, e, h) -> p.getGuessesCorrect() >= 250),
    FIRST_WIN("first_win", "Champion", "🏅", Category.GUESS,
            "Win a guess-the-song game", 100, 60,
            (p, e, h) -> p.getGuessWins() >= 1),
    WINS_10("wins_10", "Maestro", "👑", Category.GUESS,
            "Win 10 guess-the-song games", 500, 300,
            (p, e, h) -> p.getGuessWins() >= 10),

    // ---- gambling ----------------------------------------------------------
    FIRST_BET("first_bet", "Risk Taker", "🎲", Category.GAMBLING,
            "Place your first bet", 25, 15,
            (p, e, h) -> (p.getGambleWins() + p.getGambleLosses()) >= 1),
    GAMBLE_WINS_10("gamble_wins_10", "Lucky", "🍀", Category.GAMBLING,
            "Win 10 gambles", 200, 120,
            (p, e, h) -> p.getGambleWins() >= 10),
    BIG_SPENDER("big_spender", "Big Spender", "💸", Category.GAMBLING,
            "Wager 10,000 coins in total", 250, 150,
            (p, e, h) -> p.getGambleWagered() >= 10000),
    LOADED("loaded", "Loaded", "💰", Category.GAMBLING,
            "Hold 10,000 coins at once", 500, 250,
            (p, e, h) -> p.getCurrency() >= 10000),
    TYCOON("tycoon", "Tycoon", "🏦", Category.GAMBLING,
            "Hold 50,000 coins at once", 2000, 1000,
            (p, e, h) -> p.getCurrency() >= 50000);

    /** How an achievement is earned. */
    @FunctionalInterface
    public interface Check
    {
        boolean earned(UserProfile profile, EconomyEvent event, int localHour);
    }

    public enum Category
    {
        REQUESTING("Requesting"),
        LISTENING("Listening"),
        LEVELS("Levels"),
        DAILY("Daily Chest"),
        GUESS("Guess the Song"),
        GAMBLING("Coins & Gambling");

        private final String label;

        Category(String label) { this.label = label; }

        public String getLabel() { return label; }
    }

    private final String id;
    private final String displayName;
    private final String emoji;
    private final Category category;
    private final String description;
    private final long coinReward;
    private final long xpReward;
    private final Check check;

    Achievement(String id, String displayName, String emoji, Category category, String description,
                long coinReward, long xpReward, Check check)
    {
        this.id = id;
        this.displayName = displayName;
        this.emoji = emoji;
        this.category = category;
        this.description = description;
        this.coinReward = coinReward;
        this.xpReward = xpReward;
        this.check = check;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getEmoji() { return emoji; }
    public Category getCategory() { return category; }
    public String getDescription() { return description; }
    public long getCoinReward() { return coinReward; }
    public long getXpReward() { return xpReward; }

    public boolean isEarned(UserProfile profile, EconomyEvent event, int localHour)
    {
        try
        {
            return check.earned(profile, event, localHour);
        }
        catch(RuntimeException ex)
        {
            return false;
        }
    }

    public static Optional<Achievement> byId(String id)
    {
        if(id == null)
            return Optional.empty();
        for(Achievement a : values())
            if(a.id.equals(id))
                return Optional.of(a);
        return Optional.empty();
    }
}
