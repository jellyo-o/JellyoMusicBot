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
package com.jagrosh.jmusicbot.database;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Central access point for the unified SQLite database ({@value #FILE_NAME}).
 *
 * <p>Historically the bot kept several separate database files (playlists,
 * playback history, guess-music highlights, dashboard telemetry). They are now
 * consolidated into a single file so backups, inspection and migrations only
 * have to deal with one database. Every store opens its own connection(s) to
 * this file; SQLite's WAL journal makes concurrent readers and a single writer
 * across connections safe, which is exactly how the dashboard already behaved.
 *
 * @see DatabaseMigrator for the one-time import of the old split files.
 */
public final class Database
{
    /** File name of the unified database, resolved relative to the working directory. */
    public static final String FILE_NAME = "jmusicbot.db";

    private Database() {}

    /**
     * @return the default unified database path (working-directory relative)
     */
    public static Path defaultPath()
    {
        return Paths.get(FILE_NAME);
    }

    /**
     * Opens a JDBC connection to a SQLite database file with the bot's standard
     * pragmas applied: a generous busy timeout, write-ahead logging for
     * cross-connection concurrency, NORMAL synchronous mode (safe under WAL) and
     * foreign-key enforcement.
     *
     * @param path the database file
     * @return a configured connection (caller owns the lifecycle)
     * @throws SQLException if the connection cannot be opened
     */
    public static Connection open(Path path) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try(Statement st = connection.createStatement())
        {
            // Generous busy timeout so the (rare) cross-connection WAL write contention is
            // absorbed by SQLite's internal block-and-retry instead of surfacing as SQLITE_BUSY.
            st.execute("PRAGMA busy_timeout=30000");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        catch(SQLException ex)
        {
            try { connection.close(); } catch(SQLException ignored) {}
            throw ex;
        }
        return connection;
    }
}
