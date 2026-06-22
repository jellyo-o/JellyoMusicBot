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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Fallback for Spotify-owned/generated playlists that are visible on open.spotify.com
 * but blocked from Spotify's public playlist API.
 */
public final class SpotifyPlaylistFallback
{
    private static final Pattern PLAYLIST_URL = Pattern.compile(
            "(?i)https?://open\\.spotify\\.com/(?:[^/?#]+/)*playlist/([A-Za-z0-9]+)");
    private static final Pattern TRACK_URI = Pattern.compile("spotify:track:([A-Za-z0-9]{22})");
    private static final Pattern TRACK_URL = Pattern.compile("(?i)open\\.spotify\\.com/track/([A-Za-z0-9]{22})");
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; JellyoMusicBot/1.0)";
    private static final int TIMEOUT_MILLIS = 15_000;

    private SpotifyPlaylistFallback()
    {
    }

    public static boolean isSpotifyPlaylistUrl(String query)
    {
        return playlistId(query).isPresent();
    }

    public static Optional<String> playlistId(String query)
    {
        if(query == null)
            return Optional.empty();
        String clean = query.trim();
        if(clean.startsWith("<") && clean.endsWith(">") && clean.length() > 1)
            clean = clean.substring(1, clean.length() - 1).trim();
        Matcher matcher = PLAYLIST_URL.matcher(clean);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static PublicPlaylist fetch(String query) throws IOException
    {
        String id = playlistId(query).orElseThrow(() -> new IOException("Not a Spotify playlist URL"));
        IOException failure = null;
        PublicPlaylist best = null;
        try
        {
            best = betterPlaylist(best, parse(fetchDocument(canonicalPlaylistUrl(id)).html(), canonicalPlaylistUrl(id)));
        }
        catch(IOException ex)
        {
            failure = ex;
        }

        try
        {
            best = betterPlaylist(best, parse(fetchDocument(embedPlaylistUrl(id)).html(), canonicalPlaylistUrl(id)));
        }
        catch(IOException ex)
        {
            if(failure == null)
                failure = ex;
        }

        if(best != null)
            return best;
        throw failure == null ? new IOException("Spotify public playlist fallback failed") : failure;
    }

    public static PublicPlaylist parse(String html, String playlistUrl)
    {
        Document document = Jsoup.parse(html == null ? "" : html);
        String name = cleanPlaylistName(firstNonBlank(
                meta(document, "meta[property=og:title]"),
                meta(document, "meta[name=twitter:title]"),
                document.title()));

        List<Track> jsonTracks = extractNextDataTracks(document);
        if(!jsonTracks.isEmpty())
            return new PublicPlaylist(name, playlistUrl, jsonTracks);

        List<String> trackIds = extractTrackIds(document.html());
        List<Track> rows = extractRows(document, trackIds);
        if(!rows.isEmpty())
            return new PublicPlaylist(name, playlistUrl, rows);

        List<Track> tracks = new ArrayList<>();
        for(String trackId : trackIds)
            tracks.add(new Track(trackId, spotifyTrackUrl(trackId), "", ""));
        return new PublicPlaylist(name, playlistUrl, tracks);
    }

    public static String fallbackNotice(String warningPrefix)
    {
        return warningPrefix + " Spotify fallback: loading from the public page. This may take longer.";
    }

    public static String fallbackFootnote(String warningPrefix)
    {
        return warningPrefix
                + " Found using Spotify public-page fallback. For more reliable results, copy this radio/editorial playlist into your own Spotify playlist and use that link.";
    }

    private static Document fetchDocument(String url) throws IOException
    {
        return Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MILLIS)
                .ignoreContentType(true)
                .get();
    }

    private static List<String> extractTrackIds(String html)
    {
        Set<String> ids = new LinkedHashSet<>();
        collectTrackIds(TRACK_URI.matcher(html), ids);
        collectTrackIds(TRACK_URL.matcher(html), ids);
        return new ArrayList<>(ids);
    }

    private static List<Track> extractNextDataTracks(Document document)
    {
        Element script = document.selectFirst("script#__NEXT_DATA__");
        if(script == null)
            return List.of();

        try
        {
            JSONObject root = new JSONObject(script.html());
            JSONObject entity = object(object(object(object(root, "props"), "pageProps"), "state"), "data");
            entity = object(entity, "entity");
            JSONArray trackList = entity == null ? null : entity.optJSONArray("trackList");
            if(trackList == null)
                return List.of();

            List<Track> tracks = new ArrayList<>();
            for(int i = 0; i < trackList.length(); i++)
            {
                JSONObject item = trackList.optJSONObject(i);
                if(item == null)
                    continue;
                Matcher matcher = TRACK_URI.matcher(item.optString("uri", ""));
                if(!matcher.find())
                    continue;
                String id = matcher.group(1);
                tracks.add(new Track(id, spotifyTrackUrl(id),
                        item.optString("title", ""), item.optString("subtitle", "")));
            }
            return tracks;
        }
        catch(RuntimeException ex)
        {
            return List.of();
        }
    }

    private static JSONObject object(JSONObject source, String key)
    {
        return source == null ? null : source.optJSONObject(key);
    }

    private static void collectTrackIds(Matcher matcher, Set<String> ids)
    {
        while(matcher.find())
            ids.add(matcher.group(1));
    }

    private static List<Track> extractRows(Document document, List<String> trackIds)
    {
        List<Track> tracks = new ArrayList<>();
        Map<String, Track> dedupe = new LinkedHashMap<>();
        int index = 0;
        for(Element row : document.select("[data-testid^=tracklist-row], li[class*=TracklistRow]"))
        {
            String title = row.select("h3").text().trim();
            String artist = row.select("h4").text().trim();
            if(title.isBlank())
                continue;

            String id = index < trackIds.size() ? trackIds.get(index) : "";
            String url = id.isBlank() ? "" : spotifyTrackUrl(id);
            Track track = new Track(id, url, title, artist);
            String key = track.getLoadQuery().toLowerCase();
            dedupe.putIfAbsent(key, track);
            index++;
        }
        tracks.addAll(dedupe.values());
        return tracks;
    }

    private static String meta(Document document, String selector)
    {
        Element element = document.selectFirst(selector);
        return element == null ? "" : element.attr("content");
    }

    private static String firstNonBlank(String... values)
    {
        for(String value : values)
            if(value != null && !value.trim().isEmpty())
                return value.trim();
        return "Spotify Playlist";
    }

    private static String cleanPlaylistName(String name)
    {
        String clean = name == null ? "" : name.trim();
        if(clean.startsWith("Spotify Embed:"))
            clean = clean.substring("Spotify Embed:".length()).trim();
        clean = clean.replaceFirst("(?i)\\s*\\|\\s*Spotify\\s*Playlist\\s*$", "");
        clean = clean.replaceFirst("(?i)\\s*\\|\\s*Spotify\\s*$", "");
        return clean.isBlank() ? "Spotify Playlist" : clean;
    }

    private static String canonicalPlaylistUrl(String id)
    {
        return "https://open.spotify.com/playlist/" + id;
    }

    private static String embedPlaylistUrl(String id)
    {
        return "https://open.spotify.com/embed/playlist/" + id;
    }

    private static String spotifyTrackUrl(String id)
    {
        return "https://open.spotify.com/track/" + id;
    }

    static PublicPlaylist betterPlaylist(PublicPlaylist current, PublicPlaylist candidate)
    {
        if(candidate == null)
            return current;
        if(current == null)
            return candidate;
        if(candidate.getTracks().size() != current.getTracks().size())
            return candidate.getTracks().size() > current.getTracks().size() ? candidate : current;
        return candidate.titledTrackCount() > current.titledTrackCount() ? candidate : current;
    }

    public static final class PublicPlaylist
    {
        private final String name;
        private final String url;
        private final List<Track> tracks;

        private PublicPlaylist(String name, String url, List<Track> tracks)
        {
            this.name = name;
            this.url = url;
            this.tracks = List.copyOf(tracks);
        }

        public String getName()
        {
            return name;
        }

        public String getUrl()
        {
            return url;
        }

        public List<Track> getTracks()
        {
            return tracks;
        }

        private int titledTrackCount()
        {
            int count = 0;
            for(Track track : tracks)
                if(!track.getTitle().isBlank())
                    count++;
            return count;
        }

        public List<PlaylistTrack> toPlaylistTracks()
        {
            List<PlaylistTrack> items = new ArrayList<>();
            for(Track track : tracks)
                items.add(new PlaylistTrack(0, track.getLoadQuery(), track.getSpotifyUrl(),
                        track.getTitle(), track.getArtist(), 0L, "spotify-fallback"));
            return items;
        }
    }

    public static final class Track
    {
        private final String spotifyId;
        private final String spotifyUrl;
        private final String title;
        private final String artist;

        private Track(String spotifyId, String spotifyUrl, String title, String artist)
        {
            this.spotifyId = spotifyId == null ? "" : spotifyId;
            this.spotifyUrl = spotifyUrl == null ? "" : spotifyUrl;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
        }

        public String getSpotifyId()
        {
            return spotifyId;
        }

        public String getSpotifyUrl()
        {
            return spotifyUrl;
        }

        public String getTitle()
        {
            return title;
        }

        public String getArtist()
        {
            return artist;
        }

        public String getLoadQuery()
        {
            if(!spotifyUrl.isBlank())
                return spotifyUrl;
            if(!title.isBlank())
                return "ytsearch:" + (artist.isBlank() ? title : title + " " + artist);
            return spotifyUrl;
        }
    }
}
