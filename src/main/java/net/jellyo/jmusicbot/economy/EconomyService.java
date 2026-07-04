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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Business logic for the global economy: turning bot activity into currency and
 * XP, handing out the daily chest, settling gambles, and notifying the
 * achievement engine. All reads/writes go through {@link EconomyStore}; this
 * layer owns the reward amounts and the (host-local) daily calendar logic.
 *
 * <p>When the economy is disabled every award method is a no-op and gambling is
 * refused, so callers can invoke freely without guarding.
 */
public class EconomyService
{
    private static final Logger LOG = LoggerFactory.getLogger(EconomyService.class);

    public static final String CURRENCY_EMOJI = "🪙"; // 🪙
    public static final String CURRENCY_NAME = "coins";

    // Reward tuning. Chosen to feel rewarding without runaway inflation.
    public static final long XP_PER_SONG = 12;
    public static final long COINS_PER_SONG = 5;
    // Listening XP is only credited while music is actually playing (autoplay counts) and only
    // to users who have queued a song this session (see ListeningRewardService). Given those
    // gates and the steep level curve, a few XP per minute is reasonable; no coins from listening.
    public static final long XP_PER_MINUTE = 5;
    public static final long COINS_PER_MINUTE = 0;
    public static final long DAILY_BASE = 100;
    public static final long DAILY_STREAK_BONUS = 25;
    public static final int DAILY_MAX_BONUS_DAYS = 14;
    public static final long DAILY_XP = 50;
    public static final long GUESS_CORRECT_COINS = 15;
    public static final long GUESS_CORRECT_XP = 25;
    public static final long GUESS_WIN_COINS = 75;
    public static final long GUESS_WIN_XP = 60;
    public static final long GAME_PLAYED_XP = 10;
    // Work: a short-cooldown earner. Yield is kept well below the house-edge burn a gambler
    // pays, so it tops players up without out-minting the sinks.
    public static final long WORK_COOLDOWN_SECONDS = 1200; // 20 minutes
    public static final long WORK_MIN_COINS = 50;
    public static final long WORK_MAX_COINS = 250;
    public static final long WORK_COINS_PER_LEVEL = 2;
    public static final long WORK_MAX_LEVEL_BONUS = 200;
    public static final long WORK_XP = 15;
    // Trivia: a short-cooldown quiz. Correct answers pay by difficulty.
    public static final long TRIVIA_COOLDOWN_SECONDS = 600; // 10 minutes
    public static final long TRIVIA_XP = 20;

    private final EconomyStore store;
    private final boolean enabled;
    private final ZoneId zone;
    private volatile EconomyObserver observer;

    public EconomyService(EconomyStore store, boolean enabled)
    {
        this(store, enabled, ZoneId.systemDefault());
    }

    EconomyService(EconomyStore store, boolean enabled, ZoneId zone)
    {
        this.store = store;
        this.enabled = enabled;
        this.zone = zone;
    }

    public boolean isEnabled()
    {
        return enabled && store != null;
    }

    public void setObserver(EconomyObserver observer)
    {
        this.observer = observer;
    }

    public EconomyStore getStore()
    {
        return store;
    }

    // ---- profile access ----------------------------------------------------

    public void ensureUser(User user)
    {
        if(!isEnabled() || user == null)
            return;
        try
        {
            store.ensureProfile(user.getIdLong(), safeName(user), avatarUrl(user));
        }
        catch(RuntimeException ex)
        {
            LOG.warn("Failed to ensure economy profile for {}", user.getId(), ex);
        }
    }

    public UserProfile getProfile(long userId)
    {
        if(store == null)
            return UserProfile.empty(userId);
        return store.getProfile(userId);
    }

    public long getBalance(long userId)
    {
        return store == null ? 0 : store.getBalance(userId);
    }

    // ---- generic awards ----------------------------------------------------

    /** Adds currency and/or XP directly (no announcement). Returns the new balance. */
    public long award(long userId, long coins, long xp)
    {
        return award(userId, coins, xp, null);
    }

    /** Adds currency and/or XP directly, announcing a level-up in {@code channel} if one occurs. */
    public long award(long userId, long coins, long xp, MessageChannel channel)
    {
        if(!isEnabled())
            return getBalance(userId);
        addXpWithLevelCheck(userId, xp, channel);
        return coins == 0 ? getBalance(userId) : store.addCurrency(userId, coins);
    }

