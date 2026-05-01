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

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.Executors;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded read-only dashboard server for local bot operators.
 */
public class DashboardServer
{
    private final static Logger LOG = LoggerFactory.getLogger(DashboardServer.class);

    private final Bot bot;
    private final DashboardStatsService stats;
    private final String bindAddress;
    private final int port;
    private HttpServer server;

    public DashboardServer(Bot bot, DashboardStatsService stats, String bindAddress, int port)
    {
        this.bot = bot;
        this.stats = stats;
        this.bindAddress = bindAddress;
        this.port = port;
    }

    public void start() throws IOException
    {
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(bindAddress), port);
        server = HttpServer.create(address, 0);
        server.createContext("/api/snapshot", this::handleSnapshot);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(4, r ->
        {
            Thread thread = new Thread(r, "dashboard-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        LOG.info("Dashboard server listening on http://{}:{}", bindAddress, port);
    }

    public void stop()
    {
        if(server != null)
        {
            server.stop(1);
            server = null;
        }
    }

    private void handleSnapshot(HttpExchange exchange) throws IOException
    {
        if(!requireGet(exchange))
            return;

        try
        {
            JSONObject snapshot = stats.getSnapshot(
                    intParam(exchange, "requesters", 25),
                    intParam(exchange, "recent", 40),
                    intParam(exchange, "guilds", 25),
                    intParam(exchange, "hours", 24),
                    intParam(exchange, "tracks", 25));
            snapshot.put("live", livePlayback());
            snapshot.put("bot", botStatus());
            sendJson(exchange, 200, snapshot);
        }
        catch(SQLException ex)
        {
            LOG.warn("Failed to build dashboard snapshot", ex);
            sendJson(exchange, 500, new JSONObject().put("error", "Unable to read dashboard database"));
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException
    {
        if(!requireGet(exchange))
            return;

        sendJson(exchange, 200, new JSONObject()
                .put("ok", true)
                .put("databaseReady", stats.isInitialized())
                .put("bot", botStatus()));
    }

    private void handleStatic(HttpExchange exchange) throws IOException
    {
        if(!requireGet(exchange))
            return;

        String path = exchange.getRequestURI().getPath();
        if(path == null || path.equals("/") || path.equals("/dashboard") || path.equals("/dashboard/"))
        {
            sendResource(exchange, "/dashboard/index.html", "text/html; charset=utf-8");
            return;
        }

        switch(path)
        {
            case "/dashboard/styles.css":
                sendResource(exchange, "/dashboard/styles.css", "text/css; charset=utf-8");
                break;
            case "/dashboard/app.js":
                sendResource(exchange, "/dashboard/app.js", "application/javascript; charset=utf-8");
                break;
            default:
                sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
        }
    }

    private JSONObject botStatus()
    {
        JSONObject status = new JSONObject();
        JDA jda = bot.getJDA();
        status.put("dashboardEnabled", true);
        status.put("bindAddress", bindAddress);
        status.put("port", port);
        status.put("database", stats.getDbPath().toAbsolutePath().toString());

        if(jda == null)
        {
            status.put("jdaStatus", "STARTING");
            status.put("guildCount", 0);
            return status;
        }

        status.put("jdaStatus", jda.getStatus().name());
        status.put("guildCount", jda.getGuilds().size());
        if(jda.getSelfUser() != null)
        {
            status.put("botName", jda.getSelfUser().getName());
            status.put("botAvatar", jda.getSelfUser().getEffectiveAvatarUrl());
        }
        return status;
    }

    private JSONArray livePlayback()
    {
        JSONArray live = new JSONArray();
        JDA jda = bot.getJDA();
        if(jda == null)
            return live;

        for(Guild guild : jda.getGuilds())
        {
            if(!(guild.getAudioManager().getSendingHandler() instanceof AudioHandler))
                continue;

            AudioHandler handler = (AudioHandler)guild.getAudioManager().getSendingHandler();
            AudioTrack track = handler.getPlayer().getPlayingTrack();
            if(track == null)
                continue;

            AudioChannel channel = guild.getAudioManager().getConnectedChannel();
            RequestMetadata metadata = handler.getRequestMetadata();
            JSONObject row = new JSONObject();
            row.put("guild", new JSONObject()
                    .put("id", guild.getId())
                    .put("name", guild.getName())
                    .put("icon", guild.getIconUrl()));
            row.put("channel", channel == null ? JSONObject.NULL : new JSONObject()
                    .put("id", channel.getId())
                    .put("name", channel.getName()));
            row.put("track", new JSONObject()
                    .put("identifier", track.getIdentifier())
                    .put("title", track.getInfo().title)
                    .put("author", track.getInfo().author)
                    .put("uri", track.getInfo().uri)
                    .put("durationMs", track.getDuration() == Long.MAX_VALUE ? 0 : track.getDuration())
                    .put("positionMs", Math.max(0, track.getPosition()))
                    .put("source", track.getSourceManager() == null ? "unknown" : track.getSourceManager().getSourceName())
                    .put("artwork", AudioHandler.getNowPlayingThumbnail(track)));
            row.put("requester", requester(metadata));
            row.put("queueSize", handler.getQueue() == null ? 0 : handler.getQueue().size());
            row.put("volume", handler.getPlayer().getVolume());
            row.put("paused", handler.getPlayer().isPaused());
            row.put("votes", handler.getVotes().size());
            row.put("startedAt", handler.getCurrentTrackStartedAt());
            live.put(row);
        }
        return live;
    }

    private JSONObject requester(RequestMetadata metadata)
    {
        if(metadata == null || metadata.user == null)
            return new JSONObject().put("id", JSONObject.NULL).put("name", "Autoplay or unknown");

        return new JSONObject()
                .put("id", Long.toString(metadata.user.id))
                .put("name", FormatUtil.formatUsername(metadata.user))
                .put("avatar", metadata.user.avatar);
    }

    private boolean requireGet(HttpExchange exchange) throws IOException
    {
        if("GET".equalsIgnoreCase(exchange.getRequestMethod()))
            return true;

        sendText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8");
        return false;
    }

    private int intParam(HttpExchange exchange, String key, int fallback)
    {
        String query = exchange.getRequestURI().getRawQuery();
        if(query == null || query.isBlank())
            return fallback;

        for(String part : query.split("&"))
        {
            String[] pair = part.split("=", 2);
            if(pair.length == 2 && key.equals(pair[0]))
            {
                try
                {
                    return Integer.parseInt(pair[1]);
                }
                catch(NumberFormatException ignored) {}
            }
        }
        return fallback;
    }

    private void sendResource(HttpExchange exchange, String resource, String contentType) throws IOException
    {
        try(InputStream input = DashboardServer.class.getResourceAsStream(resource))
        {
            if(input == null)
            {
                sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
                return;
            }

            byte[] body = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", cacheControl(resource));
            send(exchange, 200, body);
        }
    }

    private void sendJson(HttpExchange exchange, int status, JSONObject object) throws IOException
    {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        send(exchange, status, object.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void sendText(HttpExchange exchange, int status, String text, String contentType) throws IOException
    {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        send(exchange, status, text.getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int status, byte[] body) throws IOException
    {
        exchange.sendResponseHeaders(status, body.length);
        try(OutputStream output = exchange.getResponseBody())
        {
            output.write(body);
        }
    }

    private String cacheControl(String resource)
    {
        String lower = resource.toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") ? "no-store" : "public, max-age=60";
    }
}
