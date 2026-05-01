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

/**
 * Small parsers shared by prefix and slash command entrypoints.
 */
public final class CommandParsers
{
    private CommandParsers()
    {
    }

    public static RepeatMode parseRepeatMode(String args, RepeatMode currentMode)
    {
        String normalized = normalize(args);
        if(normalized.isEmpty())
            return currentMode == RepeatMode.OFF ? RepeatMode.ALL : RepeatMode.OFF;
        if(normalized.equals("false") || normalized.equals("off"))
            return RepeatMode.OFF;
        if(normalized.equals("true") || normalized.equals("on") || normalized.equals("all"))
            return RepeatMode.ALL;
        if(normalized.equals("one") || normalized.equals("single"))
            return RepeatMode.SINGLE;
        throw new IllegalArgumentException("Invalid repeat mode");
    }

    public static int parseVolume(String args)
    {
        int volume = Integer.parseInt(normalize(args));
        if(volume < 0 || volume > 150)
            throw new IllegalArgumentException("Volume must be between 0 and 150");
        return volume;
    }

    public static int parseSkipPercentage(String args)
    {
        String normalized = normalize(args);
        if(normalized.endsWith("%"))
            normalized = normalized.substring(0, normalized.length() - 1);
        int percentage = Integer.parseInt(normalized);
        if(percentage < 0 || percentage > 100)
            throw new IllegalArgumentException("Skip percentage must be between 0 and 100");
        return percentage;
    }

    public static QueueType parseQueueType(String args)
    {
        return QueueType.valueOf(normalize(args).toUpperCase());
    }

    public static int parsePosition(String args)
    {
        return Integer.parseInt(normalize(args));
    }

    public static int[] parseMove(String args)
    {
        String[] parts = args == null ? new String[0] : args.trim().split("\\s+", 2);
        if(parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty())
            throw new IllegalArgumentException("Two positions are required");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    private static String normalize(String args)
    {
        return args == null ? "" : args.trim().toLowerCase();
    }
}
