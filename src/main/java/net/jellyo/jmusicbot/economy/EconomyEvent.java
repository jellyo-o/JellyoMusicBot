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
 * The kind of activity that just updated a user's profile. Emitted by
 * {@link EconomyService} to any registered {@link EconomyObserver} (notably the
 * achievement engine) so that time-sensitive or event-driven achievements can be
 * evaluated at the moment they happen, in addition to threshold checks.
 */
public enum EconomyEvent
{
    SONG_REQUESTED,
    LISTENED,
    DAILY_CLAIMED,
    GAMBLE_WON,
    GAMBLE_LOST,
    GUESS_CORRECT,
    GUESS_WON,
    GAME_PLAYED
}
