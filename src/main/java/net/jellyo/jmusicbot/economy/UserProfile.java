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

/**
 * Immutable snapshot of a user's global economy profile, keyed by Discord
 * snowflake. The profile is intentionally guild-agnostic: a single row tracks a
 * user across every server, and only display fields (username / avatar) are
 * mutable caches refreshed on each interaction.
 */
public final class UserProfile
{
    private final long userId;
    private final long currency;
    private final long xp;
    private final long songsRequested;
    private final long msListened;
    private final long guessesCorrect;
    private final long guessWins;
    private final long gamesPlayed;
    private final long gambleWins;
    private final long gambleLosses;
    private final long gambleWagered;
    private final long gambleNet;
    private final long biggestWin;
    private final int dailyStreak;
    private final long lastDailyAt;
    private final long lastWorkAt;
    private final long lastTriviaAt;
    private final long lastSeenAt;
    private final String username;
    private final String avatar;
    private final long createdAt;

    private UserProfile(Builder b)
    {
        this.userId = b.userId;
        this.currency = b.currency;
        this.xp = b.xp;
        this.songsRequested = b.songsRequested;
        this.msListened = b.msListened;
        this.guessesCorrect = b.guessesCorrect;
        this.guessWins = b.guessWins;
        this.gamesPlayed = b.gamesPlayed;
        this.gambleWins = b.gambleWins;
        this.gambleLosses = b.gambleLosses;
        this.gambleWagered = b.gambleWagered;
        this.gambleNet = b.gambleNet;
        this.biggestWin = b.biggestWin;
        this.dailyStreak = b.dailyStreak;
        this.lastDailyAt = b.lastDailyAt;
        this.lastWorkAt = b.lastWorkAt;
        this.lastTriviaAt = b.lastTriviaAt;
        this.lastSeenAt = b.lastSeenAt;
        this.username = b.username;
        this.avatar = b.avatar;
        this.createdAt = b.createdAt;
    }

    /** @return a zeroed profile for a user with no stored row yet */
    public static UserProfile empty(long userId)
    {
        return new Builder(userId).build();
    }

    public static Builder builder(long userId)
    {
        return new Builder(userId);
    }

    public long getUserId() { return userId; }
    public long getCurrency() { return currency; }
    public long getXp() { return xp; }
    public long getSongsRequested() { return songsRequested; }
    public long getMsListened() { return msListened; }
    public long getMinutesListened() { return msListened / 60_000L; }
    public long getGuessesCorrect() { return guessesCorrect; }
    public long getGuessWins() { return guessWins; }
    public long getGamesPlayed() { return gamesPlayed; }
    public long getGambleWins() { return gambleWins; }
    public long getGambleLosses() { return gambleLosses; }
    public long getGambleWagered() { return gambleWagered; }
    public long getGambleNet() { return gambleNet; }
    public long getBiggestWin() { return biggestWin; }
    public int getDailyStreak() { return dailyStreak; }
    public long getLastDailyAt() { return lastDailyAt; }
    public long getLastWorkAt() { return lastWorkAt; }
    public long getLastTriviaAt() { return lastTriviaAt; }
    public long getLastSeenAt() { return lastSeenAt; }
    public String getUsername() { return username; }
    public String getAvatar() { return avatar; }
    public long getCreatedAt() { return createdAt; }

    public int getLevel() { return LevelCurve.levelForXp(xp); }
    public long getXpIntoLevel() { return LevelCurve.xpIntoCurrentLevel(xp); }
    public long getXpForNextLevel() { return LevelCurve.xpToNextLevel(xp); }

    public static final class Builder
    {
        private final long userId;
        private long currency;
        private long xp;
        private long songsRequested;
        private long msListened;
        private long guessesCorrect;
        private long guessWins;
        private long gamesPlayed;
        private long gambleWins;
        private long gambleLosses;
        private long gambleWagered;
        private long gambleNet;
        private long biggestWin;
        private int dailyStreak;
        private long lastDailyAt;
        private long lastWorkAt;
        private long lastTriviaAt;
        private long lastSeenAt;
        private String username;
        private String avatar;
        private long createdAt;

        private Builder(long userId) { this.userId = userId; }

        public Builder currency(long v) { this.currency = v; return this; }
        public Builder xp(long v) { this.xp = v; return this; }
        public Builder songsRequested(long v) { this.songsRequested = v; return this; }
        public Builder msListened(long v) { this.msListened = v; return this; }
        public Builder guessesCorrect(long v) { this.guessesCorrect = v; return this; }
        public Builder guessWins(long v) { this.guessWins = v; return this; }
        public Builder gamesPlayed(long v) { this.gamesPlayed = v; return this; }
        public Builder gambleWins(long v) { this.gambleWins = v; return this; }
        public Builder gambleLosses(long v) { this.gambleLosses = v; return this; }
        public Builder gambleWagered(long v) { this.gambleWagered = v; return this; }
        public Builder gambleNet(long v) { this.gambleNet = v; return this; }
        public Builder biggestWin(long v) { this.biggestWin = v; return this; }
        public Builder dailyStreak(int v) { this.dailyStreak = v; return this; }
        public Builder lastDailyAt(long v) { this.lastDailyAt = v; return this; }
        public Builder lastWorkAt(long v) { this.lastWorkAt = v; return this; }
        public Builder lastTriviaAt(long v) { this.lastTriviaAt = v; return this; }
        public Builder lastSeenAt(long v) { this.lastSeenAt = v; return this; }
        public Builder username(String v) { this.username = v; return this; }
        public Builder avatar(String v) { this.avatar = v; return this; }
        public Builder createdAt(long v) { this.createdAt = v; return this; }

        public UserProfile build() { return new UserProfile(this); }
    }
}
