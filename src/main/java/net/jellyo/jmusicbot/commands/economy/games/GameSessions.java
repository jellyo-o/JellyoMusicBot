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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of active interactive game sessions, keyed by their message id, with a
 * secondary user index so each player may only have one live interactive game at
 * a time. Starting a new game auto-resolves the owner's previous one (rather than
 * locking them out until it times out).
 */
public final class GameSessions
{
    private final Bot bot;
    private final ConcurrentHashMap<Long, GameSession> byMessage = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> byUser = new ConcurrentHashMap<>();

    public GameSessions(Bot bot)
    {
        this.bot = bot;
    }

    /**
     * Registers a started session (its message already sent + bound), resolving any prior one.
     *
     * <p>The owner slot is claimed with a single atomic {@link java.util.concurrent.ConcurrentHashMap#put}
     * whose returned previous value tells us exactly which prior message (if any) we displaced — so two
     * near-simultaneous starts by the same owner (e.g. prefix + slash) can never both believe they are the
     * first and leave two live games: whichever put runs second sees the other's message id and resolves it.
     */
    public void register(GameSession session)
    {
        long messageId = session.getMessageId();
        byMessage.put(messageId, session);
        Long priorMessage = byUser.put(session.getOwnerId(), messageId);
        if(priorMessage != null && priorMessage != messageId)
        {
            GameSession prior = byMessage.remove(priorMessage);
            if(prior != null && prior != session)
                prior.forceAutoResolve(); // resolve the displaced game (refunds/settles it) outside any map lock
        }
    }

    public GameSession get(long messageId)
    {
        return byMessage.get(messageId);
    }

    public void remove(long messageId)
    {
        GameSession removed = byMessage.remove(messageId);
        if(removed != null)
            byUser.remove(removed.getOwnerId(), messageId);
    }

    /** @return true if the user already has a live interactive game. */
    public boolean hasActive(long userId)
    {
        Long messageId = byUser.get(userId);
        return messageId != null && byMessage.containsKey(messageId);
    }

    /** Resolves every live session (used on shutdown so no debit is left unsettled). */
    public void resolveAll()
    {
        for(GameSession session : byMessage.values())
        {
            try
            {
                session.forceAutoResolve();
            }
            catch(RuntimeException ignored)
            {
                // best-effort teardown
            }
        }
        byMessage.clear();
        byUser.clear();
    }
}
