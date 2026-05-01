package com.jagrosh.jmusicbot.lyrics;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

class GeniusLyricsProvider implements DirectLyricsProvider
{
    private final GeniusClient client;

    GeniusLyricsProvider()
    {
        this(new GeniusClient());
    }

    GeniusLyricsProvider(GeniusClient client)
    {
        this.client = client;
    }

    @Override
    public Optional<LyricsResult> search(String query, boolean allowDifferentArtistFallback) throws IOException
    {
        Optional<GeniusClient.GeniusSong> songOpt = client.findSong(query, allowDifferentArtistFallback);
        if(songOpt.isEmpty())
            return Optional.empty();
        GeniusClient.GeniusSong match = songOpt.get();
        Optional<GeniusClient.GeniusSong> fetched = client.fetchSongPage(match.path());
        if(fetched.isEmpty())
            return Optional.empty();
        GeniusClient.GeniusSong song = mergeMetadata(match, fetched.get());
        return toResult(song);
    }

    @Override
    public Optional<LyricsResult> fetchByUrl(String url) throws IOException
    {
        String path = LyricsCache.extractPathFromUrl(url);
        if(path == null || !InputValidator.isValidGeniusPath(path))
            return Optional.empty();
        Optional<GeniusClient.GeniusSong> song = client.fetchSongPage(path);
        return song.flatMap(this::toResult);
    }

    private GeniusClient.GeniusSong mergeMetadata(GeniusClient.GeniusSong match, GeniusClient.GeniusSong fetched)
    {
        String artist = !match.artist().isBlank() ? match.artist() : fetched.artist();
        String title = !match.title().isBlank() ? match.title() : fetched.title();
        return new GeniusClient.GeniusSong(artist, title, match.path(), match.sourceUrl(), fetched.lyrics());
    }

    private Optional<LyricsResult> toResult(GeniusClient.GeniusSong song)
    {
        if(song.lyrics() == null || song.lyrics().isBlank())
            return Optional.empty();
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(song.artist() + " " + song.title());
        aliases.add(song.artist() + " - " + song.title());
        aliases.add(song.title());
        aliases.add(song.path());
        aliases.add(song.sourceUrl());

        return Optional.of(new LyricsResult(
                "genius",
                song.path(),
                song.path(),
                song.sourceUrl(),
                song.artist(),
                song.title(),
                song.lyrics(),
                aliases
        ));
    }
}
