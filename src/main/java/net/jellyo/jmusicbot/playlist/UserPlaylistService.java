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

import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.playlist.PlaylistLoader.Playlist;
import com.jagrosh.jmusicbot.utils.OtherUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed user playlist storage.
 */
public class UserPlaylistService
{
    private final static Logger LOG = LoggerFactory.getLogger(UserPlaylistService.class);
    public final static String LIKED_SONGS = "Liked Songs";
    private final static String LEGACY_IMPORT_KEY = "legacy_playlist_imported";

    private final Path dbPath;
    private final SecureRandom random = new SecureRandom();
    private Connection connection;

    public UserPlaylistService(Path dbPath)
    {
        this.dbPath = dbPath;
    }

    public synchronized void init() throws SQLException
    {
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try(Statement st = connection.createStatement())
        {
            st.executeUpdate("PRAGMA foreign_keys = ON");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS playlist_meta ("
                    + "key TEXT PRIMARY KEY,"
                    + "value TEXT NOT NULL"
                    + ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS playlists ("
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
            st.executeUpdate("CREATE TABLE IF NOT EXISTS playlist_items ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,"
                    + "position INTEGER NOT NULL,"
                    + "duplicate_key TEXT,"
                    + "query TEXT NOT NULL,"
                    + "url TEXT,"
                    + "title TEXT,"
                    + "author TEXT,"
                    + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                    + "source TEXT,"
                    + "created_at INTEGER NOT NULL"
                    + ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS playlist_shares ("
                    + "code TEXT PRIMARY KEY,"
                    + "playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,"
                    + "mode TEXT NOT NULL,"
                    + "revoked INTEGER NOT NULL DEFAULT 0,"
                    + "created_at INTEGER NOT NULL"
                    + ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS playlist_follows ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "follower_id INTEGER NOT NULL,"
                    + "source_playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,"
                    + "display_name TEXT NOT NULL,"
                    + "normalized_name TEXT NOT NULL,"
                    + "created_at INTEGER NOT NULL,"
                    + "UNIQUE(follower_id, normalized_name),"
                    + "UNIQUE(follower_id, source_playlist_id)"
                    + ")");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_playlist_items_playlist_position ON playlist_items(playlist_id, position, id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_playlist_follows_user ON playlist_follows(follower_id)");
        }
        ensurePlaylistItemDuplicateKeys();
    }

    public synchronized void importLegacyPlaylists(BotConfig config, long ownerId)
    {
        ensureOpen();
        if(hasMeta(LEGACY_IMPORT_KEY))
            return;

        Path folder = OtherUtil.getPath(config.getPlaylistsFolder());
        if(!Files.exists(folder))
        {
            setMeta(LEGACY_IMPORT_KEY, "true");
            return;
        }

        PlaylistLoader loader = new PlaylistLoader(config);
        int imported = 0;
        for(String name : loader.getPlaylistNames())
        {
            if(findOwned(ownerId, name).isPresent())
                continue;
            Playlist playlist = loader.getPlaylist(name);
            if(playlist == null)
                continue;

            try
            {
                PlaylistSummary created = createPlaylist(ownerId, name, false, playlist.isShuffle());
                List<PlaylistTrack> tracks = new ArrayList<>();
                for(String item : playlist.getItems())
                    tracks.add(PlaylistTrack.fromLegacyItem(item));
                addTracks(created.getId(), tracks);
                imported++;
            }
            catch(PlaylistException ex)
            {
                LOG.warn("Skipping legacy playlist '{}': {}", name, ex.getMessage());
            }
        }
        setMeta(LEGACY_IMPORT_KEY, "true");
        LOG.info("Imported {} legacy file playlists into {}", imported, dbPath.toAbsolutePath());
    }

    public synchronized PlaylistSummary createPlaylist(long ownerId, String name)
    {
        return createPlaylist(ownerId, name, false, false);
    }

    public synchronized PlaylistSummary getOrCreateLikedPlaylist(long ownerId)
    {
        Optional<PlaylistSummary> existing = findOwned(ownerId, LIKED_SONGS);
        if(existing.isPresent())
            return existing.get();
        return createPlaylist(ownerId, LIKED_SONGS, true, false);
    }

    public synchronized List<PlaylistSummary> listPlaylists(long userId)
    {
        ensureOpen();
        List<PlaylistSummary> playlists = new ArrayList<>();
        String ownedSql = "SELECT p.id, p.owner_id, p.name, p.liked, p.legacy_shuffle, "
                + "(SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id=p.id) AS item_count "
                + "FROM playlists p WHERE p.owner_id=?";
        try(PreparedStatement ps = connection.prepareStatement(ownedSql))
        {
            ps.setLong(1, userId);
            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                    playlists.add(new PlaylistSummary(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"),
                            rs.getBoolean("liked"), rs.getBoolean("legacy_shuffle"), false, true, rs.getInt("item_count")));
            }
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to list playlists", ex);
        }

        String followedSql = "SELECT p.id, p.owner_id, f.display_name AS name, p.liked, p.legacy_shuffle, "
                + "(SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id=p.id) AS item_count "
                + "FROM playlist_follows f JOIN playlists p ON p.id=f.source_playlist_id WHERE f.follower_id=?";
        try(PreparedStatement ps = connection.prepareStatement(followedSql))
        {
            ps.setLong(1, userId);
            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                    playlists.add(new PlaylistSummary(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"),
                            rs.getBoolean("liked"), rs.getBoolean("legacy_shuffle"), true, false, rs.getInt("item_count")));
            }
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to list followed playlists", ex);
        }

        playlists.sort(Comparator
                .comparing((PlaylistSummary playlist) -> !playlist.isLiked())
                .thenComparing(playlist -> playlist.getName().toLowerCase(Locale.ROOT))
                .thenComparing(PlaylistSummary::isFollowed));
        return playlists;
    }

    public synchronized Optional<PlaylistSummary> resolveVisible(long userId, String name)
    {
        Optional<PlaylistSummary> owned = findOwned(userId, name);
        if(owned.isPresent())
            return owned;

        ensureOpen();
        String sql = "SELECT p.id, p.owner_id, f.display_name AS name, p.liked, p.legacy_shuffle, "
                + "(SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id=p.id) AS item_count "
                + "FROM playlist_follows f JOIN playlists p ON p.id=f.source_playlist_id "
                + "WHERE f.follower_id=? AND f.normalized_name=? LIMIT 1";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, userId);
            ps.setString(2, normalizeName(name));
            try(ResultSet rs = ps.executeQuery())
            {
                if(rs.next())
                    return Optional.of(new PlaylistSummary(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"),
                            rs.getBoolean("liked"), rs.getBoolean("legacy_shuffle"), true, false, rs.getInt("item_count")));
            }
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to resolve playlist", ex);
        }
        return Optional.empty();
    }

