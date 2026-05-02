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
package com.jagrosh.jmusicbot.utils;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class TrackIdentity
{
    private static final List<String> VERSION_LABELS = Arrays.asList(
            "official music video",
            "official lyric video",
            "official lyrics video",
            "official audio",
            "official video",
            "lyric video",
            "lyrics video",
            "music video",
            "official lyrics",
            "official lyric",
            "visualizer",
            "official",
            "lyrics",
            "lyric"
    );

    private static final List<String> ARTIST_SUFFIXES = Arrays.asList("official", "music", "vevo", "topic");

    private TrackIdentity()
    {
    }

    public static Set<String> keys(AudioTrack track)
    {
        Set<String> keys = new HashSet<>();
        if(track == null)
            return keys;

        String identifier = track.getIdentifier();
        String uri = track.getInfo() == null ? null : track.getInfo().uri;
        String title = track.getInfo() == null ? null : track.getInfo().title;
        String author = track.getInfo() == null ? null : track.getInfo().author;
        addKeys(keys, identifier, uri, title, author);
        return keys;
    }

    public static Set<String> keys(String identifier, String uri, String title, String author)
    {
        Set<String> keys = new HashSet<>();
        addKeys(keys, identifier, uri, title, author);
        return keys;
    }

    public static String songKey(String title, String author)
    {
        String normalizedTitle = normalizeTitle(title);
        if(normalizedTitle.isEmpty())
            return null;

        String normalizedAuthor = normalizeArtist(author);
        normalizedTitle = stripLeadingArtist(normalizedTitle, normalizedAuthor);
        if(normalizedTitle.length() < 3)
            return null;

        return "song:" + compact(normalizedAuthor) + ":" + compact(normalizedTitle);
    }

    private static String titleSongKey(String title)
    {
        if(title == null)
            return null;

        String[] parts = title.trim().split("\\s+[\\p{Pd}:|]\\s+", 2);
        if(parts.length < 2)
            return null;

        String normalizedArtist = normalizeArtist(parts[0]);
        String normalizedTitle = normalizeTitle(parts[1]);
        if(normalizedArtist.length() < 3 || normalizedTitle.length() < 3)
            return null;

        return "song:" + compact(normalizedArtist) + ":" + compact(normalizedTitle);
    }

    private static void addKeys(Set<String> keys, String identifier, String uri, String title, String author)
    {
        addNonBlank(keys, identifier);
        addNonBlank(keys, uri);
        addNonBlank(keys, songKey(title, author));
        addNonBlank(keys, titleSongKey(title));
    }

    private static void addNonBlank(Set<String> keys, String value)
    {
        if(value != null && !value.trim().isEmpty())
            keys.add(value.trim());
    }

    private static String normalizeTitle(String title)
    {
        String normalized = normalize(title);
        if(normalized.isEmpty())
            return "";

        String padded = " " + normalized + " ";
        for(String label : VERSION_LABELS)
            padded = padded.replace(" " + label + " ", " ");
        return padded.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeArtist(String author)
    {
        String normalized = normalize(author);
        if(normalized.isEmpty())
            return "";

        String[] tokens = normalized.split(" ");
        int end = tokens.length;
        while(end > 1 && ARTIST_SUFFIXES.contains(tokens[end - 1]))
            end--;

        String trimmed = String.join(" ", Arrays.copyOf(tokens, end));
        String compact = compact(trimmed);
        for(String suffix : ARTIST_SUFFIXES)
        {
            if(compact.endsWith(suffix) && compact.length() > suffix.length() + 2)
            {
                compact = compact.substring(0, compact.length() - suffix.length());
                break;
            }
        }
        if(compact.endsWith("ny") && compact.length() > 8)
            compact = compact.substring(0, compact.length() - 2);
        return compact;
    }

    private static String stripLeadingArtist(String title, String author)
    {
        String compactAuthor = compact(author);
        if(compactAuthor.isEmpty() || !compact(title).startsWith(compactAuthor))
            return title;

        String[] tokens = title.split(" ");
        int consumed = 0;
        for(int i = 0; i < tokens.length; i++)
        {
            consumed += tokens[i].length();
            if(consumed == compactAuthor.length())
                return join(tokens, i + 1).trim();
            if(consumed > compactAuthor.length())
                return title;
        }
        return title;
    }

    private static String join(String[] tokens, int start)
    {
        StringBuilder sb = new StringBuilder();
        for(int i = start; i < tokens.length; i++)
        {
            if(sb.length() > 0)
                sb.append(' ');
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    private static String normalize(String value)
    {
        if(value == null)
            return "";
        return value.toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String compact(String value)
    {
        return value == null ? "" : value.replace(" ", "");
    }
}
