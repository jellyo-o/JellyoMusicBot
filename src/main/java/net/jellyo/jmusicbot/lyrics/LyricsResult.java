package com.jagrosh.jmusicbot.lyrics;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public class LyricsResult
{
    private final String provider;
    private final String sourceId;
    private final String sourceKey;
    private final String sourceUrl;
    private final String artist;
    private final String title;
    private final String lyrics;
    private final Set<String> aliases;

    public LyricsResult(String provider, String sourceId, String sourceKey, String sourceUrl,
                        String artist, String title, String lyrics, Collection<String> aliases)
    {
        this.provider = provider == null ? "" : provider.trim();
        this.sourceId = sourceId == null ? "" : sourceId.trim();
        this.sourceKey = sourceKey == null ? "" : sourceKey.trim();
        this.sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
        this.artist = artist == null ? "" : artist.trim();
        this.title = title == null ? "" : title.trim();
        this.lyrics = InputValidator.sanitizeLyrics(lyrics);
        this.aliases = new LinkedHashSet<>();
        if(aliases != null)
            this.aliases.addAll(aliases);
    }

    public String provider()
    {
        return provider;
    }

    public String sourceId()
    {
        return sourceId;
    }

    public String sourceKey()
    {
        return sourceKey;
    }

    public String sourceUrl()
    {
        return sourceUrl;
    }

    public String artist()
    {
        return artist;
    }

    public String title()
    {
        return title;
    }

    public String lyrics()
    {
        return lyrics;
    }

    public Set<String> aliases()
    {
        return aliases;
    }

    public boolean hasLyrics()
    {
        return lyrics != null && !lyrics.isBlank();
    }
}
