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

import com.jagrosh.jmusicbot.database.Database;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GuessMusicHighlightStore
{
    private static final Logger LOG = LoggerFactory.getLogger(GuessMusicHighlightStore.class);

    private final Path dbPath;
    private Connection connection;

    public GuessMusicHighlightStore(Path dbPath)
    {
        this.dbPath = dbPath;
    }

    public synchronized void init() throws SQLException
    {
        connection = Database.open(dbPath);
        try(Statement st = connection.createStatement())
        {
            st.executeUpdate("PRAGMA busy_timeout=5000");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS guess_music_highlights ("
                    + "track_key TEXT PRIMARY KEY,"
                    + "position_ms INTEGER NOT NULL,"
                    + "confidence REAL NOT NULL DEFAULT 0,"
                    + "manual INTEGER NOT NULL DEFAULT 0,"
                    + "updated_at INTEGER NOT NULL"
                    + ")");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_guess_music_highlights_updated "
                    + "ON guess_music_highlights(updated_at DESC)");
        }
        LOG.info("Guess music highlight database initialized at {}", dbPath.toAbsolutePath());
    }

    public synchronized Optional<Highlight> find(Collection<String> keys)
    {
        ensureOpen();
        if(keys == null || keys.isEmpty())
            return Optional.empty();

        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT position_ms, confidence, manual FROM guess_music_highlights WHERE track_key=? LIMIT 1"))
        {
            for(String key : keys)
            {
                if(key == null || key.isBlank())
                    continue;
                ps.setString(1, key.trim());
                try(ResultSet rs = ps.executeQuery())
                {
                    if(rs.next())
                    {
                        return Optional.of(new Highlight(
                                Math.max(0L, rs.getLong("position_ms")),
                                rs.getDouble("confidence"),
                                rs.getInt("manual") == 1));
                    }
                }
            }
        }
        catch(SQLException ex)
        {
            throw new HighlightStoreException("Failed to read guess music highlight cache", ex);
        }
        return Optional.empty();
    }

    public synchronized void save(Collection<String> keys, long positionMillis, double confidence, boolean manual)
    {
        ensureOpen();
        if(keys == null || keys.isEmpty())
            return;

        long now = System.currentTimeMillis();
        try(PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO guess_music_highlights (track_key, position_ms, confidence, manual, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT(track_key) DO UPDATE SET "
                        + "position_ms=excluded.position_ms, confidence=excluded.confidence, "
                        + "manual=excluded.manual, updated_at=excluded.updated_at"))
        {
            for(String key : keys)
            {
                if(key == null || key.isBlank())
                    continue;
                ps.setString(1, key.trim());
                ps.setLong(2, Math.max(0L, positionMillis));
                ps.setDouble(3, Math.max(0d, confidence));
                ps.setInt(4, manual ? 1 : 0);
                ps.setLong(5, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
        catch(SQLException ex)
        {
            throw new HighlightStoreException("Failed to write guess music highlight cache", ex);
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
            LOG.warn("Failed to close guess music highlight database", ex);
        }
        finally
        {
            connection = null;
        }
    }

    private void ensureOpen()
    {
        if(connection == null)
            throw new HighlightStoreException("Guess music highlight database is not initialized");
    }

    public static final class Highlight
    {
        private final long positionMillis;
        private final double confidence;
        private final boolean manual;

        public Highlight(long positionMillis, double confidence, boolean manual)
        {
            this.positionMillis = Math.max(0L, positionMillis);
            this.confidence = Math.max(0d, confidence);
            this.manual = manual;
        }

        public long getPositionMillis()
        {
            return positionMillis;
        }

        public double getConfidence()
        {
            return confidence;
        }

        public boolean isManual()
        {
            return manual;
        }
    }

    public static class HighlightStoreException extends RuntimeException
    {
        public HighlightStoreException(String message)
        {
            super(message);
        }

        public HighlightStoreException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