    // ---- activity hooks ----------------------------------------------------

    public void onSongRequested(long userId, MessageChannel channel)
    {
        if(!isEnabled())
            return;
        store.incrementSongsRequested(userId, 1);
        store.addCurrency(userId, COINS_PER_SONG);
        addXpWithLevelCheck(userId, XP_PER_SONG, channel);
        notifyObserver(userId, EconomyEvent.SONG_REQUESTED, channel);
    }

    /**
     * Credits a member for listening time. Listening is silent (no chat ping);
     * XP is awarded per whole minute crossed. Callers should only invoke this
     * while music is playing and for users who have queued a song this session
     * (the listening reward sampler enforces both gates).
     */
    public void creditListening(long userId, long deltaMs, String username, String avatar)
    {
        if(!isEnabled() || deltaMs <= 0)
            return;
        if(username != null || avatar != null)
            store.ensureProfile(userId, username, avatar);
        long newTotal = store.addMsListened(userId, deltaMs);
        long minutesCrossed = newTotal / 60_000L - (newTotal - deltaMs) / 60_000L;
        if(minutesCrossed > 0)
        {
            if(COINS_PER_MINUTE > 0)
                store.addCurrency(userId, minutesCrossed * COINS_PER_MINUTE);
            // Listening level-ups are not announced in chat (no channel).
            addXpWithLevelCheck(userId, minutesCrossed * XP_PER_MINUTE, null);
        }
        notifyObserver(userId, EconomyEvent.LISTENED, null);
    }

    public void recordGuessCorrect(long userId, MessageChannel channel)
    {
        if(!isEnabled())
            return;
        store.incrementGuessesCorrect(userId, 1);
        store.addCurrency(userId, GUESS_CORRECT_COINS);
        addXpWithLevelCheck(userId, GUESS_CORRECT_XP, channel);
        notifyObserver(userId, EconomyEvent.GUESS_CORRECT, channel);
    }

    public void recordGuessWin(long userId, MessageChannel channel)
    {
        if(!isEnabled())
            return;
        store.incrementGuessWins(userId, 1);
        store.addCurrency(userId, GUESS_WIN_COINS);
        addXpWithLevelCheck(userId, GUESS_WIN_XP, channel);
        notifyObserver(userId, EconomyEvent.GUESS_WON, channel);
    }

    public void recordGamePlayed(long userId, MessageChannel channel)
    {
        if(!isEnabled())
            return;
        store.incrementGamesPlayed(userId, 1);
        addXpWithLevelCheck(userId, GAME_PLAYED_XP, channel);
        notifyObserver(userId, EconomyEvent.GAME_PLAYED, channel);
    }

    // ---- daily chest -------------------------------------------------------

    public DailyResult claimDaily(User user, MessageChannel channel)
    {
        if(!isEnabled() || user == null)
            return DailyResult.disabled();
        long userId = user.getIdLong();
        ensureUser(user);
        long nowEpoch = Instant.now().getEpochSecond();
        // The read-decide-write must be atomic, otherwise two near-simultaneous /daily
        // commands both read a stale last_daily_at and both award the chest. The store's
        // own methods synchronize on this same monitor, so this just spans them as a unit.
        long amount;
        int streak;
        long balance;
        synchronized(store)
        {
            UserProfile profile = store.getProfile(userId);
            DailyDecision decision = decideDaily(profile.getLastDailyAt(), profile.getDailyStreak(), nowEpoch, zone);
            if(!decision.claimable)
                return DailyResult.onCooldown(decision.secondsUntilNext, profile.getDailyStreak());

            long bonus = Math.min(decision.streak - 1, DAILY_MAX_BONUS_DAYS) * DAILY_STREAK_BONUS;
            amount = DAILY_BASE + bonus;
            streak = decision.streak;
            store.setDaily(userId, nowEpoch, decision.streak);
            balance = store.addCurrency(userId, amount);
        }
        addXpWithLevelCheck(userId, DAILY_XP, channel);
        notifyObserver(userId, EconomyEvent.DAILY_CLAIMED, channel);
        return DailyResult.claimed(amount, DAILY_XP, streak, balance);
    }

    // ---- work (cooldown earner) --------------------------------------------

