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
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.database.Database;
import com.jagrosh.jmusicbot.utils.TrackIdentity;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlaybackHistoryStore
{
    private static final Logger LOG = LoggerFactory.getLogger(PlaybackHistoryStore.class);

    private final Path dbPath;
    private Connection connection;

    public PlaybackHistoryStore(Path dbPath)
    {
        this.dbPath = dbPath;
    }

    public synchronized void init() throws SQLException
    {
        connection = Database.open(dbPath);
        try(Statement st = connection.createStatement())
        {
            st.executeUpdate("PRAGMA busy_timeout=5000");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS playback_history ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "guild_id INTEGER NOT NULL,"
                    + "track_key TEXT NOT NULL,"
                    + "track_identifier TEXT,"
                    + "track_title TEXT NOT NULL,"
                    + "track_author TEXT,"
                    + "track_uri TEXT,"
                    + "track_source TEXT,"
                    + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                    + "stream INTEGER NOT NULL DEFAULT 0,"
                    + "play_count INTEGER NOT NULL DEFAULT 1,"
                    + "first_played_at INTEGER NOT NULL,"
                    + "last_played_at INTEGER NOT NULL"
                    + ")");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_playback_history_guild_id_id "
                    + "ON playback_history(guild_id, id DESC)");
        }
        LOG.info("Playback history database initialized at {}", dbPath.toAbsolutePath());
    }

    public synchronized void record(long guildId, AudioTrack track)
    {
        ensureOpen();
        TrackSnapshot snapshot = TrackSnapshot.from(track);
        if(snapshot == null)
            return;

        long now = System.currentTimeMillis();
        Long latestId = null;
        String latestKey = null;
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT id, track_key FROM playback_history WHERE guild_id=? ORDER BY id DESC LIMIT 1"))
        {
            ps.setLong(1, guildId);
            try(ResultSet rs = ps.executeQuery())
            {
                if(rs.next())
                {
                    latestId = rs.getLong("id");
                    latestKey = rs.getString("track_key");
                }
            }
        }
        catch(SQLException ex)
        {
            throw new PlaybackHistoryException("Failed to read playback history", ex);
        }

        if(snapshot.trackKey.equals(latestKey))
            updateLatest(latestId, snapshot, now);
        else
            insert(guildId, snapshot, now);
    }

    public synchronized int countEntries(long guildId)
    {
        ensureOpen();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM playback_history WHERE guild_id=?"))
        {
            ps.setLong(1, guildId);
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
        catch(SQLException ex)
        {
            throw new PlaybackHistoryException("Failed to count playback history", ex);
        }
    }

    public synchronized List<Entry> list(long guildId, int offset, int limit)
    {
        ensureOpen();
        List<Entry> entries = new ArrayList<>();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT track_title, track_author, track_uri, duration_ms, stream, play_count "
                        + "FROM playback_history WHERE guild_id=? ORDER BY id DESC LIMIT ? OFFSET ?"))
        {
            ps.setLong(1, guildId);
            ps.setInt(2, Math.max(1, limit));
            ps.setInt(3, Math.max(0, offset));
            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                    entries.add(new Entry(rs.getString("track_title"),
                            rs.getString("track_author"),
                            rs.getString("track_uri"),
                            rs.getLong("duration_ms"),
                            rs.getInt("stream") == 1,
                            rs.getInt("play_count")));
            }
        }
        catch(SQLException ex)
        {
            throw new PlaybackHistoryException("Failed to list playback history", ex);
        }
        return entries;
    }

    public synchronized void close()
    {
        if(connection == null)
            return;
        try
        {
            connection.close();
        }
        catch(SQLException ex)
        {
            LOG.warn("Failed to close playback history database", ex);
        }
        finally
        {
            connection = null;
        }
    }

    private void updateLatest(Long latestId, TrackSnapshot snapshot, long now)
    {
        if(latestId == null)
            return;
        try(PreparedStatement ps = connection.prepareStatement(
                "UPDATE playback_history SET track_identifier=?, track_title=?, track_author=?, track_uri=?, "
                        + "track_source=?, duration_ms=?, stream=?, play_count=play_count+1, last_played_at=? "
                        + "WHERE id=?"))
        {
            bindSnapshot(ps, snapshot);
            ps.setLong(8, now);
            ps.setLong(9, latestId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new PlaybackHistoryException("Failed to update playback history", ex);
        }
    }

    private void insert(long guildId, TrackSnapshot snapshot, long now)
    {
        try(PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO playback_history (guild_id, track_key, track_identifier, track_title, track_author, "
                        + "track_uri, track_source, duration_ms, stream, play_count, first_played_at, last_played_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)"))
        {
            ps.setLong(1, guildId);
            ps.setString(2, snapshot.trackKey);
            bindSnapshot(ps, snapshot, 3);
            ps.setLong(10, now);
            ps.setLong(11, now);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new PlaybackHistoryException("Failed to insert playback history", ex);
        }
    }

    private static void bindSnapshot(PreparedStatement ps, TrackSnapshot snapshot) throws SQLException
    {
        bindSnapshot(ps, snapshot, 1);
    }

    private static void bindSnapshot(PreparedStatement ps, TrackSnapshot snapshot, int start) throws SQLException
    {
        ps.setString(start, snapshot.identifier);
        ps.setString(start + 1, snapshot.title);
        ps.setString(start + 2, snapshot.author);
        ps.setString(start + 3, snapshot.uri);
        ps.setString(start + 4, snapshot.source);
        ps.setLong(start + 5, snapshot.duration);
        ps.setInt(start + 6, snapshot.stream ? 1 : 0);
    }

    private void ensureOpen()
    {
        if(connection == null)
            throw new PlaybackHistoryException("Playback history database is not initialized");
    }

    private static String trackKey(AudioTrack track, AudioTrackInfo info, String source)
    {
        String songKey = TrackIdentity.songKey(info.title, info.author);
        if(songKey != null)
            return songKey;

        String identifier = emptyToNull(track.getIdentifier());
        if(identifier != null)
            return "id:" + source + ":" + identifier;

        String uri = emptyToNull(info.uri);
        if(uri != null)
            return "uri:" + uri;

        String title = emptyToNull(info.title);
        return title == null ? null : "title:" + title.toLowerCase(Locale.ROOT);
    }

    private static String sourceName(AudioTrack track)
    {
        return track.getSourceManager() == null ? "unknown" : track.getSourceManager().getSourceName();
    }

    private static String emptyToNull(String value)
    {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static final class TrackSnapshot
    {
        private final String trackKey;
        private final String identifier;
        private final String title;
        private final String author;
        private final String uri;
        private final String source;
        private final long duration;
        private final boolean stream;

        private TrackSnapshot(String trackKey, String identifier, String title, String author, String uri,
                              String source, long duration, boolean stream)
        {
            this.trackKey = trackKey;
            this.identifier = identifier;
            this.title = title;
            this.author = author;
            this.uri = uri;
            this.source = source;
            this.duration = duration;
            this.stream = stream;
        }

        private static TrackSnapshot from(AudioTrack track)
        {
            if(track == null || track.getInfo() == null)
                return null;

            AudioTrackInfo info = track.getInfo();
            String source = sourceName(track);
            String key = trackKey(track, info, source);
            if(key == null)
                return null;

            String title = emptyToNull(info.title);
            return new TrackSnapshot(key,
                    emptyToNull(track.getIdentifier()),
                    title == null ? "Unknown track" : title,
                    emptyToNull(info.author),
                    emptyToNull(info.uri),
                    source,
                    Math.max(0L, track.getDuration()),
                    info.isStream || track.getDuration() == Long.MAX_VALUE);
        }
    }

    public static final class Entry
    {
        private final String title;
        private final String author;
        private final String uri;
        private final long duration;
        private final boolean stream;
        private final int count;

        Entry(String title, String author, String uri, long duration, boolean stream, int count)
        {
            this.title = title;
            this.author = author;
            this.uri = uri;
            this.duration = duration;
            this.stream = stream;
            this.count = count;
        }

        public String getTitle()
        {
            return title;
        }

        public String getAuthor()
        {
            return author;
        }

        public String getUri()
        {
            return uri;
        }

        public long getDuration()
        {
            return duration;
        }

        public boolean isStream()
        {
            return stream;
        }

        public int getCount()
        {
            return count;
        }
    }

    public static class PlaybackHistoryException extends RuntimeException
    {
        public PlaybackHistoryException(String message)
        {
            super(message);
        }

        public PlaybackHistoryException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
