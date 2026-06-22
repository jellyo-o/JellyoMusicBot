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

import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.audio.RequestMetadata.UserInfo;
import com.jagrosh.jmusicbot.database.Database;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite-backed playback telemetry for the local dashboard.
 *
 * Writes are serialized on a daemon thread so Discord audio callbacks never wait
 * on dashboard database I/O. Reads open short-lived connections from HTTP
 * request threads.
 */
public class DashboardStatsService
{
    private final static Logger LOG = LoggerFactory.getLogger(DashboardStatsService.class);

    private final Path dbPath;
    private final AtomicLong sessionSequence = new AtomicLong();
    private final ExecutorService writer;
    private volatile boolean initialized;

    public DashboardStatsService(Path dbPath)
    {
        this.dbPath = dbPath;
        this.writer = Executors.newSingleThreadExecutor(r ->
        {
            Thread thread = new Thread(r, "dashboard-stats-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void init() throws Exception
    {
        Path parent = dbPath.toAbsolutePath().getParent();
        if(parent != null)
            Files.createDirectories(parent);

        try(Connection connection = openConnection(); Statement st = connection.createStatement())
        {
            st.execute("PRAGMA journal_mode=WAL");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS dashboard_playback_sessions ("
                    + "session_key TEXT PRIMARY KEY,"
                    + "guild_id INTEGER NOT NULL,"
                    + "guild_name TEXT,"
                    + "voice_channel_id INTEGER,"
                    + "voice_channel_name TEXT,"
                    + "track_identifier TEXT,"
                    + "track_title TEXT NOT NULL,"
                    + "track_author TEXT,"
                    + "track_uri TEXT,"
                    + "track_source TEXT,"
                    + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                    + "requester_id INTEGER,"
                    + "requester_name TEXT,"
                    + "requester_discrim TEXT,"
                    + "requester_avatar TEXT,"
                    + "request_query TEXT,"
                    + "started_at INTEGER NOT NULL,"
                    + "ended_at INTEGER,"
                    + "played_ms INTEGER NOT NULL DEFAULT 0,"
                    + "end_reason TEXT,"
                    + "skipped INTEGER NOT NULL DEFAULT 0,"
                    + "skip_actor_id INTEGER,"
                    + "skip_actor_name TEXT,"
                    + "skip_type TEXT,"
                    + "queue_size_at_start INTEGER NOT NULL DEFAULT 0,"
                    + "volume INTEGER NOT NULL DEFAULT 0"
                    + ")");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS dashboard_events ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "event_type TEXT NOT NULL,"
                    + "guild_id INTEGER,"
                    + "guild_name TEXT,"
                    + "track_title TEXT,"
                    + "track_uri TEXT,"
                    + "user_id INTEGER,"
                    + "user_name TEXT,"
                    + "detail TEXT,"
                    + "created_at INTEGER NOT NULL"
                    + ")");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dashboard_sessions_started ON dashboard_playback_sessions(started_at DESC)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dashboard_sessions_requester ON dashboard_playback_sessions(requester_id, started_at DESC)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dashboard_sessions_guild ON dashboard_playback_sessions(guild_id, started_at DESC)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_dashboard_events_created ON dashboard_events(created_at DESC)");
            st.executeUpdate("UPDATE dashboard_playback_sessions "
                    + "SET ended_at=started_at, played_ms=0, end_reason='BOT_RESTART' "
                    + "WHERE ended_at IS NULL");
        }

        initialized = true;
        LOG.info("Dashboard stats database ready at {}", dbPath.toAbsolutePath());
    }

    public boolean isInitialized()
    {
        return initialized;
    }

    public Path getDbPath()
    {
        return dbPath;
    }

    public String recordTrackStart(Guild guild, AudioPlayer player, AudioTrack track, int queueSize)
    {
        if(!initialized || guild == null || player == null || track == null)
            return null;

        long now = System.currentTimeMillis();
        String sessionKey = guild.getId() + "-" + now + "-" + sessionSequence.incrementAndGet();
        RequestMetadata metadata = track.getUserData(RequestMetadata.class);
        UserInfo requester = metadata == null ? null : metadata.user;
        RequestMetadata.RequestInfo request = metadata == null ? null : metadata.requestInfo;
        AudioChannel channel = guild.getAudioManager().getConnectedChannel();

        long guildId = guild.getIdLong();
        String guildName = guild.getName();
        Long channelId = channel == null ? null : channel.getIdLong();
        String channelName = channel == null ? null : channel.getName();
        String identifier = track.getIdentifier();
        String title = safeTrackTitle(track);
        String author = track.getInfo().author;
        String uri = track.getInfo().uri;
        String source = sourceName(track);
        long durationMs = safeDuration(track.getDuration());
        Long requesterId = requester == null ? null : requester.id;
        String requesterName = requester == null ? null : FormatUtil.formatUsername(requester);
        String requesterDiscrim = requester == null ? null : requester.discrim;
        String requesterAvatar = requester == null ? null : requester.avatar;
        String requestQuery = request == null ? null : request.query;
        int volume = player.getVolume();

        executeAsync(connection ->
        {
            try(PreparedStatement closePrior = connection.prepareStatement("UPDATE dashboard_playback_sessions "
                    + "SET ended_at=?, played_ms=MAX(0, ? - started_at), end_reason='REPLACED' "
                    + "WHERE guild_id=? AND ended_at IS NULL"))
            {
                closePrior.setLong(1, now);
                closePrior.setLong(2, now);
                closePrior.setLong(3, guildId);
                closePrior.executeUpdate();
            }

            try(PreparedStatement ps = connection.prepareStatement("INSERT INTO dashboard_playback_sessions ("
                    + "session_key, guild_id, guild_name, voice_channel_id, voice_channel_name, "
                    + "track_identifier, track_title, track_author, track_uri, track_source, duration_ms, "
                    + "requester_id, requester_name, requester_discrim, requester_avatar, request_query, "
                    + "started_at, queue_size_at_start, volume"
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"))
            {
                ps.setString(1, sessionKey);
                ps.setLong(2, guildId);
                ps.setString(3, guildName);
                setNullableLong(ps, 4, channelId);
                ps.setString(5, channelName);
                ps.setString(6, identifier);
                ps.setString(7, title);
                ps.setString(8, author);
                ps.setString(9, uri);
                ps.setString(10, source);
                ps.setLong(11, durationMs);
                setNullableLong(ps, 12, requesterId);
                ps.setString(13, requesterName);
                ps.setString(14, requesterDiscrim);
                ps.setString(15, requesterAvatar);
                ps.setString(16, requestQuery);
                ps.setLong(17, now);
                ps.setInt(18, queueSize);
                ps.setInt(19, volume);
                ps.executeUpdate();
            }

            insertEvent(connection, "track_start", guildId, guildName, title, uri, requesterId, requesterName,
                    "Started playback", now);
        });

        return sessionKey;
    }

    public void recordTrackEnd(String sessionKey, long guildId, String guildName, AudioTrack track, AudioTrackEndReason endReason, SkipInfo skipInfo)
    {
        if(!initialized || sessionKey == null)
            return;

        long now = System.currentTimeMillis();
        long playedMs = playedMs(track, endReason);
        String reason = endReason == null ? "UNKNOWN" : endReason.name();
        boolean skipped = skipInfo != null;
        String title = track == null ? null : safeTrackTitle(track);
        String uri = track == null ? null : track.getInfo().uri;

        executeAsync(connection ->
        {
            try(PreparedStatement ps = connection.prepareStatement("UPDATE dashboard_playback_sessions "
                    + "SET ended_at=?, played_ms=CASE WHEN ? >= 0 THEN ? ELSE MAX(0, ? - started_at) END, "
                    + "end_reason=?, skipped=?, skip_actor_id=?, skip_actor_name=?, skip_type=? "
                    + "WHERE session_key=?"))
            {
                ps.setLong(1, now);
                ps.setLong(2, playedMs);
                ps.setLong(3, playedMs);
                ps.setLong(4, now);
                ps.setString(5, reason);
                ps.setInt(6, skipped ? 1 : 0);
                setNullableLong(ps, 7, skipInfo == null ? null : skipInfo.actorId);
                ps.setString(8, skipInfo == null ? null : skipInfo.actorName);
                ps.setString(9, skipInfo == null ? null : skipInfo.type);
                ps.setString(10, sessionKey);
                ps.executeUpdate();
            }

            insertEvent(connection, skipped ? "track_skip" : "track_end", guildId, guildName, title, uri,
                    skipInfo == null ? null : skipInfo.actorId,
                    skipInfo == null ? null : skipInfo.actorName,
                    skipped ? "Skipped by " + skipInfo.actorName + " (" + skipInfo.type + ")" : "Ended: " + reason,
                    now);
        });
    }

    public void recordTrackIssue(long guildId, String guildName, AudioTrack track, String eventType, String detail)
    {
        if(!initialized)
            return;

        long now = System.currentTimeMillis();
        String title = track == null ? null : safeTrackTitle(track);
        String uri = track == null ? null : track.getInfo().uri;
        executeAsync(connection -> insertEvent(connection, eventType, guildId, guildName, title, uri, null, null, detail, now));
    }

    public JSONObject getSnapshot(int requesterLimit, int recentLimit, int guildLimit, int hourlyLimit, int trackLimit) throws SQLException
    {
        int safeRequesterLimit = clamp(requesterLimit, 1, 100);
        int safeRecentLimit = clamp(recentLimit, 1, 100);
        int safeGuildLimit = clamp(guildLimit, 1, 100);
        int safeHourlyLimit = clamp(hourlyLimit, 1, 72);
        int safeTrackLimit = clamp(trackLimit, 1, 100);

        try(Connection connection = openConnection())
        {
            JSONArray guilds = queryGuildStats(connection, safeGuildLimit);
            JSONObject snapshot = new JSONObject();
            snapshot.put("summary", querySummary(connection));
            snapshot.put("requesters", queryRequesterStats(connection, safeRequesterLimit));
            snapshot.put("guilds", guilds);
            snapshot.put("serverDetails", queryGuildDetails(connection, guilds,
                    Math.min(12, safeRecentLimit),
                    Math.min(10, safeTrackLimit),
                    Math.min(10, safeRequesterLimit)));
            snapshot.put("tracks", queryTopTracks(connection, safeTrackLimit, false));
            snapshot.put("skippedTracks", queryTopTracks(connection, safeTrackLimit, true));
            snapshot.put("sources", querySourceStats(connection));
            snapshot.put("history", queryPlaybackHistory(connection, safeRecentLimit, null));
            snapshot.put("recent", queryRecentEvents(connection, safeRecentLimit));
            snapshot.put("hourly", queryHourlyStats(connection, safeHourlyLimit));
            snapshot.put("daily", queryDailyStats(connection, 14));
            snapshot.put("hoursOfDay", queryHoursOfDayStats(connection));
            snapshot.put("database", dbPath.toAbsolutePath().toString());
            snapshot.put("generatedAt", System.currentTimeMillis());
            return snapshot;
        }
    }

    public void close()
    {
        initialized = false;
        writer.shutdown();
        try
        {
            if(!writer.awaitTermination(2, TimeUnit.SECONDS))
                writer.shutdownNow();
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }

    private Connection openConnection() throws SQLException
    {
        return Database.open(dbPath);
    }

    private JSONObject querySummary(Connection connection) throws SQLException
    {
        long now = System.currentTimeMillis();
        String sql = "SELECT COUNT(*) AS songs_played,"
                + "COALESCE(SUM(CASE WHEN ended_at IS NULL THEN MAX(0, ? - started_at) ELSE played_ms END), 0) AS played_ms,"
                + "COALESCE(SUM(CASE WHEN skipped=1 THEN 1 ELSE 0 END), 0) AS skipped_songs,"
                + "COALESCE(SUM(CASE WHEN end_reason='FINISHED' THEN 1 ELSE 0 END), 0) AS completed_songs,"
                + "COALESCE(SUM(CASE WHEN ended_at IS NULL THEN 1 ELSE 0 END), 0) AS active_sessions,"
                + "COUNT(DISTINCT guild_id) AS guilds_seen,"
                + "COUNT(DISTINCT CASE WHEN requester_id IS NOT NULL AND requester_id != 0 THEN requester_id END) AS unique_requesters "
                + "FROM dashboard_playback_sessions";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, now);
            try(ResultSet rs = ps.executeQuery())
            {
                JSONObject summary = new JSONObject();
                if(rs.next())
                {
                    long playedMs = rs.getLong("played_ms");
                    long songsPlayed = rs.getLong("songs_played");
                    long skippedSongs = rs.getLong("skipped_songs");
                    long completedSongs = rs.getLong("completed_songs");
                    summary.put("songsPlayed", rs.getLong("songs_played"));
                    summary.put("playedMs", playedMs);
                    summary.put("minutesPlayed", Math.round(playedMs / 60000.0));
                    summary.put("averagePlayedMs", songsPlayed == 0 ? 0 : Math.round((double)playedMs / songsPlayed));
                    summary.put("skippedSongs", skippedSongs);
                    summary.put("completedSongs", completedSongs);
                    summary.put("skipRate", songsPlayed == 0 ? 0 : skippedSongs / (double)songsPlayed);
                    summary.put("completionRate", songsPlayed == 0 ? 0 : completedSongs / (double)songsPlayed);
                    summary.put("activeSessions", rs.getLong("active_sessions"));
                    summary.put("guildsSeen", rs.getLong("guilds_seen"));
                    summary.put("uniqueRequesters", rs.getLong("unique_requesters"));
                }
                return summary;
            }
        }
    }

    private JSONArray queryTopTracks(Connection connection, int limit, boolean skippedOnly) throws SQLException
    {
        return queryTopTracks(connection, limit, skippedOnly, null);
    }

    private JSONArray queryTopTracks(Connection connection, int limit, boolean skippedOnly, Long guildId) throws SQLException
    {
        long now = System.currentTimeMillis();
        String where;
        if(skippedOnly && guildId != null)
            where = "WHERE skipped=1 AND guild_id=? ";
        else if(skippedOnly)
            where = "WHERE skipped=1 ";
        else if(guildId != null)
            where = "WHERE guild_id=? ";
        else
            where = "";
        String sql = "SELECT COALESCE(NULLIF(track_uri, ''), NULLIF(track_identifier, ''), track_title) AS track_key,"
                + "COALESCE(MAX(track_title), 'Unknown track') AS track_title,"
                + "MAX(track_author) AS track_author,"
                + "MAX(track_uri) AS track_uri,"
                + "MAX(track_source) AS track_source,"
                + "MAX(duration_ms) AS duration_ms,"
                + "COUNT(*) AS plays,"
                + "COALESCE(SUM(CASE WHEN ended_at IS NULL THEN MAX(0, ? - started_at) ELSE played_ms END), 0) AS played_ms,"
                + "COALESCE(SUM(CASE WHEN skipped=1 THEN 1 ELSE 0 END), 0) AS skips,"
                + "COUNT(DISTINCT CASE WHEN requester_id IS NOT NULL AND requester_id != 0 THEN requester_id END) AS requesters,"
                + "COUNT(DISTINCT guild_id) AS guilds,"
                + "MAX(started_at) AS last_played_at "
                + "FROM dashboard_playback_sessions "
                + where
                + "GROUP BY track_key "
                + "ORDER BY " + (skippedOnly ? "skips DESC, plays DESC, played_ms DESC" : "plays DESC, played_ms DESC, skips DESC") + ", last_played_at DESC "
                + "LIMIT ?";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            int index = 1;
            ps.setLong(index++, now);
            if(guildId != null)
                ps.setLong(index++, guildId);
            ps.setInt(index, limit);
            try(ResultSet rs = ps.executeQuery())
            {
                JSONArray tracks = new JSONArray();
                while(rs.next())
                {
                    long plays = rs.getLong("plays");
                    long playedMs = rs.getLong("played_ms");
                    JSONObject track = new JSONObject();
                    track.put("key", rs.getString("track_key"));
                    track.put("title", rs.getString("track_title"));
                    track.put("author", rs.getString("track_author"));
                    track.put("uri", rs.getString("track_uri"));
                    track.put("source", rs.getString("track_source"));
                    track.put("durationMs", rs.getLong("duration_ms"));
                    track.put("plays", plays);
                    track.put("playedMs", playedMs);
                    track.put("averagePlayedMs", plays == 0 ? 0 : Math.round((double)playedMs / plays));
                    track.put("skips", rs.getLong("skips"));
                    track.put("requesters", rs.getLong("requesters"));
                    track.put("guilds", rs.getLong("guilds"));
                    track.put("lastPlayedAt", rs.getLong("last_played_at"));
                    tracks.put(track);
                }
                return tracks;
            }
        }
    }

    private JSONArray querySourceStats(Connection connection) throws SQLException
    {
        return querySourceStats(connection, null);
    }

    private JSONArray querySourceStats(Connection connection, Long guildId) throws SQLException
    {
        long now = System.currentTimeMillis();
        String where = guildId == null ? "" : "WHERE guild_id=? ";
        String sql = "SELECT COALESCE(NULLIF(track_source, ''), 'unknown') AS source,"
                + "COUNT(*) AS plays,"
                + "COALESCE(SUM(CASE WHEN ended_at IS NULL THEN MAX(0, ? - started_at) ELSE played_ms END), 0) AS played_ms,"
                + "COALESCE(SUM(CASE WHEN skipped=1 THEN 1 ELSE 0 END), 0) AS skips,"
                + "COUNT(DISTINCT COALESCE(NULLIF(track_uri, ''), NULLIF(track_identifier, ''), track_title)) AS tracks "
                + "FROM dashboard_playback_sessions "
                + where
                + "GROUP BY source "
                + "ORDER BY plays DESC, played_ms DESC";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, now);
            if(guildId != null)
                ps.setLong(2, guildId);
            try(ResultSet rs = ps.executeQuery())
            {
                JSONArray sources = new JSONArray();
                while(rs.next())
                {
                    JSONObject source = new JSONObject();
                    source.put("source", rs.getString("source"));
                    source.put("plays", rs.getLong("plays"));
                    source.put("playedMs", rs.getLong("played_ms"));
                    source.put("skips", rs.getLong("skips"));
                    source.put("tracks", rs.getLong("tracks"));
                    sources.put(source);
                }
                return sources;
            }
        }
    }

    private JSONArray queryRequesterStats(Connection connection, int limit) throws SQLException
    {
        return queryRequesterStats(connection, limit, null);
    }

    private JSONArray queryRequesterStats(Connection connection, int limit, Long guildId) throws SQLException
    {
        long now = System.currentTimeMillis();
        String sql = "SELECT requester_id,"
                + "COALESCE(MAX(requester_name), 'Unknown') AS requester_name,"
                + "MAX(requester_avatar) AS requester_avatar,"
                + "COUNT(*) AS songs,"
                + "COALESCE(SUM(CASE WHEN ended_at IS NULL THEN MAX(0, ? - started_at) ELSE played_ms END), 0) AS played_ms,"
                + "COALESCE(SUM(CASE WHEN skipped=1 THEN 1 ELSE 0 END), 0) AS skips,"
                + "MAX(started_at) AS last_requested_at "
                + "FROM dashboard_playback_sessions "
                + "WHERE requester_id IS NOT NULL AND requester_id != 0 "
                + (guildId == null ? "" : "AND guild_id=? ")
                + "GROUP BY requester_id "
                + "ORDER BY songs DESC, played_ms DESC, last_requested_at DESC "
                + "LIMIT ?";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            int index = 1;
            ps.setLong(index++, now);
            if(guildId != null)
                ps.setLong(index++, guildId);
            ps.setInt(index, limit);
            try(ResultSet rs = ps.executeQuery())
            {
                JSONArray requesters = new JSONArray();
                while(rs.next())
                {
                    JSONObject requester = new JSONObject();
                    requester.put("id", Long.toString(rs.getLong("requester_id")));
                    requester.put("name", rs.getString("requester_name"));
                    requester.put("avatar", rs.getString("requester_avatar"));
                    requester.put("songs", rs.getLong("songs"));
                    requester.put("playedMs", rs.getLong("played_ms"));
                    requester.put("skips", rs.getLong("skips"));
                    requester.put("lastRequestedAt", rs.getLong("last_requested_at"));
                    requesters.put(requester);
                }
                return requesters;
            }
        }
    }

