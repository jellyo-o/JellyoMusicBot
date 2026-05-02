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

import com.jagrosh.jmusicbot.utils.TrackIdentity;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

class PlaybackSessionHistory
{
    private final Set<String> trackKeys = new HashSet<>();
    private final LinkedList<MutableEntry> entries = new LinkedList<>();

    void remember(AudioTrack track)
    {
        Set<String> keys = TrackIdentity.keys(track);
        trackKeys.addAll(keys);

        MutableEntry next = MutableEntry.from(track, keys);
        if(next == null)
            return;

        if(!entries.isEmpty() && entries.getFirst().matches(next.keys))
            entries.getFirst().incrementFrom(next);
        else
            entries.addFirst(next);
    }

    boolean contains(AudioTrack track)
    {
        return containsAny(TrackIdentity.keys(track));
    }

    boolean contains(String identifier, String uri, String title, String author)
    {
        return containsAny(TrackIdentity.keys(identifier, uri, title, author));
    }

    Set<String> snapshot()
    {
        return new HashSet<>(trackKeys);
    }

    List<PlaybackHistoryStore.Entry> snapshotEntries()
    {
        List<PlaybackHistoryStore.Entry> snapshot = new ArrayList<>(entries.size());
        for(MutableEntry entry : entries)
            snapshot.add(entry.snapshot());
        return snapshot;
    }

    void clear()
    {
        trackKeys.clear();
        entries.clear();
    }

    private boolean containsAny(Set<String> keys)
    {
        for(String key : keys)
            if(trackKeys.contains(key))
                return true;
        return false;
    }

    private static final class MutableEntry
    {
        private final Set<String> keys;
        private String title;
        private String author;
        private String uri;
        private long duration;
        private boolean stream;
        private int count;

        private MutableEntry(Set<String> keys, String title, String author, String uri, long duration, boolean stream)
        {
            this.keys = new HashSet<>(keys);
            this.title = title;
            this.author = author;
            this.uri = uri;
            this.duration = duration;
            this.stream = stream;
            this.count = 1;
        }

        private static MutableEntry from(AudioTrack track, Set<String> keys)
        {
            if(track == null || track.getInfo() == null)
                return null;

            AudioTrackInfo info = track.getInfo();
            Set<String> entryKeys = keys == null || keys.isEmpty() ? TrackIdentity.keys(track) : keys;
            if(entryKeys.isEmpty())
                entryKeys = fallbackKeys(info);
            if(entryKeys.isEmpty())
                return null;

            return new MutableEntry(entryKeys,
                    info.title == null || info.title.isBlank() ? "Unknown track" : info.title,
                    info.author,
                    info.uri,
                    Math.max(0L, track.getDuration()),
                    info.isStream || track.getDuration() == Long.MAX_VALUE);
        }

        private static Set<String> fallbackKeys(AudioTrackInfo info)
        {
            Set<String> keys = new HashSet<>();
            if(info.title != null && !info.title.trim().isEmpty())
                keys.add(info.title.trim());
            return keys;
        }

        private boolean matches(Set<String> otherKeys)
        {
            for(String key : otherKeys)
                if(keys.contains(key))
                    return true;
            return false;
        }

        private void incrementFrom(MutableEntry latest)
        {
            keys.addAll(latest.keys);
            title = latest.title;
            author = latest.author;
            uri = latest.uri;
            duration = latest.duration;
            stream = latest.stream;
            count++;
        }

        private PlaybackHistoryStore.Entry snapshot()
        {
            return new PlaybackHistoryStore.Entry(title, author, uri, duration, stream, count);
        }
    }
}
