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

public class TrackReference
{
    private final String source;
    private final String identifier;
    private final String uri;
    private final String title;
    private final String author;
    private final int plays;
    private final long lastPlayed;

    public TrackReference(String source, String identifier, String uri, String title, String author, int plays, long lastPlayed)
    {
        this.source = source;
        this.identifier = identifier;
        this.uri = uri;
        this.title = title;
        this.author = author;
        this.plays = plays;
        this.lastPlayed = lastPlayed;
    }

    public String getSource()
    {
        return source;
    }

    public String getIdentifier()
    {
        return identifier;
    }

    public String getUri()
    {
        return uri;
    }

    public String getTitle()
    {
        return title;
    }

    public String getAuthor()
    {
        return author;
    }

    public int getPlays()
    {
        return plays;
    }

    public long getLastPlayed()
    {
        return lastPlayed;
    }
}