    public WorkResult claimWork(User user, MessageChannel channel)
    {
        if(!isEnabled() || user == null)
            return WorkResult.disabled();
        long userId = user.getIdLong();
        ensureUser(user);
        long nowEpoch = Instant.now().getEpochSecond();
        long amount;
        long balance;
        // Atomic read-decide-write, mirroring claimDaily, so two quick /work calls can't both pay.
        synchronized(store)
        {
            UserProfile profile = store.getProfile(userId);
            CooldownDecision decision = decideCooldown(profile.getLastWorkAt(), nowEpoch, WORK_COOLDOWN_SECONDS);
            if(!decision.isReady())
                return WorkResult.onCooldown(decision.getSecondsUntilNext());
            int level = LevelCurve.levelForXp(profile.getXp());
            long levelBonus = Math.min(WORK_MAX_LEVEL_BONUS, (long) level * WORK_COINS_PER_LEVEL);
            int span = (int) (WORK_MAX_COINS - WORK_MIN_COINS + 1);
            amount = WORK_MIN_COINS + ThreadLocalRandom.current().nextInt(span) + levelBonus;
            store.setLastWorkAt(userId, nowEpoch);
            balance = store.addCurrency(userId, amount);
        }
        addXpWithLevelCheck(userId, WORK_XP, channel);
        notifyObserver(userId, EconomyEvent.GAME_PLAYED, channel); // triggers achievement re-evaluation
        return WorkResult.claimed(amount, WORK_XP, balance);
    }

    // ---- trivia (cooldown quiz) --------------------------------------------

    /**
     * Atomically checks the trivia cooldown and, if ready, stamps it (so the
     * attempt is consumed whether or not the answer is right). Returns the seconds
     * remaining if on cooldown, or 0 if the attempt was started.
     */
    public long tryStartTrivia(User user)
    {
        if(!isEnabled() || user == null)
            return 0;
        long userId = user.getIdLong();
        ensureUser(user);
        long nowEpoch = Instant.now().getEpochSecond();
        synchronized(store)
        {
            UserProfile profile = store.getProfile(userId);
            CooldownDecision decision = decideCooldown(profile.getLastTriviaAt(), nowEpoch, TRIVIA_COOLDOWN_SECONDS);
            if(!decision.isReady())
                return decision.getSecondsUntilNext();
            store.setLastTriviaAt(userId, nowEpoch);
            return 0;
        }
    }

    /** Awards a correct trivia answer (coins by difficulty + XP), announcing a level-up. */
    public long rewardTrivia(long userId, long coins, MessageChannel channel)
    {
        return award(userId, coins, TRIVIA_XP, channel);
    }

    // ---- gambling ----------------------------------------------------------

    /** Atomically removes a wager from the balance. Returns false if unaffordable. */
    public boolean trySpend(long userId, long amount)
    {
        return isEnabled() && store.trySpend(userId, amount);
    }

    public long addCurrency(long userId, long delta)
    {
        return isEnabled() ? store.addCurrency(userId, delta) : getBalance(userId);
    }

    // ---- crash-safe wager escrow -------------------------------------------

    /**
     * Escrows a wager: atomically debits {@code amount} and records a durable crash-recovery row. Returns an
     * opaque escrow id to hand to {@link #settleGame}/{@link #resolveEscrow} when the game ends, or
     * {@code null} if the economy is off or the user can't afford it (in which case nothing was debited).
     */
    public String escrow(long userId, long amount, String kind)
    {
        if(!isEnabled() || amount <= 0)
            return null; // a non-null id always denotes a real pending row that a settle can resolve against
        String id = UUID.randomUUID().toString();
        return store.escrow(userId, amount, id, kind) ? id : null;
    }

    /** Clears an escrow and credits {@code credit} back to its owner (a refund or manual settle). */
    public long resolveEscrow(String escrowId, long credit)
    {
        return isEnabled() && escrowId != null ? store.resolveEscrow(escrowId, credit) : 0;
    }

    /** Grows a live escrow by an extra stake (e.g. a blackjack double-down), atomically. False if refused. */
    public boolean increaseEscrow(String escrowId, long extra)
    {
        return isEnabled() && escrowId != null && store.increaseEscrow(escrowId, extra);
    }

