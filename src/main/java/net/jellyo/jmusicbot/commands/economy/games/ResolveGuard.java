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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A one-shot resolution latch. An interactive game can be resolved from several
 * threads at once — a button click (JDA thread), an inactivity timeout (games
 * scheduler) and, for crash, the crash tick — and each of those may imply a
 * <i>different</i> outcome (button = win, tick = loss). {@link #claim()} returns
 * {@code true} to exactly one caller, so a game settles precisely once: the
 * winner of the race performs the settlement and everyone else no-ops. This is
 * the single guarantee that prevents a stranded debit or a double payout.
 *
 * <p>Deliberately free of JDA/Bot dependencies so the critical property is unit
 * testable under real thread contention.
 */
public final class ResolveGuard
{
    private final AtomicBoolean resolved = new AtomicBoolean(false);

    /** @return {@code true} to exactly one caller across all threads; {@code false} thereafter */
    public boolean claim()
    {
        return resolved.compareAndSet(false, true);
    }

    public boolean isResolved()
    {
        return resolved.get();
    }
}
