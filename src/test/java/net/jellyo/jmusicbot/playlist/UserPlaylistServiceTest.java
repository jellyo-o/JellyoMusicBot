package com.jagrosh.jmusicbot.playlist;

import com.jagrosh.jmusicbot.playlist.UserPlaylistService.PlaylistException;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.AddResult;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.PlaylistSummary;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.Share;
import com.jagrosh.jmusicbot.playlist.UserPlaylistService.ShareMode;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class UserPlaylistServiceTest
{
    @Test
    public void createAndEditPlaylistItemsByOneBasedIndex() throws Exception
    {
        UserPlaylistService service = newService();
        service.createPlaylist(1L, "Road Trip");
        service.addTracksToOwned(1L, "Road Trip", List.of(track("a", "A"), track("b", "B"), track("c", "C")));

        service.moveItem(1L, "Road Trip", 3, 1);
        service.removeItem(1L, "Road Trip", 2);

        List<PlaylistTrack> items = service.listItems(1L, "Road Trip");
        assertEquals(2, items.size());
        assertEquals("C", items.get(0).getTitle());
        assertEquals("B", items.get(1).getTitle());
    }

    @Test
    public void likedSongsAreAutoCreatedAndReserved() throws Exception
    {
        UserPlaylistService service = newService();

        PlaylistSummary liked = service.getOrCreateLikedPlaylist(1L);

        assertEquals(UserPlaylistService.LIKED_SONGS, liked.getName());
        assertTrue(liked.isLiked());
        assertThrows(PlaylistException.class, () -> service.createPlaylist(1L, UserPlaylistService.LIKED_SONGS));
    }

    @Test
    public void removeLikedSongsByTrackOrQuery() throws Exception
    {
        UserPlaylistService service = newService();
        service.getOrCreateLikedPlaylist(1L);
        service.addTracksToOwned(1L, UserPlaylistService.LIKED_SONGS, List.of(track("a", "Alpha"), track("b", "Beta")));

        assertTrue(service.removeTrack(1L, UserPlaylistService.LIKED_SONGS, track("a", "Different Alpha")).isPresent());
        List<PlaylistTrack> remaining = service.listItems(1L, UserPlaylistService.LIKED_SONGS);
        assertEquals(1, remaining.size());
        assertEquals("Beta", remaining.get(0).getTitle());

        assertTrue(service.removeFirstMatchingTrack(1L, UserPlaylistService.LIKED_SONGS, "bet").isPresent());
        assertTrue(service.listItems(1L, UserPlaylistService.LIKED_SONGS).isEmpty());
        assertFalse(service.removeFirstMatchingTrack(1L, UserPlaylistService.LIKED_SONGS, "missing").isPresent());
    }

    @Test
    public void copyShareCreatesEditableIndependentPlaylist() throws Exception
    {
        UserPlaylistService service = newService();
        service.createPlaylist(1L, "Owner Mix");
        service.addTracksToOwned(1L, "Owner Mix", List.of(track("a", "A")));
        Share share = service.createShare(1L, "Owner Mix", ShareMode.COPY);

        PlaylistSummary copy = service.addShared(2L, share.getCode(), "My Copy");
        service.addTracksToOwned(2L, "My Copy", List.of(track("b", "B")));

        assertFalse(copy.isFollowed());
        assertEquals(1, service.listItems(1L, "Owner Mix").size());
        assertEquals(2, service.listItems(2L, "My Copy").size());
    }

    @Test
    public void followShareIsReadOnlyAndReflectsOwnerEdits() throws Exception
    {
        UserPlaylistService service = newService();
        service.createPlaylist(1L, "Owner Mix");
        service.addTracksToOwned(1L, "Owner Mix", List.of(track("a", "A")));
        Share share = service.createShare(1L, "Owner Mix", ShareMode.FOLLOW);

        PlaylistSummary followed = service.addShared(2L, share.getCode(), "Followed Mix");
        service.addTracksToOwned(1L, "Owner Mix", List.of(track("b", "B")));

        assertTrue(followed.isFollowed());
        assertFalse(followed.isEditable());
        assertEquals(2, service.listItems(2L, "Followed Mix").size());
        assertThrows(PlaylistException.class, () -> service.addTracksToOwned(2L, "Followed Mix", List.of(track("c", "C"))));
    }

    @Test
    public void copyVisibleMakesFollowedPlaylistEditable() throws Exception
    {
        UserPlaylistService service = newService();
        service.createPlaylist(1L, "Owner Mix");
        service.addTracksToOwned(1L, "Owner Mix", List.of(track("a", "A")));
        Share share = service.createShare(1L, "Owner Mix", ShareMode.FOLLOW);
        service.addShared(2L, share.getCode(), "Followed Mix");

        PlaylistSummary copy = service.copyVisible(2L, "Followed Mix", "Editable Mix");
        service.addTracksToOwned(2L, "Editable Mix", List.of(track("b", "B")));

        assertTrue(copy.isEditable());
        assertEquals(2, service.listItems(2L, "Editable Mix").size());
        assertEquals(1, service.listItems(2L, "Followed Mix").size());
    }

    @Test
    public void followedPlaylistCannotShadowOwnedPlaylistName() throws Exception
    {
        UserPlaylistService service = newService();
        service.createPlaylist(1L, "Owner Mix");
        service.createPlaylist(2L, "Owner Mix");
        Share share = service.createShare(1L, "Owner Mix", ShareMode.FOLLOW);

        assertThrows(PlaylistException.class, () -> service.addShared(2L, share.getCode(), "Owner Mix"));
    }

    @Test
    public void duplicateTracksAreSkippedByUrl() throws Exception
    {
        UserPlaylistService service = newService();
        service.createPlaylist(1L, "Owner Mix");

        AddResult first = service.addTracksToOwned(1L, "Owner Mix", List.of(
                track("a", "A"),
                track("a", "Duplicate A"),
                track("b", "B")));
        AddResult second = service.addTrack(1L, "Owner Mix", track("a", "A Again"));

        assertEquals(2, first.getAdded());
        assertEquals(1, first.getSkippedDuplicates());
        assertEquals(0, second.getAdded());
        assertEquals(1, second.getSkippedDuplicates());
        List<PlaylistTrack> items = service.listItems(1L, "Owner Mix");
        assertEquals(2, items.size());
        assertEquals("A", items.get(0).getTitle());
        assertEquals("B", items.get(1).getTitle());
    }

    @Test
    public void duplicateTracksUseQueryWhenUrlIsMissing() throws Exception
    {
        UserPlaylistService service = newService();
        service.createPlaylist(1L, "Owner Mix");

        service.addTrack(1L, "Owner Mix", trackWithoutUrl("ytsearch:hello world", "Hello World"));
        AddResult duplicate = service.addTrack(1L, "Owner Mix", trackWithoutUrl(" YTSEARCH:HELLO   WORLD ", "Another Title"));

        assertEquals(0, duplicate.getAdded());
        assertEquals(1, duplicate.getSkippedDuplicates());
        assertEquals(1, service.listItems(1L, "Owner Mix").size());
    }

    @Test
    public void duplicateTrackKeysAreEnforcedByDatabase() throws Exception
    {
        Path db = newDbPath();
        UserPlaylistService service = new UserPlaylistService(db);
        service.init();
        PlaylistSummary playlist = service.createPlaylist(1L, "Owner Mix");
        PlaylistTrack track = track("a", "A");
        service.addTrack(1L, "Owner Mix", track);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
            PreparedStatement ps = connection.prepareStatement("INSERT INTO playlist_items"
                    + "(playlist_id, position, duplicate_key, query, created_at) VALUES(?,?,?,?,?)"))
        {
            ps.setLong(1, playlist.getId());
            ps.setInt(2, 2);
            ps.setString(3, track.getDuplicateKey());
            ps.setString(4, track.getQuery());
            ps.setLong(5, 1);

            assertThrows(SQLException.class, ps::executeUpdate);
        }
        assertEquals(1, service.listItems(1L, "Owner Mix").size());
    }

    @Test
    public void legacyDuplicateRowsDoNotBlockUniqueIndexMigration() throws Exception
    {
        Path db = newDbPath();
        createLegacyPlaylistDatabase(db);

        UserPlaylistService service = new UserPlaylistService(db);
        service.init();
        AddResult duplicate = service.addTrack(1L, "Legacy", track("a", "New A"));

        assertEquals(0, duplicate.getAdded());
        assertEquals(1, duplicate.getSkippedDuplicates());
        assertEquals(2, service.listItems(1L, "Legacy").size());
    }

    private UserPlaylistService newService() throws Exception
    {
        Path db = newDbPath();
        UserPlaylistService service = new UserPlaylistService(db);
        service.init();
        return service;
    }

    private Path newDbPath() throws Exception
    {
        Path db = Files.createTempFile("user-playlists-test", ".db");
        db.toFile().deleteOnExit();
        return db;
    }

    private void createLegacyPlaylistDatabase(Path db) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
            Statement st = connection.createStatement())
        {
            st.executeUpdate("CREATE TABLE playlists ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "owner_id INTEGER NOT NULL,"
                    + "name TEXT NOT NULL,"
                    + "normalized_name TEXT NOT NULL,"
                    + "liked INTEGER NOT NULL DEFAULT 0,"
                    + "legacy_shuffle INTEGER NOT NULL DEFAULT 0,"
                    + "created_at INTEGER NOT NULL,"
                    + "updated_at INTEGER NOT NULL,"
                    + "UNIQUE(owner_id, normalized_name)"
                    + ")");
            st.executeUpdate("CREATE TABLE playlist_items ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,"
                    + "position INTEGER NOT NULL,"
                    + "query TEXT NOT NULL,"
                    + "url TEXT,"
                    + "title TEXT,"
                    + "author TEXT,"
                    + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                    + "source TEXT,"
                    + "created_at INTEGER NOT NULL"
                    + ")");
            st.executeUpdate("INSERT INTO playlists(id, owner_id, name, normalized_name, liked, legacy_shuffle, created_at, updated_at) "
                    + "VALUES(1, 1, 'Legacy', 'legacy', 0, 0, 1, 1)");
            st.executeUpdate("INSERT INTO playlist_items(playlist_id, position, query, url, title, author, duration_ms, source, created_at) "
                    + "VALUES(1, 1, 'a', 'https://example.test/a', 'A', 'Artist', 1000, 'test', 1)");
            st.executeUpdate("INSERT INTO playlist_items(playlist_id, position, query, url, title, author, duration_ms, source, created_at) "
                    + "VALUES(1, 2, 'a', 'https://example.test/a', 'Duplicate A', 'Artist', 1000, 'test', 1)");
        }
    }

    private PlaylistTrack track(String query, String title)
    {
        return new PlaylistTrack(0, query, "https://example.test/" + query, title, "Artist", 1000, "test");
    }

    private PlaylistTrack trackWithoutUrl(String query, String title)
    {
        return new PlaylistTrack(0, query, null, title, "Artist", 1000, "test");
    }
}