    /** Settles a duel: clears both antes' escrows and pays the winner {@code pot}, atomically. */
    public void settleDuel(String escrowA, String escrowB, long winnerId, long pot)
    {
        if(isEnabled())
            store.settleDuel(escrowA, escrowB, winnerId, pot);
    }

    /**
     * Refunds every wager left unresolved by a crash (a game still live in memory when the bot went down)
     * and returns them so the caller can tell the affected players. Runs even when the economy is currently
     * disabled, so stakes from a previous enabled run are never lost. Call once on boot.
     */
    public List<EconomyStore.PendingWager> reclaimAbandonedWagers()
    {
        return store == null ? Collections.emptyList() : store.reclaimPending();
    }

    /**
     * Settles a resolved casino round after the wager has already been escrowed
     * (debited + recorded) via {@link #escrow}. This is the single settlement path
     * for every game: it applies the per-round return cap, credits the payout,
     * records the gamble stats, awards participation XP (announcing a level-up),
     * and pays a daily-capped loyalty rebate on a net loss.
     *
     * <p>{@code rawPayout} is the total returned to the player, stake-inclusive
     * (0 on a total loss, {@code wager} on a push, {@code > wager} on a win) — see
     * {@link Payouts} for the convention.
     *
     * @return a {@link GameOutcome} describing the settled round
     */
    public GameOutcome settleGame(long userId, long wager, long rawPayout, MessageChannel channel)
    {
        return settleGame(userId, wager, rawPayout, channel, null);
    }

    /**
     * As {@link #settleGame(long, long, long, MessageChannel)}, but resolves the durable escrow
     * {@code escrowId} atomically with the payout credit — clearing the crash-recovery row in the same
     * transaction — instead of a bare credit. Every wager game supplies its escrow id, so a round that has
     * settled can never be double-refunded on the next boot. A {@code null} id falls back to a plain credit.
     */
    public GameOutcome settleGame(long userId, long wager, long rawPayout, MessageChannel channel, String escrowId)
    {
        if(!isEnabled())
            return GameOutcome.disabled(getBalance(userId));
        long payout = Payouts.clampReturn(Math.max(0, rawPayout));
        if(payout < rawPayout)
            LOG.debug("Clamped game payout {} -> {} for user {}", rawPayout, payout, userId);
        // Read the level before awarding this round's XP so the rebate reflects the
        // player's standing at the time of the loss.
        int level = LevelCurve.levelForXp(store.getProfile(userId).getXp());
        if(escrowId != null)
            store.resolveEscrow(escrowId, payout); // credit the payout AND clear the crash-recovery row atomically
        else if(payout > 0)
            store.addCurrency(userId, payout);
        long net = payout - wager;
        boolean won = net > 0;
        store.recordGamble(userId, wager, net, won);
        store.incrementGamesPlayed(userId, 1);
        if(won)
            store.updateBiggestWin(userId, net);
        long xp = Payouts.gameXp(wager);
        addXpWithLevelCheck(userId, xp, channel);
        long rebate = 0;
        if(net < 0)
        {
            long rawRebate = Payouts.loyaltyRebate(-net, level);
            if(rawRebate > 0)
            {
                long dayKey = Payouts.dayKey(Instant.now().getEpochSecond(), zone);
                rebate = store.addRebateCapped(userId, rawRebate, dayKey, Payouts.REBATE_DAILY_CAP);
            }
        }
        notifyObserver(userId, won ? EconomyEvent.GAMBLE_WON : EconomyEvent.GAMBLE_LOST, channel);
        return new GameOutcome(net, payout, rebate, xp, getBalance(userId));
    }

    /**
     * Back-compat thin delegate for {@code /gamble}: settles through
     * {@link #settleGame} (so it now inherits the return cap, participation XP and
     * loyalty rebate) and returns just the net change.
     */
    public long settleGamble(long userId, long wager, long payout, MessageChannel channel)
    {
        return settleGame(userId, wager, payout, channel).getNet();
    }

    // ---- helpers -----------------------------------------------------------

    private void notifyObserver(long userId, EconomyEvent event, MessageChannel channel)
    {
        EconomyObserver obs = observer;
        if(obs == null)
            return;
        try
        {
            obs.onEconomyEvent(userId, event, LocalTime.now(zone).getHour(), channel);
        }
        catch(RuntimeException ex)
        {
            LOG.warn("Economy observer failed for event {}", event, ex);
        }
    }

