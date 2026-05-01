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
package com.jagrosh.jmusicbot.settings;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum AutoplayMode
{
    OFF("Off"),
    SMART("Smart"),
    RELATED("Related"),
    ARTIST("Artist"),
    PLAYLIST("Playlist"),
    SERVER("Server Favorites");

    private final String userFriendlyName;

    AutoplayMode(String userFriendlyName)
    {
        this.userFriendlyName = userFriendlyName;
    }

    public String getUserFriendlyName()
    {
        return userFriendlyName;
    }

    public static List<String> getNames()
    {
        return Arrays.stream(values())
                .map(mode -> mode.name().toLowerCase())
                .collect(Collectors.toList());
    }
}
