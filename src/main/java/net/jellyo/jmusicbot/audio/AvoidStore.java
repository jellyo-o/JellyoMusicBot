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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-guild, persistent list of songs that autoplay must never pick. Identity is
 * matched with {@link TrackIdentity} keys (identifier / uri / normalized
 * title+author), so blocking one variant catches re-uploads and live/cover
 * variants of the same song. An in-memory key cache keeps the hot
 * {@link #isAvoided} path (run for every autoplay candidate) lock-free.
 */
public class AvoidStore
{
    private static final Logger LOG = LoggerFactory.getLogger(AvoidStore.class);

    private final Path dbPath;
    private Connection connection;
    private final ConcurrentHashMap<Long, Set<String>> cache = new ConcurrentHashMap<>();

    public AvoidStore(Path dbPath)
    {
        this.dbPath = dbPath;
    }

    public synchronized void init() throws SQLException
    {
        connection = Database.open(dbPath);
        try(Statement st = connection.createStatement())
        {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS avoided_tracks ("
                    + "guild_id INTEGER NOT NULL,"
                    + "avoid_key TEXT NOT NULL,"
                    + "label TEXT NOT NULL,"
                    + "created_at INTEGER NOT NULL,"
                    + "PRIMARY KEY(guild_id, avoid_key)"
                    + ")");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_avoided_tracks_guild ON avoided_tracks(guild_id)");
        }
        loadCache();
        LOG.info("Avoid list ready in unified database at {}", dbPath.toAbsolutePath());
    }

    private void loadCache() throws SQLException
    {
        cache.clear();
        try(Statement st = connection.createStatement();
            ResultSet rs = st.executeQuery("SELECT guild_id, avoid_key FROM avoided_tracks"))
        {
            while(rs.next())
                cache.computeIfAbsent(rs.getLong("guild_id"), id -> ConcurrentHashMap.newKeySet())
                        .add(rs.getString("avoid_key"));
        }
    }

    /** @return true if any identity key of the track is on the guild's avoid list */
    public boolean isAvoided(long guildId, AudioTrack track)
    {
        return matches(guildId, TrackIdentity.keys(track));
    }

    public boolean isAvoided(long guildId, String identifier, String uri, String title, String author)
    {
        return matches(guildId, TrackIdentity.keys(identifier, uri, title, author));
    }

    private boolean matches(long guildId, Set<String> keys)
    {
        if(keys.isEmpty())
            return false;
        Set<String> guildKeys = cache.get(guildId);
        if(guildKeys == null || guildKeys.isEmpty())
            return false;
        for(String key : keys)
            if(guildKeys.contains(key))
                return true;
        return false;
    }

    /** Avoids the given track. Returns the result (label and whether it was newly added). */
    public synchronized Result avoidTrack(long guildId, AudioTrack track)
    {
        if(track == null)
            return Result.notIdentifiable();
        return store(guildId, TrackIdentity.keys(track), label(track));
    }

    /** Avoids a song by free-text query (title), used when it isn't currently playing. */
    public synchronized Result avoidQuery(long guildId, String query)
    {
        if(query == null || query.isBlank())
            return Result.notIdentifiable();
        return store(guildId, TrackIdentity.keys(null, null, query, null), query.trim());
    }

    private Result store(long guildId, Set<String> keys, String label)
    {
        ensureOpen();
        if(keys.isEmpty())
            return Result.notIdentifiable();

        Set<String> guildKeys = cache.computeIfAbsent(guildId, id -> ConcurrentHashMap.newKeySet());
        boolean alreadyAvoided = false;
        for(String key : keys)
            if(guildKeys.contains(key))
                alreadyAvoided = true;

        long now = Instant.now().getEpochSecond();
        try(PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO avoided_tracks(guild_id, avoid_key, label, created_at) VALUES(?,?,?,?)"))
        {
            for(String key : keys)
            {
                ps.setLong(1, guildId);
                ps.setString(2, key);
                ps.setString(3, label);
                ps.setLong(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        catch(SQLException ex)
        {
            throw new AvoidException("Failed to save avoided song", ex);
        }
        guildKeys.addAll(keys);
        return new Result(true, alreadyAvoided, label);
    }

    /** Removes avoided songs whose label contains the query, or whose keys match the query. Returns count of songs removed. */
    public synchronized int unavoid(long guildId, String query)
    {
        ensureOpen();
        if(query == null || query.isBlank())
            return 0;
        String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
        Set<String> queryKeys = TrackIdentity.keys(null, null, query, null);

        List<String> labels = new ArrayList<>();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT DISTINCT label FROM avoided_tracks WHERE guild_id=?"))
        {
            ps.setLong(1, guildId);
            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                {
                    String label = rs.getString("label");
                    if(label != null && label.toLowerCase(java.util.Locale.ROOT).contains(needle))
                        labels.add(label);
                }
            }
        }
        catch(SQLException ex)
        {
            throw new AvoidException("Failed to read avoid list", ex);
        }

        // Also match by song key (e.g. exact title), collecting their labels.
        if(!queryKeys.isEmpty())
            labels.addAll(labelsForKeys(guildId, queryKeys));

        Set<String> distinctLabels = new LinkedHashSet<>(labels);
        if(distinctLabels.isEmpty())
            return 0;

        try(PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM avoided_tracks WHERE guild_id=? AND label=?"))
        {
            for(String label : distinctLabels)
            {
                ps.setLong(1, guildId);
                ps.setString(2, label);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        catch(SQLException ex)
        {
            throw new AvoidException("Failed to remove avoided songs", ex);
        }
        reloadGuild(guildId);
        return distinctLabels.size();
    }

    private List<String> labelsForKeys(long guildId, Set<String> keys)
    {
        List<String> labels = new ArrayList<>();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT label FROM avoided_tracks WHERE guild_id=? AND avoid_key=?"))
        {
            for(String key : keys)
            {
                ps.setLong(1, guildId);
                ps.setString(2, key);
                try(ResultSet rs = ps.executeQuery())
                {
                    while(rs.next())
                        labels.add(rs.getString("label"));
                }
            }
        }
        catch(SQLException ex)
        {
            throw new AvoidException("Failed to read avoid list", ex);
        }
        return labels;
    }

    /** @return the distinct avoided song labels for the guild, newest first */
    public synchronized List<String> list(long guildId)
    {
        ensureOpen();
        List<String> labels = new ArrayList<>();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT label, MIN(created_at) AS c FROM avoided_tracks WHERE guild_id=? GROUP BY label ORDER BY c DESC"))
        {
            ps.setLong(1, guildId);
            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                    labels.add(rs.getString("label"));
            }
        }
        catch(SQLException ex)
        {
            throw new AvoidException("Failed to read avoid list", ex);
        }
        return labels;
    }

    private void reloadGuild(long guildId)
    {
        Set<String> guildKeys = ConcurrentHashMap.newKeySet();
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT avoid_key FROM avoided_tracks WHERE guild_id=?"))
        {
            ps.setLong(1, guildId);
            try(ResultSet rs = ps.executeQuery())
            {
                while(rs.next())
                    guildKeys.add(rs.getString("avoid_key"));
            }
        }
        catch(SQLException ex)
        {
            throw new AvoidException("Failed to refresh avoid cache", ex);
        }
        if(guildKeys.isEmpty())
            cache.remove(guildId);
        else
            cache.put(guildId, guildKeys);
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
            LOG.warn("Failed to close avoid store", ex);
        }
        finally
        {
            connection = null;
        }
    }

    private void ensureOpen()
    {
        if(connection == null)
            throw new AvoidException("Avoid store is not initialized");
    }

    private static String label(AudioTrack track)
    {
        AudioTrackInfo info = track.getInfo();
        String title = info == null || info.title == null || info.title.isBlank() ? "Unknown track" : info.title;
        String author = info == null ? null : info.author;
        return author == null || author.isBlank() ? title : title + " — " + author;
    }

    public static final class Result
    {
        private final boolean identifiable;
        private final boolean alreadyAvoided;
        private final String label;

        private Result(boolean identifiable, boolean alreadyAvoided, String label)
        {
            this.identifiable = identifiable;
            this.alreadyAvoided = alreadyAvoided;
            this.label = label;
        }

        static Result notIdentifiable()
        {
            return new Result(false, false, null);
        }

        public boolean isIdentifiable() { return identifiable; }
        public boolean isAlreadyAvoided() { return alreadyAvoided; }
        public String getLabel() { return label; }
    }

    public static class AvoidException extends RuntimeException
    {
        public AvoidException(String message)
        {
            super(message);
        }

        public AvoidException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