    /** Adds XP and, if it pushes the user to a new level and a channel is given, announces it. */
    private void addXpWithLevelCheck(long userId, long xp, MessageChannel channel)
    {
        if(xp <= 0)
            return;
        long before = store.getProfile(userId).getXp();
        store.addXp(userId, xp);
        int oldLevel = LevelCurve.levelForXp(before);
        int newLevel = LevelCurve.levelForXp(before + xp);
        if(newLevel > oldLevel && channel != null)
            announceLevelUp(userId, newLevel, channel);
    }

    private void announceLevelUp(long userId, int level, MessageChannel channel)
    {
        try
        {
            channel.sendMessage("🎉 <@" + userId + "> leveled up to **level " + level + "**!")
                    .setAllowedMentions(EnumSet.of(Message.MentionType.USER))
                    .queue(m -> {}, f -> LOG.debug("Failed to announce level-up for {}: {}", userId, f.toString()));
        }
        catch(RuntimeException ex)
        {
            LOG.debug("Failed to announce level-up for {}", userId, ex);
        }
    }

    static String safeName(User user)
    {
        if(user == null)
            return null;
        String name = user.getEffectiveName();
        return name == null || name.isBlank() ? user.getName() : name;
    }

    private static String avatarUrl(User user)
    {
        return user == null ? null : user.getEffectiveAvatarUrl();
    }

    /** Formats a currency amount with the coin emoji, e.g. {@code "1,234 🪙"}. */
    public static String coins(long amount)
    {
        return String.format("%,d", amount) + " " + CURRENCY_EMOJI;
    }

    /**
     * Minimum wall-clock gap between two daily claims. A pure calendar-day check
     * would let a claim at 23:59 and another at 00:01 both land (different local
     * dates, seconds apart); this floor makes the chest genuinely once per ~day.
     */
    static final long MIN_DAILY_INTERVAL_SECONDS = 20 * 3600; // 20h

    /**
     * Pure daily-chest decision based on host-local calendar days plus a minimum
     * elapsed gap. Claimable only when it is a different local day <b>and</b> at
     * least {@link #MIN_DAILY_INTERVAL_SECONDS} have elapsed since the last claim,
     * so two claims can never straddle local midnight a few seconds apart.
     * Consecutive day &rarr; streak increments; a skipped day resets it to 1.
     */
    static DailyDecision decideDaily(long lastDailyEpoch, int prevStreak, long nowEpoch, ZoneId zone)
    {
        LocalDate today = Instant.ofEpochSecond(nowEpoch).atZone(zone).toLocalDate();
        LocalDate lastDate = lastDailyEpoch > 0
                ? Instant.ofEpochSecond(lastDailyEpoch).atZone(zone).toLocalDate()
                : null;
        boolean sameDay = lastDate != null && lastDate.equals(today);
        boolean tooSoon = lastDailyEpoch > 0 && nowEpoch - lastDailyEpoch < MIN_DAILY_INTERVAL_SECONDS;
        if(sameDay || tooSoon)
        {
            long waitUntil = nowEpoch;
            if(sameDay)
                waitUntil = Math.max(waitUntil, today.plusDays(1).atStartOfDay(zone).toEpochSecond());
            if(tooSoon)
                waitUntil = Math.max(waitUntil, lastDailyEpoch + MIN_DAILY_INTERVAL_SECONDS);
            return new DailyDecision(false, prevStreak, Math.max(0, waitUntil - nowEpoch));
        }
        int streak = (lastDate != null && lastDate.plusDays(1).equals(today)) ? prevStreak + 1 : 1;
        return new DailyDecision(true, streak, 0);
    }

    static final class DailyDecision
    {
        final boolean claimable;
        final int streak;
        final long secondsUntilNext;

        DailyDecision(boolean claimable, int streak, long secondsUntilNext)
        {
            this.claimable = claimable;
            this.streak = streak;
            this.secondsUntilNext = secondsUntilNext;
        }
    }

    /**
     * Pure fixed-interval cooldown decision (elapsed-seconds based), shared by
     * {@code /work} and {@code /trivia}. Claimable when at least
     * {@code cooldownSeconds} have elapsed since {@code lastEpoch}.
     */
    public static CooldownDecision decideCooldown(long lastEpoch, long nowEpoch, long cooldownSeconds)
    {
        if(lastEpoch <= 0)
            return new CooldownDecision(true, 0);
        long readyAt = lastEpoch + cooldownSeconds;
        if(nowEpoch >= readyAt)
            return new CooldownDecision(true, 0);
        return new CooldownDecision(false, readyAt - nowEpoch);
    }

