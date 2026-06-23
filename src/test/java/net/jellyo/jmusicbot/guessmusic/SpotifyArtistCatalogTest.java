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

import com.jagrosh.jmusicbot.guessmusic.SpotifyArtistCatalog.AlbumPage;
import com.jagrosh.jmusicbot.guessmusic.SpotifyArtistCatalog.Song;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SpotifyArtistCatalogTest
{
    @Test
    public void disabledWithoutCredentials()
    {
        assertFalse(new SpotifyArtistCatalog("NONE", "NONE").isEnabled());
        assertFalse(new SpotifyArtistCatalog("", "").isEnabled());
        assertFalse(new SpotifyArtistCatalog(null, null).isEnabled());
        assertTrue(new SpotifyArtistCatalog("an-id", "a-secret").isEnabled());
        // A disabled catalog never makes a network call and just returns nothing.
        assertTrue(new SpotifyArtistCatalog("NONE", "NONE").originalSongs("NewJeans").isEmpty());
    }

    @Test
    public void parseArtistIdPrefersExactNameMatch()
    {
        String json = "{\"artists\":{\"items\":["
                + "{\"id\":\"close1\",\"name\":\"Newjeans Tribute Band\"},"
                + "{\"id\":\"exact1\",\"name\":\"NewJeans\"}"
                + "]}}";
        assertEquals("exact1", SpotifyArtistCatalog.parseArtistId(json, "newjeans"));
    }

    @Test
    public void parseArtistIdFallsBackToFirstWhenNoExactMatch()
    {
        String json = "{\"artists\":{\"items\":["
                + "{\"id\":\"first1\",\"name\":\"The Weekndz\"},"
                + "{\"id\":\"second1\",\"name\":\"Weekend Players\"}"
                + "]}}";
        assertEquals("first1", SpotifyArtistCatalog.parseArtistId(json, "The Weeknd"));
    }

    @Test
    public void parseArtistIdHandlesEmptyOrNull()
    {
        assertNull(SpotifyArtistCatalog.parseArtistId(null, "x"));
        assertNull(SpotifyArtistCatalog.parseArtistId("{\"artists\":{\"items\":[]}}", "x"));
        assertNull(SpotifyArtistCatalog.parseArtistId("{}", "x"));
    }

    @Test
    public void parseAlbumPageKeepsOriginalsAndSkipsCoverReleases()
    {
        String json = "{\"next\":\"https://api.spotify.com/next\",\"items\":["
                + "{\"id\":\"alb1\",\"name\":\"Debut Album\"},"
                + "{\"id\":\"alb2\",\"name\":\"Acoustic Covers\"},"        // cover release -> skipped
                + "{\"id\":\"alb3\",\"name\":\"A Tribute to Queen\"},"     // tribute -> skipped
                + "{\"id\":\"alb4\",\"name\":\"Greatest Hits\"}"
                + "]}";
        AlbumPage page = SpotifyArtistCatalog.parseAlbumPage(json);

        assertEquals(List.of("alb1", "alb4"), page.albumIds);
        assertEquals("https://api.spotify.com/next", page.next);
    }

    @Test
    public void parseAlbumPageHandlesNullNextAndEmpty()
    {
        AlbumPage page = SpotifyArtistCatalog.parseAlbumPage("{\"next\":null,\"items\":[]}");
        assertTrue(page.albumIds.isEmpty());
        assertNull(page.next);
        assertTrue(SpotifyArtistCatalog.parseAlbumPage(null).albumIds.isEmpty());
    }

    @Test
    public void coverAlbumNameDetectionUsesWordBoundaries()
    {
        assertTrue(SpotifyArtistCatalog.isCoverAlbumName("Punk Goes Pop: Covers"));
        assertTrue(SpotifyArtistCatalog.isCoverAlbumName("A Tribute Album"));
        assertTrue(SpotifyArtistCatalog.isCoverAlbumName("Karaoke Hits"));
        assertFalse(SpotifyArtistCatalog.isCoverAlbumName("Discovery"));
        assertFalse(SpotifyArtistCatalog.isCoverAlbumName("Greatest Hits"));
        assertFalse(SpotifyArtistCatalog.isCoverAlbumName(""));
    }

    @Test
    public void parseAlbumTracksCollectsDeepCutsAndDedupes()
    {
        String json = "{\"albums\":["
                + "{\"tracks\":{\"items\":["
                + "  {\"name\":\"Super Shy\",\"artists\":[{\"name\":\"NewJeans\"}]},"
                + "  {\"name\":\"Cool With You\",\"artists\":[{\"name\":\"NewJeans\"}]},"
                + "  {\"name\":\"\",\"artists\":[{\"name\":\"NewJeans\"}]}"
                + "]}},"
                + "{\"tracks\":{\"items\":["
                + "  {\"name\":\"Super Shy\",\"artists\":[{\"name\":\"NewJeans\"}]}," // duplicate across albums
                + "  {\"name\":\"Ditto\",\"artists\":[{\"name\":\"NewJeans\"},{\"name\":\"Feature\"}]}"
                + "]}}"
                + "]}";
        List<Song> songs = SpotifyArtistCatalog.parseAlbumTracks(json, "fallback");

        assertEquals(3, songs.size()); // blank skipped, duplicate collapsed
        assertEquals("Super Shy", songs.get(0).getTitle());
        assertEquals("Cool With You", songs.get(1).getTitle());
        assertEquals("Ditto", songs.get(2).getTitle());
        assertEquals("NewJeans", songs.get(2).getArtist()); // primary (first) artist only
    }

    @Test
    public void parseAlbumTracksUsesFallbackArtistWhenMissing()
    {
        String json = "{\"albums\":[{\"tracks\":{\"items\":[{\"name\":\"Song\"}]}}]}";
        List<Song> songs = SpotifyArtistCatalog.parseAlbumTracks(json, "Fallback Artist");

        assertEquals(1, songs.size());
        assertEquals("Fallback Artist", songs.get(0).getArtist());
    }

    @Test
    public void parseAlbumTracksHandlesEmptyOrNull()
    {
        assertTrue(SpotifyArtistCatalog.parseAlbumTracks(null, "x").isEmpty());
        assertTrue(SpotifyArtistCatalog.parseAlbumTracks("{}", "x").isEmpty());
        assertTrue(SpotifyArtistCatalog.parseAlbumTracks("{\"albums\":[]}", "x").isEmpty());
    }
}
