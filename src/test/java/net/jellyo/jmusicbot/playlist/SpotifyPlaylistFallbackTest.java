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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpotifyPlaylistFallbackTest
{
    @Test
    public void detectsSpotifyPlaylistUrls()
    {
        assertEquals("37i9dQZF1E4CgIcIDlZSNP", SpotifyPlaylistFallback.playlistId(
                "<https://open.spotify.com/playlist/37i9dQZF1E4CgIcIDlZSNP?si=test>").orElse(""));
        assertEquals("37i9dQZF1E4CgIcIDlZSNP", SpotifyPlaylistFallback.playlistId(
                "https://open.spotify.com/intl-ja/playlist/37i9dQZF1E4CgIcIDlZSNP").orElse(""));
        assertTrue(SpotifyPlaylistFallback.isSpotifyPlaylistUrl(
                "https://open.spotify.com/embed/playlist/37i9dQZF1E4CgIcIDlZSNP"));
    }

    @Test
    public void parsesVisibleEmbedRowsIntoSpotifyTrackUrls()
    {
        String html = "<html><head><meta property=\"og:title\" content=\"Spotify Embed: POLKADOT STINGRAY Radio\"></head>"
                + "<body>"
                + "spotify:track:7dylAisHQrWgpwAQl3o4vb"
                + "spotify:track:66JwZjX7TWE6CEDLHP9c32"
                + "<ol>"
                + "<li data-testid=\"tracklist-row-0\"><h3>トゲめくスピカ</h3><h4>POLKADOT STINGRAY</h4></li>"
                + "<li data-testid=\"tracklist-row-1\"><h3>saredokijyutushihasaiwohuru</h3><h4>Lie and a Chameleon</h4></li>"
                + "</ol></body></html>";

        SpotifyPlaylistFallback.PublicPlaylist playlist = SpotifyPlaylistFallback.parse(html,
                "https://open.spotify.com/playlist/37i9dQZF1E4CgIcIDlZSNP");

        assertEquals("POLKADOT STINGRAY Radio", playlist.getName());
        assertEquals(2, playlist.getTracks().size());
        assertEquals("https://open.spotify.com/track/7dylAisHQrWgpwAQl3o4vb",
                playlist.getTracks().get(0).getLoadQuery());
        assertEquals("https://open.spotify.com/track/7dylAisHQrWgpwAQl3o4vb",
                playlist.getTracks().get(0).getSpotifyUrl());
    }

    @Test
    public void fallsBackToTrackUrlsWhenRowsAreMissing()
    {
        String html = "open.spotify.com/track/7dylAisHQrWgpwAQl3o4vb "
                + "spotify:track:7dylAisHQrWgpwAQl3o4vb "
                + "spotify:track:66JwZjX7TWE6CEDLHP9c32";

        SpotifyPlaylistFallback.PublicPlaylist playlist = SpotifyPlaylistFallback.parse(html,
                "https://open.spotify.com/playlist/37i9dQZF1E4CgIcIDlZSNP");

        assertEquals(2, playlist.getTracks().size());
        assertEquals("https://open.spotify.com/track/7dylAisHQrWgpwAQl3o4vb",
                playlist.getTracks().get(0).getLoadQuery());
    }

    @Test
    public void prefersMoreCompletePublicPlaylist()
    {
        SpotifyPlaylistFallback.PublicPlaylist page = SpotifyPlaylistFallback.parse(
                "spotify:track:7dylAisHQrWgpwAQl3o4vb", "https://open.spotify.com/playlist/37i9dQZF1E4CgIcIDlZSNP");
        SpotifyPlaylistFallback.PublicPlaylist embed = SpotifyPlaylistFallback.parse(
                "spotify:track:7dylAisHQrWgpwAQl3o4vb spotify:track:66JwZjX7TWE6CEDLHP9c32"
                        + "<li data-testid=\"tracklist-row-0\"><h3>Song 1</h3><h4>Artist 1</h4></li>"
                        + "<li data-testid=\"tracklist-row-1\"><h3>Song 2</h3><h4>Artist 2</h4></li>",
                "https://open.spotify.com/playlist/37i9dQZF1E4CgIcIDlZSNP");

        SpotifyPlaylistFallback.PublicPlaylist best = SpotifyPlaylistFallback.betterPlaylist(page, embed);

        assertEquals(2, best.getTracks().size());
        assertEquals("https://open.spotify.com/track/66JwZjX7TWE6CEDLHP9c32", best.getTracks().get(1).getLoadQuery());
    }

    @Test
    public void parsesNextDataTrackMetadata()
    {
        String html = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + "{\"props\":{\"pageProps\":{\"state\":{\"data\":{\"entity\":{\"trackList\":["
                + "{\"uri\":\"spotify:track:7dylAisHQrWgpwAQl3o4vb\",\"title\":\"トゲめくスピカ\",\"subtitle\":\"POLKADOT STINGRAY\"},"
                + "{\"uri\":\"spotify:track:66JwZjX7TWE6CEDLHP9c32\",\"title\":\"saredokijyutushihasaiwohuru\",\"subtitle\":\"Lie and a Chameleon\"}"
                + "]}}}}}}</script>";

        SpotifyPlaylistFallback.PublicPlaylist playlist = SpotifyPlaylistFallback.parse(html,
                "https://open.spotify.com/playlist/37i9dQZF1E4CgIcIDlZSNP");

        assertEquals(2, playlist.getTracks().size());
        assertEquals("saredokijyutushihasaiwohuru", playlist.getTracks().get(1).getTitle());
        assertEquals("Lie and a Chameleon", playlist.getTracks().get(1).getArtist());
        assertEquals("https://open.spotify.com/track/66JwZjX7TWE6CEDLHP9c32",
                playlist.getTracks().get(1).getLoadQuery());
    }
}
