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
package com.jagrosh.jmusicbot.commands.economy.games;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.economy.EconomyService.GameOutcome;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base for a stateful, button-driven casino game bound to one message. The wager
 * is <b>escrowed</b> by the command (debited + recorded under {@link #escrowId})
 * before the session starts; the session credits the resolved payout and clears
 * the escrow atomically through {@link #settleOnce(long)} exactly once, guarded by
 * a {@link ResolveGuard} so a button click, an inactivity timeout and (for crash)
 * a crash tick can all race without ever double-paying or stranding the debit. Any
 * escrow left unresolved by a hard crash is refunded on the next boot.
 *
 * <p>Subclasses own their game state, their button handling and their
 * abandon-time auto-resolution — which must never pay <i>more</i> than an
 * in-progress cash-out, so walking away is never better than playing.
 */
public abstract class GameSession
{
    private static final Logger LOG = LoggerFactory.getLogger(GameSession.class);

    protected final Bot bot;
    protected final long ownerId;
    protected final String ownerName;
    protected final long guildId;
    protected final long channelId;
    protected final long wager;
    /** The crash-recovery escrow holding this game's stake; cleared (or refunded) exactly when it resolves. */
    protected final String escrowId;

    private volatile long messageId = -1L;
    private final ResolveGuard guard = new ResolveGuard();
    private volatile ScheduledFuture<?> timeout;

    protected GameSession(Bot bot, long ownerId, String ownerName, long guildId, long channelId, long wager,
                          String escrowId)
    {
        this.bot = bot;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.guildId = guildId;
        this.channelId = channelId;
        this.wager = wager;
        this.escrowId = escrowId;
    }

    public long getOwnerId() { return ownerId; }
    public long getMessageId() { return messageId; }
    public String getEscrowId() { return escrowId; }
    public boolean isResolved() { return guard.isResolved(); }

    /**
     * Binds the sent message, registers the session (auto-resolving any prior
     * game the owner had) and arms the inactivity timeout. Call from the reply
     * callback once the message id is known.
     */
    public void begin(long messageId, long timeoutMs)
    {
        this.messageId = messageId;
        bot.getGameSessions().register(this);
        scheduleTimeout(timeoutMs);
        onStarted();
    }

    /**
     * Called when the panel could not be sent (the reply failed), so the game never went live:
     * refunds the escrowed wager exactly once. The session was never registered and no timeout was
     * armed, so there is nothing else to unwind. Safe to call at most once; a no-op if already resolved.
     */
    public void cancelBeforeStart()
    {
        if(!guard.claim())
            return; // already resolved somehow — never double-refund
        if(wager > 0)
            bot.getEconomyService().resolveEscrow(escrowId, wager); // refund the escrowed stake + clear its row
    }

    /** Hook invoked once the session's message is bound and registered (e.g. to start ticks). */
    protected void onStarted() {}

    /** Hook invoked exactly once, right after this session wins resolution (e.g. to release resources). */
    protected void onResolved() {}

    /** Settles exactly once with the base wager. Returns the outcome, or {@code null} if already resolved. */
    protected GameOutcome settleOnce(long rawPayout)
    {
        return settleOnce(wager, rawPayout);
    }

    /**
     * Settles exactly once with an explicit total stake (e.g. after a blackjack
     * double). Returns the outcome, or {@code null} if the game was already resolved.
     */
    protected GameOutcome settleOnce(long stake, long rawPayout)
    {
        if(!claimResolution())
            return null;
        return settleClaimed(stake, rawPayout);
    }

    /**
     * Claims the sole right to resolve this game. Returns true to exactly one caller.
     * Use when the payout requires side-effecting work (e.g. playing out the dealer)
     * that must happen only once — claim first, do the work, then {@link #settleClaimed}.
     */
    protected boolean claimResolution()
    {
        return guard.claim();
    }

    /** Settles after a successful {@link #claimResolution()} (base wager as stake). */
    protected GameOutcome settleClaimed(long rawPayout)
    {
        return settleClaimed(wager, rawPayout);
    }

    /** Settles after a successful {@link #claimResolution()} with an explicit stake. */
    protected GameOutcome settleClaimed(long stake, long rawPayout)
    {
        closeClaimed();
        // Resolve the crash-recovery escrow atomically with the payout, so this settled round can never be
        // refunded again on a later boot.
        return bot.getEconomyService().settleGame(ownerId, stake, rawPayout, channel(), escrowId);
    }

    /**
     * Finalizes a claimed game without any house settlement (cleanup only): cancels
     * the timeout, deregisters the session and runs {@link #onResolved()}. For
     * non-wager games (trivia, PvP duel) that award/transfer coins themselves.
     * Call only after a successful {@link #claimResolution()}.
     */
    protected void closeClaimed()
    {
        cancelTimeout();
        bot.getGameSessions().remove(messageId);
        try
        {
            onResolved();
        }
        catch(RuntimeException ex)
        {
            LOG.debug("onResolved hook failed for message {}", messageId, ex);
        }
    }

    protected boolean isSettled() { return guard.isResolved(); }

    /**
     * Whether {@code userId} is allowed to press the button {@code action}. Defaults
     * to owner-only; PvP games override so a specific opponent can accept/decline.
     */
    public boolean canPress(long userId, String action)
    {
        return userId == ownerId;
    }

    /** Updates the interactive panel (embed + buttons) in response to a click. */
    protected void editPanel(ButtonInteractionEvent event, MessageEmbed embed, List<ActionRow> rows)
    {
        event.editMessageEmbeds(embed).setComponents(rows).queue(x -> {}, t -> {});
    }

    /** Replaces the message with a final result (buttons removed), in response to a click. */
    protected void editResult(ButtonInteractionEvent event, MessageEmbed embed)
    {
        event.editMessageEmbeds(embed).setComponents(Collections.emptyList()).queue(x -> {}, t -> {});
    }

    /** Acknowledges a click that didn't change anything, to avoid a client-side "interaction failed". */
    protected void ackIfNeeded(ButtonInteractionEvent event)
    {
        if(!event.isAcknowledged())
            event.deferEdit().queue(x -> {}, t -> {});
    }

    /** Replaces the message with a final result by id (used from the timeout path, no event). */
    protected void editResultById(MessageEmbed embed)
    {
        MessageChannel ch = channel();
        if(ch != null && messageId > 0)
            ch.editMessageEmbedsById(messageId, embed).setComponents(Collections.emptyList()).queue(x -> {}, t -> {});
    }

    /** Updates the panel by id with the given components (used from scheduler ticks, no event). */
    protected void editPanelById(MessageEmbed embed, List<ActionRow> rows)
    {
        MessageChannel ch = channel();
        if(ch != null && messageId > 0)
            ch.editMessageEmbedsById(messageId, embed).setComponents(rows).queue(x -> {}, t -> {});
    }

    protected MessageChannel channel()
    {
        return bot.getJDA() == null ? null : bot.getJDA().getChannelById(MessageChannel.class, channelId);
    }

    protected void scheduleTimeout(long delayMs)
    {
        cancelTimeout();
        timeout = bot.getGamesScheduler().schedule(this::fireTimeout, delayMs, TimeUnit.MILLISECONDS);
    }

    protected void rearmTimeout(long delayMs)
    {
        scheduleTimeout(delayMs);
    }

    protected void cancelTimeout()
    {
        ScheduledFuture<?> t = timeout;
        if(t != null)
        {
            t.cancel(false);
            timeout = null;
        }
    }

    private void fireTimeout()
    {
        if(guard.isResolved())
            return;
        try
        {
            onTimeout();
        }
        catch(RuntimeException ex)
        {
            LOG.warn("Game timeout handler failed for message {}", messageId, ex);
        }
    }

    /** Forces the abandon/auto-resolve path (used when the owner starts a new game). */
    void forceAutoResolve()
    {
        fireTimeout();
    }

    /** Auto-resolves an abandoned game. Must never pay more than a live cash-out would. */
    protected abstract void onTimeout();

    /** Handles a button press; {@code action} is the customId minus the {@code jmb-game:} prefix. */
    public abstract void onButton(String action, ButtonInteractionEvent event);
}
