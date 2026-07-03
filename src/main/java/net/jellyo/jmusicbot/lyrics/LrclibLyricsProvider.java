package com.jagrosh.jmusicbot.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

class LrclibLyricsProvider implements LyricsProvider
{
    private static final String BASE = "https://lrclib.net";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Long.getLong("lyrics.lrclibTimeoutMillis", 5_000L), TimeUnit.MILLISECONDS)
            .readTimeout(Long.getLong("lyrics.lrclibTimeoutMillis", 5_000L), TimeUnit.MILLISECONDS)
            .callTimeout(Long.getLong("lyrics.lrclibTimeoutMillis", 8_000L), TimeUnit.MILLISECONDS)
            .build();

    @Override
    public Optional<LyricsResult> search(String query, boolean allowDifferentArtistFallback) throws IOException
    {
        // When we know the artist (query is "Artist - Title"), ask LRCLIB for that
        // exact pairing first so the right track surfaces even if free-text q= buries it.
        String[] artistTitle = InputValidator.splitArtistTitle(query);
        if(artistTitle != null)
        {
            HttpUrl url = HttpUrl.parse(BASE + "/api/search").newBuilder()
                    .addQueryParameter("track_name", artistTitle[1])
                    .addQueryParameter("artist_name", artistTitle[0])
                    .build();
            Optional<LyricsResult> scoped = runSearch(url, query, query);
            if(scoped.isPresent())
                return scoped;
        }
        for(String providerQuery : InputValidator.providerQueries(query))
        {
            Optional<LyricsResult> result = searchOnce(providerQuery, query);
            if(result.isPresent())
                return result;
        }
        return Optional.empty();
    }

    private Optional<LyricsResult> searchOnce(String providerQuery, String scoringQuery) throws IOException
    {
        HttpUrl url = HttpUrl.parse(BASE + "/api/search").newBuilder()
                .addQueryParameter("q", providerQuery)
                .build();
        return runSearch(url, providerQuery, scoringQuery);
    }

    private Optional<LyricsResult> runSearch(HttpUrl url, String providerQuery, String scoringQuery) throws IOException
    {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "JellyoMusicBot lyrics lookup")
                .build();

        try(Response response = CLIENT.newCall(request).execute())
        {
            if(!response.isSuccessful() || response.body() == null)
                return Optional.empty();
            JsonNode root = MAPPER.readTree(response.body().byteStream());
            if(!root.isArray())
                return Optional.empty();

            List<LyricsResult> candidates = new ArrayList<>();
            for(JsonNode item : root)
            {
                LyricsResult candidate = toResult(item);
                if(candidate != null)
                    candidates.add(candidate);
            }
            return selectBest(scoringQuery, providerQuery, candidates);
        }
    }

    static Optional<LyricsResult> selectBest(String scoringQuery, String providerQuery, List<LyricsResult> candidates)
    {
        LyricsResult best = null;
        double bestScore = -1d;
        for(LyricsResult candidate : candidates)
        {
            if(candidate == null || !candidate.hasLyrics())
                continue;
            double s = Math.max(score(scoringQuery, candidate), score(providerQuery, candidate));
            if(s > bestScore)
            {
                bestScore = s;
                best = candidate;
            }
        }
        if(best != null && bestScore >= 0.12d)
            return Optional.of(best);
        return Optional.empty();
    }

    private LyricsResult toResult(JsonNode item)
    {
        if(item.path("instrumental").asBoolean(false))
            return null;

        String lyrics = item.path("plainLyrics").asText("");
        if(lyrics.isBlank())
            return null;

        String id = item.path("id").asText("");
        if(id.isBlank())
            return null;

        String artist = item.path("artistName").asText("");
        String title = item.path("trackName").asText(item.path("name").asText(""));
        if(title.isBlank())
            return null;

        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(artist + " " + title);
        aliases.add(artist + " - " + title);
        aliases.add(title);
        String album = item.path("albumName").asText("");
        if(!album.isBlank())
            aliases.add(album + " " + title);

        return new LyricsResult(
                "lrclib",
                id,
                "lrclib:" + id,
                BASE + "/lyrics/" + id,
                artist,
                title,
                lyrics,
                aliases
        );
    }

    static double score(String query, LyricsResult result)
    {
        String normalizedQuery = InputValidator.normalizeLookup(query);
        String normalizedTitle = InputValidator.normalizeLookup(result.title());
        String normalizedArtistTitle = InputValidator.normalizeLookup(result.artist() + " " + result.title());
        if(normalizedQuery.isEmpty())
            return 0d;
        if(normalizedArtistTitle.equals(normalizedQuery))
            return 1.0d;
        if(normalizedTitle.equals(normalizedQuery))
            return 0.85d;

        double score = tokenScore(normalizedQuery, normalizedArtistTitle);
        String[] artistTitle = InputValidator.splitArtistTitle(query);
        if(artistTitle != null)
        {
            String artist = InputValidator.normalizeLookup(artistTitle[0]);
            String title = InputValidator.normalizeLookup(artistTitle[1]);
            if(!artist.isEmpty() && InputValidator.normalizeLookup(result.artist()).contains(artist))
                score += 0.20d;
            if(!title.isEmpty() && normalizedTitle.contains(title))
                score += 0.20d;
        }
        return score;
    }

    static double tokenScore(String query, String candidate)
    {
        if(query.isEmpty() || candidate.isEmpty())
            return 0d;
        Set<String> q = new LinkedHashSet<>();
        Set<String> c = new LinkedHashSet<>();
        for(String token : query.split(" "))
            if(!token.isBlank())
                q.add(token);
        for(String token : candidate.split(" "))
            if(!token.isBlank())
                c.add(token);
        if(q.isEmpty() || c.isEmpty())
            return 0d;

        int intersection = 0;
        for(String token : q)
            if(c.contains(token))
                intersection++;
        int union = q.size() + c.size() - intersection;
        double score = union == 0 ? 0d : intersection / (double) union;
        if(candidate.contains(query))
            score += 0.15d;
        return score;
    }
}
