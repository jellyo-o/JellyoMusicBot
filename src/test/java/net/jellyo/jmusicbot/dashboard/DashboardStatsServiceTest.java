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
package com.jagrosh.jmusicbot.dashboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Comparator;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DashboardStatsServiceTest
{
    @Test
    public void emptySnapshotReturnsZeroedSections() throws Exception
    {
        Path dir = Files.createTempDirectory("dashboard-stats-test");
        DashboardStatsService service = new DashboardStatsService(dir.resolve("dashboard.db"));
        try
        {
            service.init();
            JSONObject snapshot = service.getSnapshot(5, 5, 5, 5, 5);

            assertEquals(0, snapshot.getJSONObject("summary").getLong("songsPlayed"));
            assertEquals(0, snapshot.getJSONObject("summary").getLong("minutesPlayed"));
            assertEquals(0, snapshot.getJSONArray("requesters").length());
            assertEquals(0, snapshot.getJSONArray("guilds").length());
            assertEquals(0, snapshot.getJSONArray("tracks").length());
            assertEquals(0, snapshot.getJSONArray("sources").length());
            assertEquals(0, snapshot.getJSONArray("recent").length());
        }
        finally
        {
            service.close();
            deleteTree(dir);
        }
    }

    @Test
    public void snapshotAggregatesPlaybackRows() throws Exception
    {
        Path dir = Files.createTempDirectory("dashboard-stats-test");
        Path db = dir.resolve("dashboard.db");
        DashboardStatsService service = new DashboardStatsService(db);
        long startedAt = System.currentTimeMillis() - 180000;
        try
        {
            service.init();
            try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath()))
            {
                try(PreparedStatement ps = connection.prepareStatement("INSERT INTO dashboard_playback_sessions ("
                        + "session_key, guild_id, guild_name, track_title, duration_ms, requester_id, requester_name, "
                        + "started_at, ended_at, played_ms, end_reason, skipped"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"))
                {
                    ps.setString(1, "session-1");
                    ps.setLong(2, 123L);
                    ps.setString(3, "Server");
                    ps.setString(4, "Song");
                    ps.setLong(5, 180000L);
                    ps.setLong(6, 456L);
                    ps.setString(7, "Requester");
                    ps.setLong(8, startedAt);
                    ps.setLong(9, startedAt + 125000L);
                    ps.setLong(10, 125000L);
                    ps.setString(11, "STOPPED");
                    ps.setInt(12, 1);
                    ps.executeUpdate();
                }
                try(PreparedStatement ps = connection.prepareStatement("INSERT INTO dashboard_events ("
                        + "event_type, guild_id, guild_name, track_title, user_id, user_name, detail, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)"))
                {
                    ps.setString(1, "track_skip");
                    ps.setLong(2, 123L);
                    ps.setString(3, "Server");
                    ps.setString(4, "Song");
                    ps.setLong(5, 456L);
                    ps.setString(6, "Requester");
                    ps.setString(7, "Skipped");
                    ps.setLong(8, startedAt + 125000L);
                    ps.executeUpdate();
                }
            }

            JSONObject snapshot = service.getSnapshot(5, 5, 5, 5, 5);

            assertEquals(1, snapshot.getJSONObject("summary").getLong("songsPlayed"));
            assertEquals(2, snapshot.getJSONObject("summary").getLong("minutesPlayed"));
            assertEquals(1, snapshot.getJSONObject("summary").getLong("skippedSongs"));
            assertEquals("456", snapshot.getJSONArray("requesters").getJSONObject(0).getString("id"));
            assertEquals("Song", snapshot.getJSONArray("tracks").getJSONObject(0).getString("title"));
            assertEquals(1, snapshot.getJSONArray("skippedTracks").getJSONObject(0).getLong("skips"));
            assertEquals(1, snapshot.getJSONArray("recent").length());
        }
        finally
        {
            service.close();
            deleteTree(dir);
        }
    }

    private static void deleteTree(Path path) throws IOException
    {
        if(!Files.exists(path))
            return;

        try(var paths = Files.walk(path))
        {
            paths.sorted(Comparator.reverseOrder()).forEach(p ->
            {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch(IOException ignored) {}
            });
        }
    }
}