    private JSONArray queryGuildStats(Connection connection, int limit) throws SQLException
    {
        long now = System.currentTimeMillis();
        String sql = "SELECT guild_id,"
                + "COALESCE(MAX(guild_name), 'Unknown server') AS guild_name,"
                + "COUNT(*) AS songs,"
                + "COALESCE(SUM(CASE WHEN ended_at IS NULL THEN MAX(0, ? - started_at) ELSE played_ms END), 0) AS played_ms,"
                + "COALESCE(SUM(CASE WHEN skipped=1 THEN 1 ELSE 0 END), 0) AS skips,"
                + "COALESCE(SUM(CASE WHEN end_reason='FINISHED' THEN 1 ELSE 0 END), 0) AS completed_songs,"
                + "COALESCE(SUM(CASE WHEN ended_at IS NULL THEN 1 ELSE 0 END), 0) AS active_sessions,"
                + "COUNT(DISTINCT CASE WHEN requester_id IS NOT NULL AND requester_id != 0 THEN requester_id END) AS requesters,"
                + "COUNT(DISTINCT COALESCE(NULLIF(track_uri, ''), NULLIF(track_identifier, ''), track_title)) AS tracks,"
                + "COUNT(DISTINCT COALESCE(NULLIF(track_source, ''), 'unknown')) AS sources,"
                + "MAX(started_at) AS last_played_at "
                + "FROM dashboard_playback_sessions "
                + "GROUP BY guild_id "
                + "ORDER BY songs DESC, played_ms DESC, last_played_at DESC "
                + "LIMIT ?";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, now);
            ps.setInt(2, limit);
            try(ResultSet rs = ps.executeQuery())
            {
                JSONArray guilds = new JSONArray();
                while(rs.next())
                {
                    long songs = rs.getLong("songs");
                    long playedMs = rs.getLong("played_ms");
                    long skips = rs.getLong("skips");
                    long completedSongs = rs.getLong("completed_songs");
                    JSONObject guild = new JSONObject();
                    guild.put("id", Long.toString(rs.getLong("guild_id")));
                    guild.put("name", rs.getString("guild_name"));
                    guild.put("songs", songs);
                    guild.put("playedMs", playedMs);
                    guild.put("minutesPlayed", Math.round(playedMs / 60000.0));
                    guild.put("averagePlayedMs", songs == 0 ? 0 : Math.round((double)playedMs / songs));
                    guild.put("skips", skips);
                    guild.put("completedSongs", completedSongs);
                    guild.put("activeSessions", rs.getLong("active_sessions"));
                    guild.put("skipRate", songs == 0 ? 0 : skips / (double)songs);
                    guild.put("completionRate", songs == 0 ? 0 : completedSongs / (double)songs);
                    guild.put("requesters", rs.getLong("requesters"));
                    guild.put("tracks", rs.getLong("tracks"));
                    guild.put("sources", rs.getLong("sources"));
                    guild.put("lastPlayedAt", rs.getLong("last_played_at"));
                    guilds.put(guild);
                }
                return guilds;
            }
        }
    }

    private JSONArray queryGuildDetails(Connection connection, JSONArray guilds, int historyLimit, int trackLimit, int requesterLimit) throws SQLException
    {
        JSONArray details = new JSONArray();
        for(int i = 0; i < guilds.length(); i++)
        {
            JSONObject guild = guilds.getJSONObject(i);
            long guildId;
            try
            {
                guildId = Long.parseLong(guild.getString("id"));
            }
            catch(NumberFormatException ex)
            {
                continue;
            }

            JSONObject detail = new JSONObject();
            detail.put("id", guild.getString("id"));
            detail.put("name", guild.optString("name", "Unknown server"));
            detail.put("summary", new JSONObject(guild.toString()));
            detail.put("history", queryPlaybackHistory(connection, historyLimit, guildId));
            detail.put("topTracks", queryTopTracks(connection, trackLimit, false, guildId));
            detail.put("requesters", queryRequesterStats(connection, requesterLimit, guildId));
            detail.put("sources", querySourceStats(connection, guildId));
            details.put(detail);
        }
        return details;
    }

    private JSONArray queryRecentEvents(Connection connection, int limit) throws SQLException
    {
        String sql = "SELECT event_type, guild_id, guild_name, track_title, track_uri, user_id, user_name, detail, created_at "
                + "FROM dashboard_events ORDER BY created_at DESC LIMIT ?";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setInt(1, limit);
            try(ResultSet rs = ps.executeQuery())
            {
                JSONArray recent = new JSONArray();
                while(rs.next())
                {
                    JSONObject event = new JSONObject();
                    event.put("type", rs.getString("event_type"));
                    long guildId = rs.getLong("guild_id");
                    event.put("guildId", rs.wasNull() ? JSONObject.NULL : Long.toString(guildId));
                    event.put("guildName", rs.getString("guild_name"));
                    event.put("trackTitle", rs.getString("track_title"));
                    event.put("trackUri", rs.getString("track_uri"));
                    long userId = rs.getLong("user_id");
                    event.put("userId", rs.wasNull() ? JSONObject.NULL : Long.toString(userId));
                    event.put("userName", rs.getString("user_name"));
                    event.put("detail", rs.getString("detail"));
                    event.put("createdAt", rs.getLong("created_at"));
                    recent.put(event);
                }
                return recent;
            }
        }
    }

    private JSONArray queryPlaybackHistory(Connection connection, int limit, Long guildId) throws SQLException
    {
        long now = System.currentTimeMillis();
        String sql = "SELECT session_key, guild_id, guild_name, voice_channel_id, voice_channel_name, "
                + "track_identifier, track_title, track_author, track_uri, track_source, duration_ms, "
                + "requester_id, requester_name, requester_avatar, request_query, started_at, ended_at, "
                + "CASE WHEN ended_at IS NULL THEN MAX(0, ? - started_at) ELSE played_ms END AS effective_played_ms, "
                + "end_reason, skipped, skip_actor_id, skip_actor_name, skip_type, queue_size_at_start, volume "
                + "FROM dashboard_playback_sessions "
                + (guildId == null ? "" : "WHERE guild_id=? ")
                + "ORDER BY started_at DESC LIMIT ?";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            int index = 1;
            ps.setLong(index++, now);
            if(guildId != null)
                ps.setLong(index++, guildId);
            ps.setInt(index, limit);
            try(ResultSet rs = ps.executeQuery())
            {
                JSONArray history = new JSONArray();
                while(rs.next())
                {
                    JSONObject row = new JSONObject();
                    row.put("sessionKey", rs.getString("session_key"));
                    row.put("guild", new JSONObject()
                            .put("id", Long.toString(rs.getLong("guild_id")))
                            .put("name", rs.getString("guild_name")));
                    row.put("channel", new JSONObject()
                            .put("id", nullableLongId(rs, "voice_channel_id"))
                            .put("name", rs.getString("voice_channel_name")));
                    row.put("track", new JSONObject()
                            .put("identifier", rs.getString("track_identifier"))
                            .put("title", rs.getString("track_title"))
                            .put("author", rs.getString("track_author"))
                            .put("uri", rs.getString("track_uri"))
                            .put("source", rs.getString("track_source"))
                            .put("durationMs", rs.getLong("duration_ms")));
                    row.put("requester", new JSONObject()
                            .put("id", nullableLongId(rs, "requester_id"))
                            .put("name", rs.getString("requester_name"))
                            .put("avatar", rs.getString("requester_avatar"))
                            .put("query", rs.getString("request_query")));
                    row.put("startedAt", rs.getLong("started_at"));
                    row.put("endedAt", nullableLongValue(rs, "ended_at"));
                    row.put("playedMs", rs.getLong("effective_played_ms"));
                    row.put("endReason", rs.getString("end_reason"));
                    row.put("skipped", rs.getInt("skipped") == 1);
                    row.put("skipActor", new JSONObject()
                            .put("id", nullableLongId(rs, "skip_actor_id"))
                            .put("name", rs.getString("skip_actor_name")));
                    row.put("skipType", rs.getString("skip_type"));
                    row.put("queueSizeAtStart", rs.getInt("queue_size_at_start"));
                    row.put("volume", rs.getInt("volume"));
                    history.put(row);
                }
                return history;
            }
        }
    }

    private JSONArray queryHourlyStats(Connection connection, int limit) throws SQLException
    {
        long now = System.currentTimeMillis();
        long since = now - TimeUnit.HOURS.toMillis(limit - 1L);
        String sql = "SELECT strftime('%Y-%m-%d %H:00', started_at / 1000, 'unixepoch') AS bucket,"
                + "MIN(started_at) AS bucket_start,"
                + "COUNT(*) AS songs,"
                + "COALESCE(SUM(CASE WHEN ended_at IS NULL THEN MAX(0, ? - started_at) ELSE played_ms END), 0) AS played_ms,"
                + "COALESCE(SUM(CASE WHEN skipped=1 THEN 1 ELSE 0 END), 0) AS skips "
                + "FROM dashboard_playback_sessions "
                + "WHERE started_at >= ? "
                + "GROUP BY bucket "
                + "ORDER BY bucket_start ASC";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, now);
            ps.setLong(2, since);
            try(ResultSet rs = ps.executeQuery())
            {
                JSONArray buckets = new JSONArray();
                while(rs.next())
                {
                    JSONObject bucket = new JSONObject();
                    bucket.put("bucket", rs.getString("bucket"));
                    bucket.put("bucketStart", rs.getLong("bucket_start"));
                    bucket.put("songs", rs.getLong("songs"));
                    bucket.put("playedMs", rs.getLong("played_ms"));
                    bucket.put("skips", rs.getLong("skips"));
                    buckets.put(bucket);
                }
                return buckets;
            }
        }
    }

    private JSONArray queryDailyStats(Connection connection, int days) throws SQLException
    {
        long now = System.currentTimeMillis();
        long since = now - TimeUnit.DAYS.toMillis(days - 1L);
        String sql = "SELECT strftime('%Y-%m-%d', started_at / 1000, 'unixepoch', 'localtime') AS bucket,"
                + "MIN(started_at) AS bucket_start,"
                + "COUNT(*) AS songs,"
                + "COALESCE(SUM(CASE WHEN ended_at IS NULL THEN MAX(0, ? - started_at) ELSE played_ms END), 0) AS played_ms,"
                + "COALESCE(SUM(CASE WHEN skipped=1 THEN 1 ELSE 0 END), 0) AS skips,"
                + "COUNT(DISTINCT CASE WHEN requester_id IS NOT NULL AND requester_id != 0 THEN requester_id END) AS requesters "
                + "FROM dashboard_playback_sessions "
                + "WHERE started_at >= ? "
                + "GROUP BY bucket "
                + "ORDER BY bucket_start ASC";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, now);
            ps.setLong(2, since);
            try(ResultSet rs = ps.executeQuery())
            {
                JSONArray buckets = new JSONArray();
                while(rs.next())
                {
                    JSONObject bucket = new JSONObject();
                    bucket.put("bucket", rs.getString("bucket"));
                    bucket.put("bucketStart", rs.getLong("bucket_start"));
                    bucket.put("songs", rs.getLong("songs"));
                    bucket.put("playedMs", rs.getLong("played_ms"));
                    bucket.put("skips", rs.getLong("skips"));
                    bucket.put("requesters", rs.getLong("requesters"));
                    buckets.put(bucket);
                }
                return buckets;
            }
        }
    }

    private JSONArray queryHoursOfDayStats(Connection connection) throws SQLException
    {
        long now = System.currentTimeMillis();
        String sql = "SELECT strftime('%H', started_at / 1000, 'unixepoch', 'localtime') AS hour,"
                + "COUNT(*) AS songs,"
                + "COALESCE(SUM(CASE WHEN ended_at IS NULL THEN MAX(0, ? - started_at) ELSE played_ms END), 0) AS played_ms,"
                + "COALESCE(SUM(CASE WHEN skipped=1 THEN 1 ELSE 0 END), 0) AS skips "
                + "FROM dashboard_playback_sessions "
                + "GROUP BY hour "
                + "ORDER BY hour ASC";
        try(PreparedStatement ps = connection.prepareStatement(sql))
        {
            ps.setLong(1, now);
            try(ResultSet rs = ps.executeQuery())
            {
                JSONArray buckets = new JSONArray();
                while(rs.next())
                {
                    JSONObject bucket = new JSONObject();
                    bucket.put("hour", rs.getString("hour"));
                    bucket.put("songs", rs.getLong("songs"));
                    bucket.put("playedMs", rs.getLong("played_ms"));
                    bucket.put("skips", rs.getLong("skips"));
                    buckets.put(bucket);
                }
                return buckets;
            }
        }
    }

    private void insertEvent(Connection connection, String eventType, Long guildId, String guildName, String trackTitle,
                             String trackUri, Long userId, String userName, String detail, long createdAt) throws SQLException
    {
        try(PreparedStatement ps = connection.prepareStatement("INSERT INTO dashboard_events ("
                + "event_type, guild_id, guild_name, track_title, track_uri, user_id, user_name, detail, created_at"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"))
        {
            ps.setString(1, eventType);
            setNullableLong(ps, 2, guildId);
            ps.setString(3, guildName);
            ps.setString(4, trackTitle);
            ps.setString(5, trackUri);
            setNullableLong(ps, 6, userId);
            ps.setString(7, userName);
            ps.setString(8, detail);
            ps.setLong(9, createdAt);
            ps.executeUpdate();
        }
    }

    private void executeAsync(SqlOperation operation)
    {
        if(!initialized)
            return;

        writer.execute(() ->
        {
            try(Connection connection = openConnection())
            {
                operation.run(connection);
            }
            catch(Exception ex)
            {
                LOG.warn("Dashboard stats write failed", ex);
            }
        });
    }

    private static long playedMs(AudioTrack track, AudioTrackEndReason endReason)
    {
        if(track == null)
            return -1;
        if(endReason == AudioTrackEndReason.FINISHED && track.getDuration() != Long.MAX_VALUE)
            return safeDuration(track.getDuration());
        return Math.max(0, track.getPosition());
    }

    private static long safeDuration(long duration)
    {
        if(duration < 0 || duration == Long.MAX_VALUE)
            return 0;
        return duration;
    }

    private static String safeTrackTitle(AudioTrack track)
    {
        if(track.getInfo().title == null || track.getInfo().title.isBlank())
            return "Unknown track";
        return track.getInfo().title;
    }

    private static String sourceName(AudioTrack track)
    {
        return track.getSourceManager() == null ? "unknown" : track.getSourceManager().getSourceName();
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException
    {
        if(value == null)
            ps.setNull(index, Types.BIGINT);
        else
            ps.setLong(index, value);
    }

    private static Object nullableLongId(ResultSet rs, String column) throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? JSONObject.NULL : Long.toString(value);
    }

    private static Object nullableLongValue(ResultSet rs, String column) throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? JSONObject.NULL : value;
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private interface SqlOperation
    {
        void run(Connection connection) throws Exception;
    }

    public static class SkipInfo
    {
        private final long actorId;
        private final String actorName;
        private final String type;

        private SkipInfo(long actorId, String actorName, String type)
        {
            this.actorId = actorId;
            this.actorName = actorName;
            this.type = type;
        }

        public static SkipInfo fromUser(User user, String type)
        {
            if(user == null)
                return new SkipInfo(0L, "Unknown", type);
            return new SkipInfo(user.getIdLong(), FormatUtil.formatUsername(user), type);
        }
    }
}
