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
import java.util.HashSet;
import java.util.Set;

class PlaybackSessionHistory
{
    private final Set<String> trackKeys = new HashSet<>();

    void remember(AudioTrack track)
    {
        trackKeys.addAll(TrackIdentity.keys(track));
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

    void clear()
    {
        trackKeys.clear();
    }

    private boolean containsAny(Set<String> keys)
    {
        for(String key : keys)
            if(trackKeys.contains(key))
                return true;
        return false;
    }
}
