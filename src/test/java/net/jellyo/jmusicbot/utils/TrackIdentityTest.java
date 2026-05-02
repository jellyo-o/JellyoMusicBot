package com.jagrosh.jmusicbot.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TrackIdentityTest
{
    @Test
    public void officialVideoVariantsShareSongKey()
    {
        String musicVideo = TrackIdentity.songKey("Against The Current - Gravity (Official Music Video)", "AgainstTheCurrentNY");
        String lyricVideo = TrackIdentity.songKey("Against The Current - Gravity [Official Lyrics Video]", "Against The Current - Topic");

        assertNotNull(musicVideo);
        assertEquals(musicVideo, lyricVideo);
    }

    @Test
    public void differentSongsDoNotShareSongKey()
    {
        String first = TrackIdentity.songKey("Against The Current - Gravity (Official Music Video)", "AgainstTheCurrentNY");
        String second = TrackIdentity.songKey("Against The Current - Legends Never Die (Official Video)", "AgainstTheCurrentNY");

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second);
    }

    @Test
    public void embeddedArtistTitleMatchesDifferentUploader()
    {
        assertTrue(TrackIdentity.keys("fan-upload", "https://example.test/fan-upload",
                "Against The Current - Gravity (Official Music Video)", "Random Channel")
                .contains(TrackIdentity.songKey("Against The Current - Gravity [Official Lyrics Video]", "Against The Current - Topic")));
    }
}
