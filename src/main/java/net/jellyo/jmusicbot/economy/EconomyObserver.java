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

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/**
 * Notified after a user's profile changes. Implemented by the achievement engine
 * so it can grant achievements in response to activity. Implementations must be
 * fast and must not throw — the economy layer invokes them inline after writes.
 */
@FunctionalInterface
public interface EconomyObserver
{
    /**
     * @param userId    the affected user's snowflake
     * @param event     what just happened
     * @param localHour hour of day (0-23, host local time) the event occurred,
     *                  used by time-based achievements such as "Night Owl"
     * @param announceChannel where unlocks should be announced (tagging the user),
     *                  or null to fall back to a DM (e.g. passive listening events)
     */
    void onEconomyEvent(long userId, EconomyEvent event, int localHour, MessageChannel announceChannel);
}
