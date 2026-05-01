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
package com.jagrosh.jmusicbot.autoplay;

import com.jagrosh.jmusicbot.utils.OtherUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayHistoryStore
{
    private static final Logger LOG = LoggerFactory.getLogger(PlayHistoryStore.class);
    private static final String HISTORY_FILE = "playhistory.json";
    private static final int MAX_TRACKS_PER_GUILD = 250;

    private final Path path;
    private final Map<Long, Map<String, MutableTrackReference>> history = new HashMap<>();

    public PlayHistoryStore()
    {
        this(OtherUtil.getPath(HISTORY_FILE));
    }

    public PlayHistoryStore(Path path)
    {
        this.path = path;
        load();
    }

    public synchronized void record(long guildId, AudioTrack track)
    {
        if(track == null || track.getInfo() == null)
            return;

        String identifier = emptyToNull(track.getIdentifier());
        String uri = emptyToNull(track.getInfo().uri);
        if(identifier == null && uri == null)
            return;

        String source = track.getSourceManager() == null ? "unknown" : track.getSourceManager().getSourceName();
        String key = key(source, identifier == null ? uri : identifier);
        Map<String, MutableTrackReference> guildHistory = history.computeIfAbsent(guildId, id -> new HashMap<>());
        MutableTrackReference reference = guildHistory.get(key);
        if(reference == null)
        {
            reference = new MutableTrackReference(source, identifier, uri, track.getInfo().title, track.getInfo().author);
            guildHistory.put(key, reference);
        }
        else
        {
            reference.uri = uri;
            reference.title = track.getInfo().title;
            reference.author = track.getInfo().author;
        }

        reference.plays++;
        reference.lastPlayed = System.currentTimeMillis();
        prune(guildHistory);
        write();
    }

    public synchronized Optional<TrackReference> chooseWeighted(long guildId, Set<String> excludedIdentifiers, Random random)
    {
        Map<String, MutableTrackReference> guildHistory = history.get(guildId);
        if(guildHistory == null || guildHistory.isEmpty())
            return Optional.empty();

        Set<String> excluded = excludedIdentifiers == null ? new HashSet<>() : excludedIdentifiers;
        List<MutableTrackReference> candidates = new ArrayList<>();
        int totalWeight = 0;
        for(MutableTrackReference reference : guildHistory.values())
        {
            if(excluded.contains(reference.identity()) || (reference.uri != null && excluded.contains(reference.uri)))
                continue;
            int weight = Math.max(1, reference.plays);
            totalWeight += weight;
            candidates.add(reference);
        }

        if(candidates.isEmpty())
            return Optional.empty();

        int selected = random.nextInt(totalWeight);
        int cursor = 0;
        for(MutableTrackReference reference : candidates)
        {
            cursor += Math.max(1, reference.plays);
            if(selected < cursor)
                return Optional.of(reference.toImmutable());
        }
        return Optional.of(candidates.get(candidates.size() - 1).toImmutable());
    }

    public synchronized int size(long guildId)
    {
        Map<String, MutableTrackReference> guildHistory = history.get(guildId);
        return guildHistory == null ? 0 : guildHistory.size();
    }

    private void load()
    {
        try
        {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(path)));
            for(String guildId : root.keySet())
            {
                JSONArray tracks = root.getJSONArray(guildId);
                Map<String, MutableTrackReference> guildHistory = new HashMap<>();
                for(int i = 0; i < tracks.length(); i++)
                {
                    JSONObject json = tracks.getJSONObject(i);
                    MutableTrackReference reference = MutableTrackReference.fromJson(json);
                    guildHistory.put(key(reference.source, reference.identifier == null ? reference.uri : reference.identifier), reference);
                }
                prune(guildHistory);
                history.put(Long.parseLong(guildId), guildHistory);
            }
            LOG.info("Loaded play history from {}", path.toAbsolutePath());
        }
        catch(NoSuchFileException ex)
        {
            LOG.info("No play history found at {}; it will be created when tracks are recorded", path.toAbsolutePath());
        }
        catch(IOException | JSONException | NumberFormatException ex)
        {
            LOG.warn("Failed to load play history from {}", path.toAbsolutePath(), ex);
        }
    }

    private void write()
    {
        JSONObject root = new JSONObject();
        history.forEach((guildId, tracks) -> {
            JSONArray array = new JSONArray();
            tracks.values().stream()
                    .sorted(Comparator.comparingLong((MutableTrackReference reference) -> reference.lastPlayed).reversed())
                    .forEach(reference -> array.put(reference.toJson()));
            root.put(Long.toString(guildId), array);
        });

        try
        {
            Files.write(path, root.toString(4).getBytes());
        }
        catch(IOException ex)
        {
            LOG.warn("Failed to write play history to {}", path.toAbsolutePath(), ex);
        }
    }

    private static void prune(Map<String, MutableTrackReference> guildHistory)
    {
        if(guildHistory.size() <= MAX_TRACKS_PER_GUILD)
            return;

        List<String> keys = new ArrayList<>(guildHistory.keySet());
        keys.sort(Comparator
                .comparingInt((String key) -> guildHistory.get(key).plays)
                .thenComparingLong(key -> guildHistory.get(key).lastPlayed));
        for(int i = 0; guildHistory.size() > MAX_TRACKS_PER_GUILD && i < keys.size(); i++)
            guildHistory.remove(keys.get(i));
    }

    private static String key(String source, String identifier)
    {
        return (source == null ? "unknown" : source) + ":" + identifier;
    }

    private static String emptyToNull(String value)
    {
        return value == null || value.isEmpty() ? null : value;
    }

    private static class MutableTrackReference
    {
        private final String source;
        private final String identifier;
        private String uri;
        private String title;
        private String author;
        private int plays;
        private long lastPlayed;

        private MutableTrackReference(String source, String identifier, String uri, String title, String author)
        {
            this.source = source;
            this.identifier = identifier;
            this.uri = uri;
            this.title = title;
            this.author = author;
        }

        private String identity()
        {
            return identifier == null ? uri : identifier;
        }

        private TrackReference toImmutable()
        {
            return new TrackReference(source, identifier, uri, title, author, plays, lastPlayed);
        }

        private JSONObject toJson()
        {
            JSONObject json = new JSONObject();
            json.put("source", source);
            if(identifier != null)
                json.put("identifier", identifier);
            if(uri != null)
                json.put("uri", uri);
            if(title != null)
                json.put("title", title);
            if(author != null)
                json.put("author", author);
            json.put("plays", plays);
            json.put("last_played", lastPlayed);
            return json;
        }

        private static MutableTrackReference fromJson(JSONObject json)
        {
            MutableTrackReference reference = new MutableTrackReference(
                    json.optString("source", "unknown"),
                    json.has("identifier") ? json.getString("identifier") : null,
                    json.has("uri") ? json.getString("uri") : null,
                    json.optString("title", null),
                    json.optString("author", null));
            reference.plays = Math.max(1, json.optInt("plays", 1));
            reference.lastPlayed = json.optLong("last_played", 0);
            return reference;
        }
    }
}
