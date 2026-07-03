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
package com.jagrosh.jmusicbot.commands;

import com.jagrosh.jmusicbot.settings.QueueType;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.AutoplayMode;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class CommandParsersTest
{
    @Test
    public void repeatModeAcceptsPrefixAndSlashAliases()
    {
        assertEquals(RepeatMode.ALL, CommandParsers.parseRepeatMode("", RepeatMode.OFF));
        assertEquals(RepeatMode.OFF, CommandParsers.parseRepeatMode("", RepeatMode.ALL));
        assertEquals(RepeatMode.OFF, CommandParsers.parseRepeatMode("off", RepeatMode.ALL));
        assertEquals(RepeatMode.ALL, CommandParsers.parseRepeatMode("on", RepeatMode.OFF));
        assertEquals(RepeatMode.ALL, CommandParsers.parseRepeatMode("true", RepeatMode.OFF));
        assertEquals(RepeatMode.ALL, CommandParsers.parseRepeatMode("all", RepeatMode.OFF));
        assertEquals(RepeatMode.SINGLE, CommandParsers.parseRepeatMode("one", RepeatMode.OFF));
        assertEquals(RepeatMode.SINGLE, CommandParsers.parseRepeatMode("single", RepeatMode.OFF));
    }

    @Test(expected = IllegalArgumentException.class)
    public void repeatModeRejectsInvalidMode()
    {
        CommandParsers.parseRepeatMode("invalid", RepeatMode.OFF);
    }

    @Test
    public void volumeAcceptsValidRange()
    {
        assertEquals(0, CommandParsers.parseVolume("0"));
        assertEquals(75, CommandParsers.parseVolume("75"));
        assertEquals(150, CommandParsers.parseVolume("150"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void volumeRejectsOutOfRange()
    {
        CommandParsers.parseVolume("151");
    }

    @Test(expected = NumberFormatException.class)
    public void volumeRejectsNonInteger()
    {
        CommandParsers.parseVolume("loud");
    }

    @Test
    public void skipPercentageAcceptsPlainAndPercentValues()
    {
        assertEquals(55, CommandParsers.parseSkipPercentage("55"));
        assertEquals(55, CommandParsers.parseSkipPercentage("55%"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void skipPercentageRejectsOutOfRange()
    {
        CommandParsers.parseSkipPercentage("101");
    }

    @Test
    public void queueTypeAcceptsConfiguredNames()
    {
        assertEquals(QueueType.FAIR, CommandParsers.parseQueueType("fair"));
        assertEquals(QueueType.LINEAR, CommandParsers.parseQueueType("linear"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void queueTypeRejectsInvalidName()
    {
        CommandParsers.parseQueueType("random");
    }

    @Test
    public void autoplayModeAcceptsPrefixAndSlashAliases()
    {
        assertEquals(AutoplayMode.SMART, CommandParsers.parseAutoplayMode("", AutoplayMode.OFF));
        assertEquals(AutoplayMode.OFF, CommandParsers.parseAutoplayMode("", AutoplayMode.SMART));
        assertEquals(AutoplayMode.OFF, CommandParsers.parseAutoplayMode("off", AutoplayMode.SMART));
        assertEquals(AutoplayMode.SMART, CommandParsers.parseAutoplayMode("on", AutoplayMode.OFF));
        assertEquals(AutoplayMode.SMART, CommandParsers.parseAutoplayMode("true", AutoplayMode.OFF));
        assertEquals(AutoplayMode.SMART, CommandParsers.parseAutoplayMode("radio", AutoplayMode.OFF));
        assertEquals(AutoplayMode.RELATED, CommandParsers.parseAutoplayMode("related", AutoplayMode.OFF));
        assertEquals(AutoplayMode.ARTIST, CommandParsers.parseAutoplayMode("artist", AutoplayMode.OFF));
        assertEquals(AutoplayMode.PLAYLIST, CommandParsers.parseAutoplayMode("playlist", AutoplayMode.OFF));
        assertEquals(AutoplayMode.SERVER, CommandParsers.parseAutoplayMode("server", AutoplayMode.OFF));
    }

    @Test(expected = IllegalArgumentException.class)
    public void autoplayModeRejectsInvalidMode()
    {
        CommandParsers.parseAutoplayMode("random", AutoplayMode.OFF);
    }

    @Test
    public void moveParsesTwoPositions()
    {
        assertArrayEquals(new int[]{2, 5}, CommandParsers.parseMove("2 5"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void moveRejectsMissingPosition()
    {
        CommandParsers.parseMove("2");
    }

    @Test(expected = NumberFormatException.class)
    public void moveRejectsNonIntegerPosition()
    {
        CommandParsers.parseMove("2 later");
    }

    @Test
    public void parseToggleEmptyFlipsCurrent()
    {
        org.junit.Assert.assertTrue(CommandParsers.parseToggle("", false));
        org.junit.Assert.assertFalse(CommandParsers.parseToggle("  ", true));
    }

    @Test
    public void parseToggleOnOff()
    {
        org.junit.Assert.assertTrue(CommandParsers.parseToggle("on", false));
        org.junit.Assert.assertTrue(CommandParsers.parseToggle("enable", false));
        org.junit.Assert.assertFalse(CommandParsers.parseToggle("off", true));
        org.junit.Assert.assertFalse(CommandParsers.parseToggle("disable", true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseToggleInvalidThrows()
    {
        CommandParsers.parseToggle("maybe", false);
    }
}
