package com.jagrosh.jmusicbot.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.LinkedHashSet;
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

            LyricsResult best = null;
            double bestScore = -1d;
            for(JsonNode item : root)
            {
                LyricsResult candidate = toResult(item);
                if(candidate == null || !candidate.hasLyrics())
                    continue;
                double score = Math.max(score(scoringQuery, candidate), score(providerQuery, candidate));
                if(score > bestScore)
                {
                    bestScore = score;
                    best = candidate;
                }
            }

            if(best != null && bestScore >= 0.12d)
                return Optional.of(best);
            return Optional.empty();
        }
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

    private double score(String query, LyricsResult result)
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

    private double tokenScore(String query, String candidate)
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