    public static final class CooldownDecision
    {
        private final boolean ready;
        private final long secondsUntilNext;

        CooldownDecision(boolean ready, long secondsUntilNext)
        {
            this.ready = ready;
            this.secondsUntilNext = secondsUntilNext;
        }

        public boolean isReady() { return ready; }
        public long getSecondsUntilNext() { return secondsUntilNext; }
    }

    /** Immutable result of a settled casino round, for building the reply embed. */
    public static final class GameOutcome
    {
        private final long net;
        private final long payout;
        private final long rebate;
        private final long xpAwarded;
        private final long newBalance;

        public GameOutcome(long net, long payout, long rebate, long xpAwarded, long newBalance)
        {
            this.net = net;
            this.payout = payout;
            this.rebate = rebate;
            this.xpAwarded = xpAwarded;
            this.newBalance = newBalance;
        }

        static GameOutcome disabled(long balance)
        {
            return new GameOutcome(0, 0, 0, 0, balance);
        }

        /** Net change for the player (payout - wager); negative on a loss. */
        public long getNet() { return net; }
        /** Total coins returned this round after the per-round cap (0 on a total loss). */
        public long getPayout() { return payout; }
        /** Loyalty rebate credited on a net loss (0 otherwise / when the daily cap is spent). */
        public long getRebate() { return rebate; }
        public long getXpAwarded() { return xpAwarded; }
        public long getNewBalance() { return newBalance; }
        public boolean isWin() { return net > 0; }
    }

    public static final class WorkResult
    {
        private final boolean enabled;
        private final boolean worked;
        private final long amount;
        private final long xp;
        private final long newBalance;
        private final long secondsUntilNext;

        private WorkResult(boolean enabled, boolean worked, long amount, long xp,
                           long newBalance, long secondsUntilNext)
        {
            this.enabled = enabled;
            this.worked = worked;
            this.amount = amount;
            this.xp = xp;
            this.newBalance = newBalance;
            this.secondsUntilNext = secondsUntilNext;
        }

        static WorkResult disabled() { return new WorkResult(false, false, 0, 0, 0, 0); }
        static WorkResult claimed(long amount, long xp, long balance) { return new WorkResult(true, true, amount, xp, balance, 0); }
        static WorkResult onCooldown(long secondsUntilNext) { return new WorkResult(true, false, 0, 0, 0, secondsUntilNext); }

        public boolean isEnabled() { return enabled; }
        public boolean isWorked() { return worked; }
        public long getAmount() { return amount; }
        public long getXp() { return xp; }
        public long getNewBalance() { return newBalance; }
        public long getSecondsUntilNext() { return secondsUntilNext; }
    }

    public static final class DailyResult
    {
        private final boolean enabled;
        private final boolean claimed;
        private final long amount;
        private final long xp;
        private final int streak;
        private final long newBalance;
        private final long secondsUntilNext;

        private DailyResult(boolean enabled, boolean claimed, long amount, long xp, int streak,
                            long newBalance, long secondsUntilNext)
        {
            this.enabled = enabled;
            this.claimed = claimed;
            this.amount = amount;
            this.xp = xp;
            this.streak = streak;
            this.newBalance = newBalance;
            this.secondsUntilNext = secondsUntilNext;
        }

        static DailyResult disabled()
        {
            return new DailyResult(false, false, 0, 0, 0, 0, 0);
        }

        static DailyResult claimed(long amount, long xp, int streak, long newBalance)
        {
            return new DailyResult(true, true, amount, xp, streak, newBalance, 0);
        }

        static DailyResult onCooldown(long secondsUntilNext, int streak)
        {
            return new DailyResult(true, false, 0, 0, streak, 0, secondsUntilNext);
        }

        public boolean isEnabled() { return enabled; }
        public boolean isClaimed() { return claimed; }
        public long getAmount() { return amount; }
        public long getXp() { return xp; }
        public int getStreak() { return streak; }
        public long getNewBalance() { return newBalance; }
        public long getSecondsUntilNext() { return secondsUntilNext; }
    }
}
