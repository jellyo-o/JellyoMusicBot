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
package com.jagrosh.jmusicbot.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Colors the short logger/source name with a stable ANSI color.
 */
public class SourceColorConverter extends ClassicConverter
{
    private static final String RESET = "\u001B[0m";
    private static final String[] COLORS = {
            "\u001B[38;5;39m",  // blue
            "\u001B[38;5;43m",  // green
            "\u001B[38;5;141m", // purple
            "\u001B[38;5;208m", // orange
            "\u001B[38;5;45m",  // cyan
            "\u001B[38;5;203m", // red
            "\u001B[38;5;220m", // yellow
            "\u001B[38;5;111m", // periwinkle
            "\u001B[38;5;120m", // mint
            "\u001B[38;5;177m", // pink
            "\u001B[38;5;214m", // amber
            "\u001B[38;5;87m"   // aqua
    };

    private static final boolean COLOR_ENABLED = isColorEnabled();

    @Override
    public String convert(ILoggingEvent event)
    {
        String source = shortLoggerName(event.getLoggerName());
        if(!COLOR_ENABLED)
            return source;

        return COLORS[Math.floorMod(source.hashCode(), COLORS.length)] + source + RESET;
    }

    private static String shortLoggerName(String loggerName)
    {
        if(loggerName == null || loggerName.isEmpty())
            return "unknown";

        int dot = loggerName.lastIndexOf('.');
        return dot >= 0 && dot < loggerName.length() - 1 ? loggerName.substring(dot + 1) : loggerName;
    }

    private static boolean isColorEnabled()
    {
        String property = System.getProperty("jmusicbot.colorLogs", "true");
        if("false".equalsIgnoreCase(property))
            return false;
        if(System.getenv("NO_COLOR") != null)
            return false;

        String term = System.getenv("TERM");
        return term == null || !"dumb".equalsIgnoreCase(term);
    }
}
