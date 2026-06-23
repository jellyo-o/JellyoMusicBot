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
package com.jagrosh.jmusicbot.guessmusic;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Looks up an artist's own catalogue from the Spotify Web API so the guess-music discovery pool can
 * be seeded with real songs by the artist — including album/single deep cuts, not just their handful
 * of most-streamed hits — instead of freeform YouTube searches that surface fan covers and other
 * artists' songs.
 *
 * <p>It enumerates the artist's albums and singles (excluding {@code appears_on}/compilations and
 * obviously-named cover/tribute releases) rather than only top-tracks, so games stay varied and
 * require real effort. Officially-released covers an artist puts on their own albums are filtered
 * later, at round time, by {@link CoverDetector}.
 *
 * <p>Entirely best-effort: disabled unless Spotify credentials are configured, and every failure
 * (network error, rate limit, unknown artist) yields an empty list so the caller falls back to
 * keyword-based discovery. Uses the client-credentials flow with the same Client ID/Secret the bot
 * already configures for lavasrc; results are cached per artist for the bot's lifetime.
 */
public class SpotifyArtistCatalog
{
    private static final Logger LOG = LoggerFactory.getLogger(SpotifyArtistCatalog.class);
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String API_BASE = "https://api.spotify.com/v1";
    private static final String MARKET = "US";
    private static final long TOKEN_REFRESH_BUFFER_MS = 60_000L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_CACHE_ENTRIES = 500;
    private static final int MAX_ALBUM_PAGES = 2;   // up to 100 albums/singles considered per artist
    private static final int MAX_ALBUMS = 40;
    private static final int ALBUM_BATCH = 20;      // Spotify GET /albums accepts up to 20 ids
    private static final int MAX_SONGS = 80;        // cap the per-artist discovery breadth
    private static final java.util.regex.Pattern COVER_ALBUM_NAME = java.util.regex.Pattern.compile(
            "\\b(?:covers?|covered|tribute|tributes|karaoke|karaokes|originally)\\b");