    public synchronized Optional<PlaylistSummary> findOwned(long ownerId, String name)
    {
        ensureOpen();
        String normalized = normalizeName(name);
        String sql = "SELECT p.id, p.owner_id, p.name, p.liked, p.legacy_shuffle, "
                + "(SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id=p.id) AS item_count "
                + "FROM playlists p WHERE p.owner_id=? AND p.normalized_name=? LIMIT 1";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, ownerId);
            ps.setString(2, normalized);
            try(ResultSet rs = ps.executeQuery())
            {
                if(rs.next())
                    return Optional.of(new PlaylistSummary(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("name"),
                            rs.getBoolean("liked"), rs.getBoolean("legacy_shuffle"), false, true, rs.getInt("item_count")));
            }
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to find playlist", ex);
        }
        return Optional.empty();
    }

    private boolean findFollowedName(long userId, String name)
    {
        ensureOpen();
        String sql = "SELECT 1 FROM playlist_follows WHERE follower_id=? AND normalized_name=? LIMIT 1";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, userId);
            ps.setString(2, normalizeName(name));
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next();
            }
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to check followed playlist names", ex);
        }
    }

    public synchronized List<PlaylistTrack> listItems(long userId, String name)
    {
        PlaylistSummary playlist = resolveVisible(userId, name)
                .orElseThrow(() -> new PlaylistException("Playlist `" + name + "` does not exist."));
        return listItems(playlist.getId());
    }

    public synchronized List<PlaylistTrack> listItems(long playlistId)
    {
        ensureOpen();
        List<PlaylistTrack> tracks = new ArrayList<>();
        String sql = "SELECT id, query, url, title, author, duration_ms, source FROM playlist_items "
                + "WHERE playlist_id=? ORDER BY position ASC, id ASC";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, playlistId);
            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                    tracks.add(mapTrack(rs));
            }
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to list playlist items", ex);
        }
        return tracks;
    }

    public synchronized AddResult addTrack(long userId, String name, PlaylistTrack track)
    {
        PlaylistSummary playlist = requireEditable(userId, name);
        return addTracks(playlist.getId(), List.of(track));
    }

    public synchronized AddResult addTracksToOwned(long userId, String name, List<PlaylistTrack> tracks)
    {
        PlaylistSummary playlist = requireEditable(userId, name);
        return addTracks(playlist.getId(), tracks);
    }

    public synchronized void removeItem(long userId, String name, int index)
    {
        PlaylistSummary playlist = requireEditable(userId, name);
        List<PlaylistTrack> items = listItems(playlist.getId());
        if(index < 1 || index > items.size())
            throw new PlaylistException("Index must be between 1 and " + items.size() + ".");
        deleteAndReindex(playlist.getId(), items.get(index - 1));
    }

    public synchronized Optional<PlaylistTrack> removeTrack(long userId, String name, PlaylistTrack track)
    {
        if(track == null)
            throw new PlaylistException("Track is required.");
        PlaylistSummary playlist = requireEditable(userId, name);
        String duplicateKey = track.getDuplicateKey();
        for(PlaylistTrack item : listItems(playlist.getId()))
        {
            if(item.getDuplicateKey().equals(duplicateKey))
                return Optional.of(deleteAndReindex(playlist.getId(), item));
        }
        return Optional.empty();
    }

    public synchronized Optional<PlaylistTrack> removeFirstMatchingTrack(long userId, String name, String query)
    {
        PlaylistSummary playlist = requireEditable(userId, name);
        String normalizedQuery = normalizeTrackSearch(query);
        if(normalizedQuery.isEmpty())
            throw new PlaylistException("Query is required.");

        List<PlaylistTrack> items = listItems(playlist.getId());
        Optional<PlaylistTrack> match = findTextMatch(items, normalizedQuery, true);
        if(!match.isPresent())
            match = findTextMatch(items, normalizedQuery, false);
        if(match.isPresent())
            return Optional.of(deleteAndReindex(playlist.getId(), match.get()));
        return Optional.empty();
    }

    public synchronized void moveItem(long userId, String name, int from, int to)
    {
        PlaylistSummary playlist = requireEditable(userId, name);
        List<PlaylistTrack> items = listItems(playlist.getId());
        if(from < 1 || from > items.size() || to < 1 || to > items.size())
            throw new PlaylistException("Positions must be between 1 and " + items.size() + ".");
        PlaylistTrack moved = items.remove(from - 1);
        items.add(to - 1, moved);
        updateOrder(playlist.getId(), items);
    }

    public synchronized int clearPlaylist(long userId, String name)
    {
        PlaylistSummary playlist = requireEditable(userId, name);
        int count = playlist.getItemCount();
        try(PreparedStatement ps = connection.prepareStatement("DELETE FROM playlist_items WHERE playlist_id=?"))
        {
            ps.setLong(1, playlist.getId());
            ps.executeUpdate();
            touchPlaylist(playlist.getId());
            return count;
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to clear playlist", ex);
        }
    }

    public synchronized void deletePlaylist(long userId, String name)
    {
        PlaylistSummary playlist = requireEditable(userId, name);
        if(playlist.isLiked())
            throw new PlaylistException("Liked Songs cannot be deleted.");
        try(PreparedStatement ps = connection.prepareStatement("DELETE FROM playlists WHERE id=? AND owner_id=?"))
        {
            ps.setLong(1, playlist.getId());
            ps.setLong(2, userId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to delete playlist", ex);
        }
    }

    public synchronized void renamePlaylist(long userId, String name, String newName)
    {
        PlaylistSummary playlist = requireEditable(userId, name);
        if(playlist.isLiked())
            throw new PlaylistException("Liked Songs cannot be renamed.");
        String clean = sanitizeName(newName);
        if(findFollowedName(userId, clean))
            throw new PlaylistException("You already have a followed playlist with that name.");
        try(PreparedStatement ps = connection.prepareStatement("UPDATE playlists SET name=?, normalized_name=?, updated_at=? WHERE id=? AND owner_id=?"))
        {
            ps.setString(1, clean);
            ps.setString(2, normalizeName(clean));
            ps.setLong(3, now());
            ps.setLong(4, playlist.getId());
            ps.setLong(5, userId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("A playlist with that name already exists.", ex);
        }
    }

    public synchronized Share createShare(long userId, String name, ShareMode mode)
    {
        PlaylistSummary playlist = requireEditable(userId, name);
        for(int attempt = 0; attempt < 5; attempt++)
        {
            String code = newShareCode();
            try(PreparedStatement ps = connection.prepareStatement("INSERT INTO playlist_shares(code, playlist_id, mode, revoked, created_at) VALUES(?,?,?,?,?)"))
            {
                ps.setString(1, code);
                ps.setLong(2, playlist.getId());
                ps.setString(3, mode.name().toLowerCase(Locale.ROOT));
                ps.setBoolean(4, false);
                ps.setLong(5, now());
                ps.executeUpdate();
                return new Share(code, mode, playlist.getName());
            }
            catch(SQLException ex)
            {
                if(attempt == 4)
                    throw new PlaylistException("Failed to create share code.", ex);
            }
        }
        throw new PlaylistException("Failed to create share code.");
    }

    public synchronized int revokeShare(long userId, String code)
    {
        String sql = "UPDATE playlist_shares SET revoked=1 WHERE code=? AND playlist_id IN "
                + "(SELECT id FROM playlists WHERE owner_id=?)";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setString(1, code.trim());
            ps.setLong(2, userId);
            return ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to revoke share code", ex);
        }
    }

    public synchronized PlaylistSummary addShared(long userId, String code, String requestedName)
    {
        ShareTarget target = resolveShare(code);
        String name = requestedName == null || requestedName.trim().isEmpty() ? target.playlist.getName() : requestedName;
        if(target.mode == ShareMode.FOLLOW)
            return followPlaylist(userId, target.playlist, name);

        PlaylistSummary copy = createPlaylist(userId, name, false, false);
        addTracks(copy.getId(), listItems(target.playlist.getId()));
        return findOwned(userId, copy.getName()).orElse(copy);
    }

    public synchronized PlaylistSummary copyVisible(long userId, String name, String newName)
    {
        PlaylistSummary source = resolveVisible(userId, name)
                .orElseThrow(() -> new PlaylistException("Playlist `" + name + "` does not exist."));
        PlaylistSummary copy = createPlaylist(userId, newName, false, false);
        addTracks(copy.getId(), listItems(source.getId()));
        return findOwned(userId, copy.getName()).orElse(copy);
    }

    public synchronized void unfollow(long userId, String name)
    {
        String sql = "DELETE FROM playlist_follows WHERE follower_id=? AND normalized_name=?";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, userId);
            ps.setString(2, normalizeName(name));
            if(ps.executeUpdate() == 0)
                throw new PlaylistException("You are not following `" + name + "`.");
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to unfollow playlist", ex);
        }
    }

    private PlaylistSummary createPlaylist(long ownerId, String name, boolean liked, boolean legacyShuffle)
    {
        ensureOpen();
        String clean = sanitizeName(name);
        if(!liked && normalizeName(clean).equals(normalizeName(LIKED_SONGS)))
            throw new PlaylistException("That playlist name is reserved for Liked Songs.");
        if(findFollowedName(ownerId, clean))
            throw new PlaylistException("You already have a followed playlist with that name.");
        long timestamp = now();
        String sql = "INSERT INTO playlists(owner_id, name, normalized_name, liked, legacy_shuffle, created_at, updated_at) "
                + "VALUES(?,?,?,?,?,?,?)";
        try(PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS))
        {
            ps.setLong(1, ownerId);
            ps.setString(2, clean);
            ps.setString(3, normalizeName(clean));
            ps.setBoolean(4, liked);
            ps.setBoolean(5, legacyShuffle);
            ps.setLong(6, timestamp);
            ps.setLong(7, timestamp);
            ps.executeUpdate();
            try(ResultSet keys = ps.getGeneratedKeys())
            {
                if(keys.next())
                    return new PlaylistSummary(keys.getLong(1), ownerId, clean, liked, legacyShuffle, false, true, 0);
            }
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("A playlist with that name already exists.", ex);
        }
        throw new PlaylistException("Failed to create playlist.");
    }

    private PlaylistSummary requireEditable(long userId, String name)
    {
        PlaylistSummary playlist = resolveVisible(userId, name)
                .orElseThrow(() -> new PlaylistException("Playlist `" + name + "` does not exist."));
        if(!playlist.isEditable())
            throw new PlaylistException("`" + playlist.getName() + "` is followed read-only. Copy it before editing.");
        return playlist;
    }

    private AddResult addTracks(long playlistId, List<PlaylistTrack> tracks)
    {
        ensureOpen();
        if(tracks == null || tracks.isEmpty())
            return new AddResult(0, 0);

        Set<String> existingKeys = existingDuplicateKeys(playlistId);
        List<PlaylistTrack> uniqueTracks = new ArrayList<>();
        int skippedDuplicates = 0;
        for(PlaylistTrack track : tracks)
        {
            String duplicateKey = track.getDuplicateKey();
            if(existingKeys.contains(duplicateKey))
            {
                skippedDuplicates++;
                continue;
            }
            existingKeys.add(duplicateKey);
            uniqueTracks.add(track);
        }

        if(uniqueTracks.isEmpty())
            return new AddResult(0, skippedDuplicates);

        int position = nextPosition(playlistId);
        String sql = "INSERT INTO playlist_items(playlist_id, position, duplicate_key, query, url, title, author, duration_ms, source, created_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(playlist_id, duplicate_key) DO NOTHING";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            int inserted = 0;
            for(PlaylistTrack track : uniqueTracks)
            {
                ps.setLong(1, playlistId);
                ps.setInt(2, position);
                ps.setString(3, track.getDuplicateKey());
                ps.setString(4, nonBlank(track.getQuery(), track.getLoadQuery()));
                ps.setString(5, nullIfBlank(track.getUrl()));
                ps.setString(6, nullIfBlank(track.getTitle()));
                ps.setString(7, nullIfBlank(track.getAuthor()));
                ps.setLong(8, Math.max(0, track.getDuration()));
                ps.setString(9, nullIfBlank(track.getSource()));
                ps.setLong(10, now());
                if(ps.executeUpdate() > 0)
                {
                    inserted++;
                    position++;
                }
                else
                {
                    skippedDuplicates++;
                }
            }
            if(inserted > 0)
                touchPlaylist(playlistId);
            return new AddResult(inserted, skippedDuplicates);
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to add tracks", ex);
        }
    }

    private Set<String> existingDuplicateKeys(long playlistId)
    {
        Set<String> keys = new HashSet<>();
        for(PlaylistTrack track : listItems(playlistId))
            keys.add(track.getDuplicateKey());
        return keys;
    }

    private Optional<PlaylistTrack> findTextMatch(List<PlaylistTrack> items, String normalizedQuery, boolean exact)
    {
        for(PlaylistTrack item : items)
            if(trackMatches(item, normalizedQuery, exact))
                return Optional.of(item);
        return Optional.empty();
    }

    private boolean trackMatches(PlaylistTrack item, String normalizedQuery, boolean exact)
    {
        String author = item.getAuthor() == null ? "" : item.getAuthor();
        String title = item.getDisplayTitle();
        String[] values = {
                item.getUrl(),
                item.getQuery(),
                item.getTitle(),
                title,
                author.isBlank() ? title : title + " " + author,
                author.isBlank() ? title : author + " " + title
        };

        for(String value : values)
        {
            String normalized = normalizeTrackSearch(value);
            if(normalized.isEmpty())
                continue;
            if(exact ? normalized.equals(normalizedQuery) : normalized.contains(normalizedQuery))
                return true;
        }
        return false;
    }

    private String normalizeTrackSearch(String value)
    {
        if(value == null)
            return "";
        String clean = value.trim();
        if(clean.startsWith("<") && clean.endsWith(">") && clean.length() > 1)
            clean = clean.substring(1, clean.length() - 1).trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        if(lower.startsWith("ytsearch:") || lower.startsWith("scsearch:"))
            clean = clean.substring(clean.indexOf(':') + 1).trim();
        return clean.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private void ensurePlaylistItemDuplicateKeys() throws SQLException
    {
        if(!columnExists("playlist_items", "duplicate_key"))
        {
            try(Statement st = connection.createStatement())
            {
                st.executeUpdate("ALTER TABLE playlist_items ADD COLUMN duplicate_key TEXT");
            }
        }
        backfillPlaylistItemDuplicateKeys();
        uniquifyExistingDuplicateKeys();
        try(Statement st = connection.createStatement())
        {
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_playlist_items_playlist_duplicate_key "
                    + "ON playlist_items(playlist_id, duplicate_key)");
        }
    }

    private boolean columnExists(String tableName, String columnName) throws SQLException
    {
        try(Statement st = connection.createStatement();
            ResultSet rs = st.executeQuery("PRAGMA table_info(" + tableName + ")"))
        {
            while(rs.next())
                if(columnName.equals(rs.getString("name")))
                    return true;
        }
        return false;
    }

    private void backfillPlaylistItemDuplicateKeys() throws SQLException
    {
        List<DuplicateKeyUpdate> updates = new ArrayList<>();
        String sql = "SELECT id, query, url, title, author, duration_ms, source FROM playlist_items "
                + "WHERE duplicate_key IS NULL OR TRIM(duplicate_key)=''";
        try(PreparedStatement ps = connection.prepareStatement(sql);
            ResultSet rs = ps.executeQuery())
        {
            while(rs.next())
            {
                PlaylistTrack track = mapTrack(rs);
                updates.add(new DuplicateKeyUpdate(track.getId(), track.getDuplicateKey()));
            }
        }
        updateDuplicateKeys(updates);
    }

    private void uniquifyExistingDuplicateKeys() throws SQLException
    {
        Set<String> seen = new HashSet<>();
        List<DuplicateKeyUpdate> updates = new ArrayList<>();
        String sql = "SELECT id, playlist_id, duplicate_key FROM playlist_items "
                + "WHERE duplicate_key IS NOT NULL AND TRIM(duplicate_key)<>'' "
                + "ORDER BY playlist_id, duplicate_key, position, id";
        try(PreparedStatement ps = connection.prepareStatement(sql);
            ResultSet rs = ps.executeQuery())
        {
            while(rs.next())
            {
                long id = rs.getLong("id");
                long playlistId = rs.getLong("playlist_id");
                String duplicateKey = rs.getString("duplicate_key");
                String compositeKey = playlistId + "\n" + duplicateKey;
                if(seen.add(compositeKey))
                    continue;

                int suffix = 0;
                String migratedKey;
                do
                {
                    suffix++;
                    migratedKey = duplicateKey + "#legacy:" + id + (suffix == 1 ? "" : ":" + suffix);
                    compositeKey = playlistId + "\n" + migratedKey;
                }
                while(!seen.add(compositeKey));
                updates.add(new DuplicateKeyUpdate(id, migratedKey));
            }
        }
        updateDuplicateKeys(updates);
    }

    private void updateDuplicateKeys(List<DuplicateKeyUpdate> updates) throws SQLException
    {
        if(updates.isEmpty())
            return;
        try(PreparedStatement ps = connection.prepareStatement("UPDATE playlist_items SET duplicate_key=? WHERE id=?"))
        {
            for(DuplicateKeyUpdate update : updates)
            {
                ps.setString(1, update.duplicateKey);
                ps.setLong(2, update.itemId);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private PlaylistSummary followPlaylist(long userId, PlaylistSummary source, String displayName)
    {
        if(source.getOwnerId() == userId)
            throw new PlaylistException("You already own that playlist.");
        String clean = sanitizeName(displayName);
        if(normalizeName(clean).equals(normalizeName(LIKED_SONGS)))
            throw new PlaylistException("That playlist name is reserved for Liked Songs.");
        if(findOwned(userId, clean).isPresent())
            throw new PlaylistException("You already have a playlist with that name.");
        String sql = "INSERT INTO playlist_follows(follower_id, source_playlist_id, display_name, normalized_name, created_at) "
                + "VALUES(?,?,?,?,?)";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, userId);
            ps.setLong(2, source.getId());
            ps.setString(3, clean);
            ps.setString(4, normalizeName(clean));
            ps.setLong(5, now());
            ps.executeUpdate();
            return resolveVisible(userId, clean).orElseThrow(() -> new PlaylistException("Failed to follow playlist."));
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("You already have a playlist or follow with that name.", ex);
        }
    }

    private ShareTarget resolveShare(String code)
    {
        ensureOpen();
        String sql = "SELECT s.mode, p.id, p.owner_id, p.name, p.liked, p.legacy_shuffle, "
                + "(SELECT COUNT(*) FROM playlist_items i WHERE i.playlist_id=p.id) AS item_count "
                + "FROM playlist_shares s JOIN playlists p ON p.id=s.playlist_id "
                + "WHERE s.code=? AND s.revoked=0 LIMIT 1";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setString(1, code.trim());
            try(ResultSet rs = ps.executeQuery())
            {
                if(rs.next())
                {
                    PlaylistSummary playlist = new PlaylistSummary(rs.getLong("id"), rs.getLong("owner_id"),
                            rs.getString("name"), rs.getBoolean("liked"), rs.getBoolean("legacy_shuffle"),
                            false, true, rs.getInt("item_count"));
                    return new ShareTarget(ShareMode.fromDb(rs.getString("mode")), playlist);
                }
            }
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to read share code", ex);
        }
        throw new PlaylistException("That share code is invalid or revoked.");
    }

    private void deleteItem(long itemId)
    {
        try(PreparedStatement ps = connection.prepareStatement("DELETE FROM playlist_items WHERE id=?"))
        {
            ps.setLong(1, itemId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to remove item", ex);
        }
    }

    private PlaylistTrack deleteAndReindex(long playlistId, PlaylistTrack item)
    {
        deleteItem(item.getId());
        reindex(playlistId);
        return item;
    }

    private void updateOrder(long playlistId, List<PlaylistTrack> items)
    {
        String sql = "UPDATE playlist_items SET position=? WHERE id=? AND playlist_id=?";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            for(int i = 0; i < items.size(); i++)
            {
                ps.setInt(1, i + 1);
                ps.setLong(2, items.get(i).getId());
                ps.setLong(3, playlistId);
                ps.addBatch();
            }
            ps.executeBatch();
            touchPlaylist(playlistId);
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to move item", ex);
        }
    }

    private void reindex(long playlistId)
    {
        updateOrder(playlistId, listItems(playlistId));
    }

    private int nextPosition(long playlistId)
    {
        try(PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(MAX(position), 0) + 1 FROM playlist_items WHERE playlist_id=?"))
        {
            ps.setLong(1, playlistId);
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next() ? rs.getInt(1) : 1;
            }
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to find playlist position", ex);
        }
    }

    private void touchPlaylist(long playlistId)
    {
        try(PreparedStatement ps = connection.prepareStatement("UPDATE playlists SET updated_at=? WHERE id=?"))
        {
            ps.setLong(1, now());
            ps.setLong(2, playlistId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to update playlist timestamp", ex);
        }
    }

    private PlaylistTrack mapTrack(ResultSet rs) throws SQLException
    {
        return new PlaylistTrack(rs.getLong("id"), rs.getString("query"), rs.getString("url"),
                rs.getString("title"), rs.getString("author"), rs.getLong("duration_ms"), rs.getString("source"));
    }

    private boolean hasMeta(String key)
    {
        try(PreparedStatement ps = connection.prepareStatement("SELECT value FROM playlist_meta WHERE key=?"))
        {
            ps.setString(1, key);
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next();
            }
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to read playlist metadata", ex);
        }
    }

    private void setMeta(String key, String value)
    {
        try(PreparedStatement ps = connection.prepareStatement("INSERT OR REPLACE INTO playlist_meta(key, value) VALUES(?,?)"))
        {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new PlaylistException("Failed to write playlist metadata", ex);
        }
    }

    private String newShareCode()
    {
        byte[] bytes = new byte[9];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sanitizeName(String name)
    {
        if(name == null)
            throw new PlaylistException("Playlist name is required.");
        String clean = name.trim().replaceAll("\\s+", " ");
        if(clean.isEmpty())
            throw new PlaylistException("Playlist name is required.");
        if(clean.length() > 64)
            throw new PlaylistException("Playlist names must be 64 characters or fewer.");
        return clean;
    }

    public static String normalizeName(String name)
    {
        return sanitizeName(name).toLowerCase(Locale.ROOT);
    }

    private String nonBlank(String value, String fallback)
    {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String nullIfBlank(String value)
    {
        return value == null || value.isBlank() ? null : value;
    }

    private long now()
    {
        return Instant.now().getEpochSecond();
    }

    private void ensureOpen()
    {
        if(connection == null)
            throw new PlaylistException("Playlist service is not initialized.");
    }

    public enum ShareMode
    {
        COPY,
        FOLLOW;

        private static ShareMode fromDb(String value)
        {
            if(value == null)
                throw new PlaylistException("Unknown share mode.");
            return ShareMode.valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public static class AddResult
    {
        private final int added;
        private final int skippedDuplicates;

        private AddResult(int added, int skippedDuplicates)
        {
            this.added = added;
            this.skippedDuplicates = skippedDuplicates;
        }

        public int getAdded()
        {
            return added;
        }

        public int getSkippedDuplicates()
        {
            return skippedDuplicates;
        }
    }

    public static class PlaylistSummary
    {
        private final long id;
        private final long ownerId;
        private final String name;
        private final boolean liked;
        private final boolean legacyShuffle;
        private final boolean followed;
        private final boolean editable;
        private final int itemCount;

        private PlaylistSummary(long id, long ownerId, String name, boolean liked, boolean legacyShuffle,
                                boolean followed, boolean editable, int itemCount)
        {
            this.id = id;
            this.ownerId = ownerId;
            this.name = name;
            this.liked = liked;
            this.legacyShuffle = legacyShuffle;
            this.followed = followed;
            this.editable = editable;
            this.itemCount = itemCount;
        }

        public long getId()
        {
            return id;
        }

        public long getOwnerId()
        {
            return ownerId;
        }

        public String getName()
        {
            return name;
        }

        public boolean isLiked()
        {
            return liked;
        }

        public boolean isLegacyShuffle()
        {
            return legacyShuffle;
        }

        public boolean isFollowed()
        {
            return followed;
        }

        public boolean isEditable()
        {
            return editable;
        }

        public int getItemCount()
        {
            return itemCount;
        }
    }

    public static class Share
    {
        private final String code;
        private final ShareMode mode;
        private final String playlistName;

        private Share(String code, ShareMode mode, String playlistName)
        {
            this.code = code;
            this.mode = mode;
            this.playlistName = playlistName;
        }

        public String getCode()
        {
            return code;
        }

        public ShareMode getMode()
        {
            return mode;
        }

        public String getPlaylistName()
        {
            return playlistName;
        }
    }

    private static class ShareTarget
    {
        private final ShareMode mode;
        private final PlaylistSummary playlist;

        private ShareTarget(ShareMode mode, PlaylistSummary playlist)
        {
            this.mode = mode;
            this.playlist = playlist;
        }
    }

    private static class DuplicateKeyUpdate
    {
        private final long itemId;
        private final String duplicateKey;

        private DuplicateKeyUpdate(long itemId, String duplicateKey)
        {
            this.itemId = itemId;
            this.duplicateKey = duplicateKey;
        }
    }

    public static class PlaylistException extends RuntimeException
    {
        public PlaylistException(String message)
        {
            super(message);
        }

        public PlaylistException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
