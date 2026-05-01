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
package com.jagrosh.jmusicbot.audio.filter;

import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Built-in audio filter presets available to guild audio players.
 */
public enum AudioFilterPreset
{
    OFF("off", "Off", "normal", "none", "clear", "reset"),
    BASS_BOOST("bassboost", "Bass Boost", "bass", "bass-boost", "bass_boost", "bb"),
    NIGHTCORE("nightcore", "Nightcore", "nc"),
    EIGHT_D("8d", "8D", "eightd", "eight-d", "rotation", "rotate"),
    VAPORWAVE("vaporwave", "Vaporwave", "vapor", "slowed", "slow"),
    TREMOLO("tremolo", "Tremolo", "pulse", "pulsing"),
    KARAOKE("karaoke", "Karaoke", "vocalremove", "vocal-remove", "vocalremover", "vocal-remover");

    private final String id;
    private final String displayName;
    private final String[] aliases;

    AudioFilterPreset(String id, String displayName, String... aliases)
    {
        this.id = id;
        this.displayName = displayName;
        this.aliases = aliases;
    }

    public String getId()
    {
        return id;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public boolean isEnabled()
    {
        return this != OFF;
    }

    public PcmFilterFactory createFactory()
    {
        return isEnabled() ? new AudioFilterChainFactory(this) : null;
    }

    public static Optional<AudioFilterPreset> fromInput(String input)
    {
        if(input == null)
            return Optional.empty();

        String normalized = normalize(input);
        if(normalized.isEmpty())
            return Optional.empty();

        return Arrays.stream(values())
                .filter(preset -> preset.matches(normalized))
                .findFirst();
    }

    public static String availablePresetList()
    {
        return Arrays.stream(values())
                .map(preset -> "`" + preset.getId() + "`")
                .collect(Collectors.joining(", "));
    }

    private boolean matches(String normalized)
    {
        return normalize(id).equals(normalized)
                || normalize(name()).equals(normalized)
                || Stream.of(aliases).map(AudioFilterPreset::normalize).anyMatch(normalized::equals);
    }

    private static String normalize(String value)
    {
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "");
    }
}