    private final String clientId;
    private final String clientSecret;
    private final boolean enabled;
    private final HttpClient http;
    private final Map<String, List<Song>> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, List<Song>>(16, 0.75f, true)
            {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Song>> eldest)
                {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });
    private String accessToken;
    private long accessTokenExpiresAt;

    public SpotifyArtistCatalog(String clientId, String clientSecret)
    {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.enabled = !this.clientId.isEmpty() && !this.clientSecret.isEmpty()
                && !"NONE".equalsIgnoreCase(this.clientId) && !"NONE".equalsIgnoreCase(this.clientSecret);
        this.http = enabled ? HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build() : null;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    /** Returns the artist's catalogue songs (title + artist), or an empty list on any failure. */
    public List<Song> originalSongs(String artistName)
    {
        if(!enabled || artistName == null || artistName.isBlank())
            return Collections.emptyList();
        String key = artistName.trim().toLowerCase(Locale.ROOT);
        List<Song> cached = cache.get(key);
        if(cached != null)
            return cached;
        List<Song> songs = fetchOriginalSongs(artistName.trim());
        if(songs == null) // transient failure: do NOT poison the cache, retry on a later game
            return Collections.emptyList();
        cache.put(key, songs); // cache definitive answers (including a genuine empty) to avoid re-hitting the API
        return songs;
    }

    /**
     * @return the artist's tracks, an empty list for a definitive "no such artist"/"no tracks" answer
     *         (worth caching), or {@code null} on a transient failure the caller should not cache.
     */
    private List<Song> fetchOriginalSongs(String artistName)
    {
        try
        {
            String token = ensureToken();
            if(token == null)
                return null;
            String searchBody = get(token, API_BASE + "/search?type=artist&limit=5&q="
                    + URLEncoder.encode(artistName, StandardCharsets.UTF_8));
            if(searchBody == null)
                return null;
            String artistId = parseArtistId(searchBody, artistName);
            if(artistId == null)
                return Collections.emptyList(); // definitive: no matching artist

            List<String> albumIds = new ArrayList<>();
            String albumsUrl = API_BASE + "/artists/" + artistId
                    + "/albums?include_groups=album,single&market=" + MARKET + "&limit=50";
            for(int page = 0; page < MAX_ALBUM_PAGES && albumsUrl != null && albumIds.size() < MAX_ALBUMS; page++)
            {
                String body = get(token, albumsUrl);
                if(body == null)
                    return null; // transient: don't cache a truncated catalogue
                AlbumPage parsed = parseAlbumPage(body);
                albumIds.addAll(parsed.albumIds);
                albumsUrl = parsed.next;
            }
            return finishCatalog(token, albumIds, artistName);
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt(); // let the enrichment loop observe the cancellation
            return null;
        }
        catch(Exception ex)
        {
            LOG.debug("Spotify artist catalog lookup failed for '{}'", artistName, ex);
            return null;
        }
    }

    private List<Song> finishCatalog(String token, List<String> albumIds, String artistName) throws Exception
    {
        if(albumIds.isEmpty())
            return Collections.emptyList();
        LinkedHashMap<String, Song> songs = new LinkedHashMap<>();
        for(int i = 0; i < albumIds.size() && songs.size() < MAX_SONGS; i += ALBUM_BATCH)
        {
            List<String> batch = albumIds.subList(i, Math.min(albumIds.size(), i + ALBUM_BATCH));
            String body = get(token, API_BASE + "/albums?market=" + MARKET + "&ids=" + String.join(",", batch));
            if(body == null)
                return null; // transient batch failure: don't cache a partial catalogue
            for(Song song : parseAlbumTracks(body, artistName))
            {
                songs.putIfAbsent(normalizeName(song.getTitle()), song);
                if(songs.size() >= MAX_SONGS)
                    break;
            }
        }
        return new ArrayList<>(songs.values());
    }

    private synchronized String ensureToken() throws Exception
    {
        if(accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt - TOKEN_REFRESH_BUFFER_MS)
            return accessToken;

        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if(response.statusCode() != 200)
        {
            LOG.warn("Spotify token request failed with status {}", response.statusCode());
            accessToken = null;
            return null;
        }
        JSONObject json = new JSONObject(response.body());
        accessToken = json.optString("access_token", null);
        accessTokenExpiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L;
        return accessToken;
    }

    private String get(String token, String url) throws Exception
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if(response.statusCode() == 200)
            return response.body();
        LOG.debug("Spotify API GET {} returned status {}", url, response.statusCode());
        return null;
    }

    // ---- pure parsing (unit-tested) ----

    static String parseArtistId(String json, String wantedName)
    {
        if(json == null)
            return null;
        JSONObject artists = new JSONObject(json).optJSONObject("artists");
        JSONArray items = artists == null ? null : artists.optJSONArray("items");
        if(items == null || items.isEmpty())
            return null;

        String wanted = normalizeName(wantedName);
        String firstId = null;
        for(int i = 0; i < items.length(); i++)
        {
            JSONObject artist = items.optJSONObject(i);
            if(artist == null)
                continue;
            String id = artist.optString("id", "");
            if(id.isEmpty())
                continue;
            if(firstId == null)
                firstId = id;
            if(normalizeName(artist.optString("name", "")).equals(wanted))
                return id; // prefer an exact name match over Spotify's relevance ordering
        }
        return firstId;
    }

    static AlbumPage parseAlbumPage(String json)
    {
        if(json == null)
            return new AlbumPage(Collections.emptyList(), null);
        JSONObject root = new JSONObject(json);
        String next = root.isNull("next") ? null : root.optString("next", null);
        JSONArray items = root.optJSONArray("items");
        List<String> ids = new ArrayList<>();
        if(items != null)
        {
            for(int i = 0; i < items.length(); i++)
            {
                JSONObject album = items.optJSONObject(i);
                if(album == null)
                    continue;
                String id = album.optString("id", "");
                if(!id.isEmpty() && !isCoverAlbumName(album.optString("name", "")))
                    ids.add(id);
            }
        }
        return new AlbumPage(ids, next);
    }

    static boolean isCoverAlbumName(String name)
    {
        if(name == null || name.isBlank())
            return false;
        return COVER_ALBUM_NAME.matcher(name.toLowerCase(Locale.ROOT)).find();
    }

    static List<Song> parseAlbumTracks(String json, String fallbackArtist)
    {
        if(json == null)
            return Collections.emptyList();
        JSONArray albums = new JSONObject(json).optJSONArray("albums");
        if(albums == null)
            return Collections.emptyList();

        LinkedHashMap<String, Song> songs = new LinkedHashMap<>();
        for(int a = 0; a < albums.length(); a++)
        {
            JSONObject album = albums.optJSONObject(a);
            if(album == null)
                continue;
            JSONObject tracks = album.optJSONObject("tracks");
            JSONArray items = tracks == null ? null : tracks.optJSONArray("items");
            if(items == null)
                continue;
            for(int t = 0; t < items.length(); t++)
            {
                JSONObject track = items.optJSONObject(t);
                if(track == null)
                    continue;
                String title = track.optString("name", "").trim();
                if(title.isEmpty())
                    continue;
                String artist = primaryArtist(track, fallbackArtist);
                songs.putIfAbsent((artist + " " + title).toLowerCase(Locale.ROOT), new Song(title, artist));
            }
        }
        return new ArrayList<>(songs.values());
    }

    private static String primaryArtist(JSONObject track, String fallbackArtist)
    {
        JSONArray artists = track.optJSONArray("artists");
        if(artists != null && !artists.isEmpty())
        {
            JSONObject first = artists.optJSONObject(0);
            if(first != null)
            {
                String name = first.optString("name", "").trim();
                if(!name.isEmpty())
                    return name;
            }
        }
        return fallbackArtist == null ? "" : fallbackArtist;
    }

    private static String normalizeName(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    static final class AlbumPage
    {
        final List<String> albumIds;
        final String next;

        AlbumPage(List<String> albumIds, String next)
        {
            this.albumIds = albumIds;
            this.next = next;
        }
    }

    public static final class Song
    {
        private final String title;
        private final String artist;

        public Song(String title, String artist)
        {
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
        }

        public String getTitle()
        {
            return title;
        }

        public String getArtist()
        {
            return artist;
        }
    }
}
