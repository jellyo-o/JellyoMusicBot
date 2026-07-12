package com.jagrosh.jmusicbot.lyrics;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public class LyricsService
{
    private final LyricsCache cache;
    private final LyricsProvider primaryProvider;
    private final DirectLyricsProvider fallbackProvider;

    public LyricsService(Path dbPath) throws Exception
    {
        this(new LyricsCache(dbPath), new LrclibLyricsProvider(), new GeniusLyricsProvider());
    }

    LyricsService(LyricsCache cache, LyricsProvider primaryProvider, DirectLyricsProvider fallbackProvider) throws SQLException
    {
        this.cache = cache;
        this.primaryProvider = primaryProvider;
        this.fallbackProvider = fallbackProvider;
        this.cache.init();
    }

    public Optional<LyricsCache.CachedLyrics> fetchAndCache(String rawQuery, boolean allowDifferentArtistFallback) throws IOException
    {
        return fetchAndCache(rawQuery, allowDifferentArtistFallback, true);
    }

    public Optional<LyricsCache.CachedLyrics> fetchAndCache(String rawQuery, boolean allowDifferentArtistFallback, boolean allowFallbackProvider) throws IOException
    {
        String sanitized = InputValidator.sanitizeQuery(rawQuery);
        if(sanitized == null)
            return Optional.empty();

        boolean force = Boolean.getBoolean("lyrics.forceRefresh");
        if(!force)
        {
            try
            {
                Optional<LyricsCache.CachedLyrics> cached = cache.findBestMatch(sanitized);
                if(cached.isPresent())
                    return cached;
            }
            catch(SQLException ignored)
            {
            }
            // Negative cache: skip the (rate-limited) providers if this query recently
            // returned nothing from any of them.
            try
            {
                if(cache.isRecentMiss(sanitized, missTtlMillis()))
                    return Optional.empty();
            }
            catch(SQLException ignored)
            {
            }
        }

        // Try LRCLIB (fast, unlimited), then Genius (rate-limited fallback). Track whether a
        // provider errored so a transient network failure is NOT recorded as a negative hit.
        boolean providerErrored = false;
        Optional<LyricsCache.CachedLyrics> primary = Optional.empty();
        try
        {
            primary = fetchFromProvider(primaryProvider, sanitized, allowDifferentArtistFallback);
        }
        catch(IOException ignored)
        {
            providerErrored = true;
        }
        if(primary.isPresent())
        {
            clearMissQuietly(sanitized);
            return primary;
        }

        // Preload/warm path: consult only the primary (LRCLIB) provider so background warming
        // never blocks on the rate-limited fallback. Do NOT negative-cache this miss, because the
        // providers were not fully consulted and an on-demand fetch must still be free to try Genius.
        if(!allowFallbackProvider)
            return Optional.empty();

        Optional<LyricsCache.CachedLyrics> fallback = Optional.empty();
        try
        {
            fallback = fetchFromProvider(fallbackProvider, sanitized, allowDifferentArtistFallback);
        }
        catch(IOException ignored)
        {
            providerErrored = true;
        }
        if(fallback.isPresent())
        {
            clearMissQuietly(sanitized);
            return fallback;
        }

        // Only remember a clean "no lyrics anywhere" result — never a network error.
        if(!providerErrored)
            recordMissQuietly(sanitized);
        return Optional.empty();
    }

    private static long missTtlMillis()
    {
        return Long.getLong("lyrics.missTtlMillis", 7L * 24 * 60 * 60 * 1000L);
    }

    private void recordMissQuietly(String query)
    {
        try { cache.recordMiss(query); } catch(SQLException ignored) {}
    }

    private void clearMissQuietly(String query)
    {
        try { cache.clearMiss(query); } catch(SQLException ignored) {}
    }

    public Optional<LyricsCache.CachedLyrics> fetchByGeniusUrl(String url) throws IOException
    {
        Optional<LyricsResult> result = fallbackProvider.fetchByUrl(url);
        if(result.isEmpty())
            return Optional.empty();
        try
        {
            return Optional.of(cache.insertOrUpdate(result.get(), Set.of(url)));
        }
        catch(SQLException ex)
        {
            return Optional.empty();
        }
    }

    public Optional<LyricsCache.CachedLyrics> replaceForQueryWithGeniusUrl(String url, String rawQuery) throws IOException
    {
        String sanitized = InputValidator.sanitizeQuery(rawQuery);
        if(sanitized == null)
            return Optional.empty();
        Optional<LyricsResult> result = fallbackProvider.fetchByUrl(url);
        if(result.isEmpty())
            return Optional.empty();
        try
        {
            return cache.replaceForQuery(sanitized, result.get(), lookupTerms(sanitized, result.get()));
        }
        catch(SQLException ex)
        {
            return Optional.empty();
        }
    }

    @Deprecated
    public Optional<LyricsCache.CachedLyrics> replaceLastWithFetched(String url) throws IOException
    {
        Optional<LyricsResult> result = fallbackProvider.fetchByUrl(url);
        if(result.isEmpty())
            return Optional.empty();
        try
        {
            return Optional.of(cache.insertOrUpdate(result.get(), Set.of(url)));
        }
        catch(SQLException ex)
        {
            return Optional.empty();
        }
    }

    private Optional<LyricsCache.CachedLyrics> fetchFromProvider(LyricsProvider provider, String query,
                                                                boolean allowDifferentArtistFallback) throws IOException
    {
        Optional<LyricsResult> result = provider.search(query, allowDifferentArtistFallback);
        if(result.isEmpty() || !result.get().hasLyrics())
            return Optional.empty();
        try
        {
            return Optional.of(cache.insertOrUpdate(result.get(), lookupTerms(query, result.get())));
        }
        catch(SQLException ex)
        {
            return Optional.empty();
        }
    }

    private Set<String> lookupTerms(String query, LyricsResult result)
    {
        Set<String> terms = new LinkedHashSet<>();
        terms.add(query);
        terms.addAll(InputValidator.lookupTerms(query));
        terms.add(result.artist() + " " + result.title());
        terms.add(result.artist() + " - " + result.title());
        terms.add(result.title());
        terms.add(result.sourceUrl());
        terms.add(result.sourceKey());
        terms.addAll(result.aliases());
        return terms;
    }
}
