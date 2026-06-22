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
package com.jagrosh.jmusicbot.guessmusic;

import com.jagrosh.jmusicbot.guessmusic.GuessMusicHighlightStore.Highlight;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GuessMusicHighlightStoreTest
{
    @Test
    public void savesAndFindsHighlightByAnyTrackKey() throws Exception
    {
        Path db = Files.createTempFile("guess-music-highlight-test", ".db");
        GuessMusicHighlightStore store = new GuessMusicHighlightStore(db);
        store.init();

        store.save(List.of("song:artist:title", "https://youtube.example/id"), 65_000L, 0.75d, false);

        Optional<Highlight> found = store.find(List.of("missing", "https://youtube.example/id"));
        assertTrue(found.isPresent());
        assertEquals(65_000L, found.get().getPositionMillis());
        assertEquals(0.75d, found.get().getConfidence(), 0.001d);

        store.close();
    }

    @Test
    public void manualHighlightCanOverwriteCachedHighlight() throws Exception
    {
        Path db = Files.createTempFile("guess-music-highlight-test", ".db");
        GuessMusicHighlightStore store = new GuessMusicHighlightStore(db);
        store.init();

        store.save(List.of("song:artist:title"), 65_000L, 0.75d, false);
        store.save(List.of("song:artist:title"), 90_000L, 1d, true);

        Optional<Highlight> found = store.find(List.of("song:artist:title"));
        assertTrue(found.isPresent());
        assertEquals(90_000L, found.get().getPositionMillis());
        assertTrue(found.get().isManual());

        store.close();
    }
}
