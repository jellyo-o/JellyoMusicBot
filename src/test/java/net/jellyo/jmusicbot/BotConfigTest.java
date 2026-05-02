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
package com.jagrosh.jmusicbot;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BotConfigTest
{
    @Test
    public void autoplayMaxDurationAcceptsSecondsAndTimeStrings()
    {
        assertEquals(600000L, BotConfig.parseAutoplayMaxDuration(600));
        assertEquals(600000L, BotConfig.parseAutoplayMaxDuration("10m"));
        assertEquals(510000L, BotConfig.parseAutoplayMaxDuration("8:30"));
        assertEquals(510000L, BotConfig.parseAutoplayMaxDuration("00:08:30"));
    }

    @Test
    public void autoplayMaxDurationDefaultsForMissingOrInvalidValues()
    {
        assertEquals(BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS, BotConfig.parseAutoplayMaxDuration(null));
        assertEquals(BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS, BotConfig.parseAutoplayMaxDuration(""));
        assertEquals(BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS, BotConfig.parseAutoplayMaxDuration("nope"));
        assertEquals(BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS, BotConfig.parseAutoplayMaxDuration(0));
        assertEquals(BotConfig.DEFAULT_AUTOPLAY_MAX_DURATION_MS, BotConfig.parseAutoplayMaxDuration("-5m"));
    }

    @Test
    public void generatedDefaultConfigMatchesRootExample() throws Exception
    {
        String example = Files.readString(Path.of("config.txt.example"), StandardCharsets.UTF_8);
        assertEquals(normalize(example), normalize(BotConfig.loadDefaultConfig()));
    }

    @Test
    public void exampleContainsEveryConfigPathReadByBotConfig()
    {
        Config config = ConfigFactory.parseFile(Path.of("config.txt.example").toFile());
        List<String> requiredPaths = Arrays.asList(
                "token",
                "prefix",
                "altprefix",
                "help",
                "owner",
                "success",
                "warning",
                "error",
                "loading",
                "searching",
                "game",
                "status",
                "stayinchannel",
                "songinstatus",
                "npimages",
                "updatealerts",
                "loglevel",
                "eval",
                "evalengine",
                "dashboard.enabled",
                "dashboard.port",
                "dashboard.bindaddress",
                "dashboard.database",
                "maxtime",
                "autoplaymaxtime",
                "maxytplaylistpages",
                "alonetimeuntilstop",
                "playlistsfolder",
                "aliases",
                "ytpotoken",
                "ytvisitordata",
                "ytroutingplanner",
                "ytipblocks",
                "transforms",
                "skipratio",
                "spotifyid",
                "spotifysecret");

        for(String path : requiredPaths)
            assertTrue("config.txt.example is missing `" + path + "`", config.hasPath(path));
    }

    @Test
    public void exampleContainsEveryConfigurableCommandAlias()
    {
        Config config = ConfigFactory.parseFile(Path.of("config.txt.example").toFile());
        List<String> aliasKeys = Arrays.asList(
                "settings",
                "lyrics",
                "correctlyrics",
                "nowplaying",
                "play",
                "playtop",
                "playlists",
                "queue",
                "history",
                "remove",
                "scsearch",
                "search",
                "seek",
                "shuffle",
                "skip",
                "autoplay",
                "filter",
                "forceremove",
                "forceskip",
                "movetrack",
                "pause",
                "playnext",
                "repeat",
                "skipto",
                "stop",
                "volume",
                "prefix",
                "queuetype",
                "setdj",
                "setskip",
                "settc",
                "setvc",
                "debug",
                "eval",
                "setavatar",
                "setgame",
                "setname",
                "setstatus",
                "shutdown");

        for(String aliasKey : aliasKeys)
            assertTrue("config.txt.example is missing `aliases." + aliasKey + "`", config.hasPath("aliases." + aliasKey));
    }

    private static String normalize(String value)
    {
        return value.replace("\r\n", "\n").trim();
    }
}
