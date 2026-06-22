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
package com.jagrosh.jmusicbot.recovery;

import com.jagrosh.jmusicbot.database.Database;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists a per-guild snapshot of the playing track + queue so it can be
 * restored after a crash, restart or everyone leaving the voice channel. Each
 * track is stored by its URL/query plus the original requester and position, so
 * it can be re-loaded later (no fragile binary blobs).
 */
public class QueueSnapshotStore
{
    private static final Logger LOG = LoggerFactory.getLogger(QueueSnapshotStore.class);

    private final Path dbPath;
    private Connection connection;

    public QueueSnapshotStore(Path dbPath)
    {
        this.dbPath = dbPath;
    }

    public synchronized void init() throws SQLException
    {
        connection = Database.open(dbPath);
        try(Statement st = connection.createStatement())
        {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS queue_snapshots ("
                    + "guild_id INTEGER PRIMARY KEY,"
                    + "payload TEXT NOT NULL,"
                    + "track_count INTEGER NOT NULL,"
                    + "sample_title TEXT,"
                    + "saved_at INTEGER NOT NULL"
                    + ")");
        }
        LOG.info("Queue snapshot store ready in unified database at {}", dbPath.toAbsolutePath());
    }

    public synchronized void save(long guildId, List<Entry> entries)
    {
        ensureOpen();
        if(entries == null || entries.isEmpty())
        {
            delete(guildId);
            return;
        }
        JSONArray array = new JSONArray();
        for(Entry e : entries)
            array.put(e.toJson());
        String sampleTitle = entries.get(0).title;
        try(PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO queue_snapshots(guild_id, payload, track_count, sample_title, saved_at) VALUES(?,?,?,?,?) "
                        + "ON CONFLICT(guild_id) DO UPDATE SET payload=excluded.payload, track_count=excluded.track_count, "
                        + "sample_title=excluded.sample_title, saved_at=excluded.saved_at"))
        {
            ps.setLong(1, guildId);
            ps.setString(2, array.toString());
            ps.setInt(3, entries.size());
            ps.setString(4, sampleTitle);
            ps.setLong(5, Instant.now().getEpochSecond());
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new SnapshotException("Failed to save queue snapshot", ex);
        }
    }

    /** Lightweight peek of count + sample title without decoding the whole payload. */
    public synchronized Optional<Info> peek(long guildId)
    {
        ensureOpen();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT track_count, sample_title, saved_at FROM queue_snapshots WHERE guild_id=?"))
        {
            ps.setLong(1, guildId);
            try(ResultSet rs = ps.executeQuery())
            {
                if(rs.next() && rs.getInt("track_count") > 0)
                    return Optional.of(new Info(rs.getInt("track_count"), rs.getString("sample_title"), rs.getLong("saved_at")));
            }
        }
        catch(SQLException ex)
        {
            throw new SnapshotException("Failed to peek queue snapshot", ex);
        }
        return Optional.empty();
    }

    public synchronized List<Entry> load(long guildId)
    {
        ensureOpen();
        String payload = null;
        try(PreparedStatement ps = connection.prepareStatement("SELECT payload FROM queue_snapshots WHERE guild_id=?"))
        {
            ps.setLong(1, guildId);
            try(ResultSet rs = ps.executeQuery())
            {
                if(rs.next())
                    payload = rs.getString("payload");
            }
        }
        catch(SQLException ex)
        {
            throw new SnapshotException("Failed to load queue snapshot", ex);
        }
        if(payload == null)
            return new ArrayList<>();
        try
        {
            return parse(payload);
        }
        catch(RuntimeException ex)
        {
            // A corrupt/unparseable payload must not wedge /restore forever: drop it and self-heal.
            LOG.warn("Discarding unreadable queue snapshot for guild {}", guildId, ex);
            try { delete(guildId); } catch(RuntimeException ignored) {}
            return new ArrayList<>();
        }
    }

    public synchronized void delete(long guildId)
    {
        ensureOpen();
        try(PreparedStatement ps = connection.prepareStatement("DELETE FROM queue_snapshots WHERE guild_id=?"))
        {
            ps.setLong(1, guildId);
            ps.executeUpdate();
        }
        catch(SQLException ex)
        {
            throw new SnapshotException("Failed to delete queue snapshot", ex);
        }
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
            LOG.warn("Failed to close queue snapshot store", ex);
        }
        finally
        {
            connection = null;
        }
    }

    private static List<Entry> parse(String payload)
    {
        List<Entry> entries = new ArrayList<>();
        if(payload == null || payload.isBlank())
            return entries;
        JSONArray array = new JSONArray(payload);
        for(int i = 0; i < array.length(); i++)
        {
            try
            {
                entries.add(Entry.fromJson(array.getJSONObject(i)));
            }
            catch(RuntimeException ex)
            {
                // Skip one malformed entry rather than discarding the whole queue.
                LOG.debug("Skipping malformed queue snapshot entry {}", i, ex);
            }
        }
        return entries;
    }

    private void ensureOpen()
    {
        if(connection == null)
            throw new SnapshotException("Queue snapshot store is not initialized");
    }

    /** A single saved track. */
    public static final class Entry
    {
        public String url;
        public String query;
        public String title;
        public String author;
        public long requesterId;
        public String requesterName;
        public String requesterAvatar;
        public long positionMs;

        public String loadIdentifier()
        {
            if(url != null && !url.isBlank())
                return url;
            return query;
        }

        private JSONObject toJson()
        {
            JSONObject o = new JSONObject();
            o.put("url", url == null ? "" : url);
            o.put("query", query == null ? "" : query);
            o.put("title", title == null ? "" : title);
            o.put("author", author == null ? "" : author);
            o.put("requesterId", requesterId);
            o.put("requesterName", requesterName == null ? "" : requesterName);
            o.put("requesterAvatar", requesterAvatar == null ? "" : requesterAvatar);
            o.put("positionMs", positionMs);
            return o;
        }

        private static Entry fromJson(JSONObject o)
        {
            Entry e = new Entry();
            e.url = emptyToNull(o.optString("url", null));
            e.query = emptyToNull(o.optString("query", null));
            e.title = emptyToNull(o.optString("title", null));
            e.author = emptyToNull(o.optString("author", null));
            e.requesterId = o.optLong("requesterId", 0);
            e.requesterName = emptyToNull(o.optString("requesterName", null));
            e.requesterAvatar = emptyToNull(o.optString("requesterAvatar", null));
            e.positionMs = o.optLong("positionMs", 0);
            return e;
        }

        private static String emptyToNull(String s)
        {
            return s == null || s.isBlank() ? null : s;
        }
    }

    public static final class Info
    {
        private final int count;
        private final String sampleTitle;
        private final long savedAt;

        Info(int count, String sampleTitle, long savedAt)
        {
            this.count = count;
            this.sampleTitle = sampleTitle;
            this.savedAt = savedAt;
        }

        public int getCount() { return count; }
        public String getSampleTitle() { return sampleTitle; }
        public long getSavedAt() { return savedAt; }
    }

    public static class SnapshotException extends RuntimeException
    {
        public SnapshotException(String message)
        {
            super(message);
        }

        public SnapshotException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
