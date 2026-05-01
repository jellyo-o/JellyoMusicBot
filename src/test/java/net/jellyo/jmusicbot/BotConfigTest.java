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
package com.jagrosh.jmusicbot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BotConfigTest
{
    @Test
    public void autoplayMaxDurationAcceptsSecondsAndTimeStrings()
    {
        assertEquals(600000L, BotConfig.parseAutoplayMaxDuration(600));
        assertEquals(600000L, BotConfig.parseAutoplayMaxDuration("10m"));
        assertEquals(510000L, BotConfig.parseAutoplayMaxDuration("8:30"));
        assertEquals(510000L, BotConfig.parseAutoplayMaxDuration("00:08:30"));
    }

    @Test
    public void autoplayMaxDurationDefaultsForMissingOrInvalidValues()
    {
        assertEquals(BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS, BotConfig.parseAutoplayMaxDuration(null));
        assertEquals(BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS, BotConfig.parseAutoplayMaxDuration(""));
        assertEquals(BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS, BotConfig.parseAutoplayMaxDuration("nope"));
        assertEquals(BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS, BotConfig.parseAutoplayMaxDuration(0));
        assertEquals(BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS, BotConfig.parseAutoplayMaxDuration("-5m"));
    }
}
