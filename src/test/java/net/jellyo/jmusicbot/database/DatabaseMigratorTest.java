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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DatabaseMigratorTest
{
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void mergesTablesFromMultipleLegacyDatabases() throws Exception
    {
        Path legacyA = folder.getRoot().toPath().resolve("playlists.db");
        Path legacyB = folder.getRoot().toPath().resolve("playback-history.db");
        Path unified = folder.getRoot().toPath().resolve("jmusicbot.db");

        createDatabase(legacyA, st ->
        {
            st.executeUpdate("CREATE TABLE widgets (id INTEGER PRIMARY KEY, name TEXT)");
            st.executeUpdate("INSERT INTO widgets(id, name) VALUES (1, 'alpha'), (2, 'beta')");
        });
        createDatabase(legacyB, st ->
        {
            st.executeUpdate("CREATE TABLE gizmos (id INTEGER PRIMARY KEY, value INTEGER)");
            st.executeUpdate("INSERT INTO gizmos(id, value) VALUES (10, 100), (20, 200), (30, 300)");
        });

        DatabaseMigrator.merge(unified, Arrays.asList(
                new DatabaseMigrator.LegacySource("playlists", legacyA),
                new DatabaseMigrator.LegacySource("playback-history", legacyB)));

        assertEquals(2, count(unified, "widgets"));
        assertEquals(3, count(unified, "gizmos"));
        assertEquals("alpha", scalarString(unified, "SELECT name FROM widgets WHERE id=1"));

        // Legacy files should be renamed out of the way once merged.
        assertFalse("legacy A should be renamed", Files.exists(legacyA));
        assertFalse("legacy B should be renamed", Files.exists(legacyB));
        assertTrue(Files.exists(folder.getRoot().toPath().resolve("playlists.db.migrated")));
    }

    @Test
    public void isIdempotentAcrossRepeatedRuns() throws Exception
    {
        Path legacy = folder.getRoot().toPath().resolve("playlists.db");
        Path unified = folder.getRoot().toPath().resolve("jmusicbot.db");
        createDatabase(legacy, st ->
        {
            st.executeUpdate("CREATE TABLE widgets (id INTEGER PRIMARY KEY, name TEXT)");
            st.executeUpdate("INSERT INTO widgets(id, name) VALUES (1, 'alpha'), (2, 'beta')");
        });

        DatabaseMigrator.merge(unified, Collections.singletonList(
                new DatabaseMigrator.LegacySource("playlists", legacy)));
        assertEquals(2, count(unified, "widgets"));

        // Second run with the same logical id is a no-op even if the file reappears.
        DatabaseMigrator.merge(unified, Collections.singletonList(
                new DatabaseMigrator.LegacySource("playlists", legacy)));
        assertEquals(2, count(unified, "widgets"));
    }

    @Test
    public void copiesOnlySharedColumnsWhenSchemasDiffer() throws Exception
    {
        Path legacy = folder.getRoot().toPath().resolve("playlists.db");
        Path unified = folder.getRoot().toPath().resolve("jmusicbot.db");

        // Unified already has a narrower table (as a store would have created it).
        createDatabase(unified, st ->
                st.executeUpdate("CREATE TABLE parts (id INTEGER PRIMARY KEY, name TEXT)"));
        // Legacy has an extra column not present in the unified schema.
        createDatabase(legacy, st ->
        {
            st.executeUpdate("CREATE TABLE parts (id INTEGER PRIMARY KEY, name TEXT, extra TEXT)");
            st.executeUpdate("INSERT INTO parts(id, name, extra) VALUES (1, 'keep', 'dropped')");
        });

        DatabaseMigrator.merge(unified, Collections.singletonList(
                new DatabaseMigrator.LegacySource("playlists", legacy)));

        assertEquals(1, count(unified, "parts"));
        assertEquals("keep", scalarString(unified, "SELECT name FROM parts WHERE id=1"));
        // The unified schema is untouched (no 'extra' column added).
        assertEquals(2, columnCount(unified, "parts"));
    }

    @Test
    public void toleratesMissingLegacyFile() throws Exception
    {
        Path missing = folder.getRoot().toPath().resolve("does-not-exist.db");
        Path unified = folder.getRoot().toPath().resolve("jmusicbot.db");
        // Should not throw and should leave a usable unified database.
        DatabaseMigrator.merge(unified, Collections.singletonList(
                new DatabaseMigrator.LegacySource("missing", missing)));
        assertTrue(Files.exists(unified));
    }

    private interface Setup
    {
        void run(Statement st) throws SQLException;
    }

    private static void createDatabase(Path path, Setup setup) throws SQLException
    {
        try(Connection c = Database.open(path); Statement st = c.createStatement())
        {
            setup.run(st);
        }
    }

    private static int count(Path db, String table) throws SQLException
    {
        try(Connection c = Database.open(db); Statement st = c.createStatement();
            java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static int columnCount(Path db, String table) throws SQLException
    {
        try(Connection c = Database.open(db); Statement st = c.createStatement();
            java.sql.ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            int n = 0;
            while(rs.next())
                n++;
            return n;
        }
    }

    private static String scalarString(Path db, String sql) throws SQLException
    {
        try(Connection c = Database.open(db); Statement st = c.createStatement();
            java.sql.ResultSet rs = st.executeQuery(sql))
        {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
