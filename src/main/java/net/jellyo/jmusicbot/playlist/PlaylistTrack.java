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
package com.jagrosh.jmusicbot.playlist;

import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import java.util.Locale;

/**
 * Persistable track metadata for user playlists.
 */
public class PlaylistTrack
{
    private final long id;
    private final String query;
    private final String url;
    private final String title;
    private final String author;
    private final long duration;
    private final String source;

    public PlaylistTrack(long id, String query, String url, String title, String author, long duration, String source)
    {
        this.id = id;
        this.query = query;
        this.url = url;
        this.title = title;
        this.author = author;
        this.duration = duration;
        this.source = source;
    }

    public static PlaylistTrack fromAudioTrack(AudioTrack track, String fallbackQuery)
    {
        AudioTrackInfo info = track.getInfo();
        RequestMetadata metadata = track.getUserData(RequestMetadata.class);
        String metadataUrl = metadata == null || metadata.requestInfo == null ? null : metadata.requestInfo.url;
        String metadataQuery = metadata == null || metadata.requestInfo == null ? null : metadata.requestInfo.query;
        String url = firstNonBlank(info.uri, metadataUrl);
        String query = firstNonBlank(url, metadataQuery, fallbackQuery, info.title);
        String source = track.getSourceManager() == null ? null : track.getSourceManager().getSourceName();
        return new PlaylistTrack(0, query, url, info.title, info.author, track.getDuration(), source);
    }

    public static PlaylistTrack fromLegacyItem(String query)
    {
        return new PlaylistTrack(0, query, query, null, null, 0, null);
    }

    private static String firstNonBlank(String... values)
    {
        for(String value : values)
            if(value != null && !value.trim().isEmpty())
                return value.trim();
        return "";
    }

    public long getId()
    {
        return id;
    }

    public String getQuery()
    {
        return query;
    }

    public String getUrl()
    {
        return url;
    }

    public String getTitle()
    {
        return title;
    }

    public String getAuthor()
    {
        return author;
    }

    public long getDuration()
    {
        return duration;
    }

    public String getSource()
    {
        return source;
    }

    public String getDisplayTitle()
    {
        if(title != null && !title.isBlank())
            return title;
        if(query != null && !query.isBlank())
            return query;
        return "Unknown track";
    }

    public String getLoadQuery()
    {
        if(query != null && !query.isBlank())
            return query;
        if(url != null && !url.isBlank())
            return url;
        return getDisplayTitle();
    }

    public String getDuplicateKey()
    {
        String cleanUrl = normalizeDuplicateValue(url);
        if(!cleanUrl.isEmpty())
            return "url:" + cleanUrl;

        String cleanQuery = normalizeDuplicateValue(query);
        if(!cleanQuery.isEmpty())
            return "query:" + cleanQuery;

        String metadata = firstNonBlank(author) + " " + getDisplayTitle();
        return "title:" + normalizeDuplicateValue(metadata);
    }

    private static String normalizeDuplicateValue(String value)
    {
        if(value == null)
            return "";
        String clean = value.trim();
        if(clean.startsWith("<") && clean.endsWith(">") && clean.length() > 1)
            clean = clean.substring(1, clean.length() - 1).trim();
        int fragment = clean.indexOf('#');
        if(fragment >= 0)
            clean = clean.substring(0, fragment);
        return clean.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
