# Lyrics preload, auto-show, and accuracy fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task (inline execution chosen — the integration tasks share large files and are safest in one context). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Warm the lyrics cache for upcoming queue songs (LRCLIB-only), optionally auto-post lyrics when a track starts, both togglable per guild, and fix title-only lyrics mismatches by keying on artist + title.

**Architecture:** One shared `LyricsService` on `Bot`. One canonical query builder (`LyricsQuery`) produces the same `"Artist - Title"` key for preload and lookup, so a preloaded entry is a guaranteed cache hit and the artist disambiguates the LRCLIB match. A `LyricsPreloader` warms the next N tracks on a dedicated single-thread executor via a Genius-free `preloadPrimary` path. Two per-guild booleans in `Settings` gate preload and auto-show.

**Tech Stack:** Java 11, Maven, JUnit 4, JDA, lavaplayer, OkHttp, Jackson, org.json (SQLite for the existing `LyricsCache`).

## Global Constraints

- Package/dir quirk: files live under `src/main/java/net/jellyo/jmusicbot/...` but declare `package com.jagrosh.jmusicbot...`. New files follow this: physical `net/jellyo`, logical `com.jagrosh`. Never "fix" the mismatch.
- Blocking work (HTTP, SQLite) must NOT run on `Bot.getThreadpool()` (single scheduler). Use a dedicated executor or `getBlockingThreadpool()`.
- No new HOCON keys (keeps `BotConfigTest` untouched). Tunables via system properties, matching the lyrics package (`-Dlyrics.preloadCount`, default 5).
- Preload and auto-show are **LRCLIB-only** — never invoke the Genius fallback (its `RATE_LIMITER` blocks 10s/call). Genius stays only on explicit manual `/lyrics`.
- Commit messages end with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Never `git add .claude`; stage explicit paths.
- Full `mvn clean test` must be green before the final merge into `master`.

---

### Task 1: Canonical query builder `LyricsQuery`

**Files:**
- Create: `src/main/java/net/jellyo/jmusicbot/lyrics/LyricsQuery.java`
- Test: `src/test/java/net/jellyo/jmusicbot/lyrics/LyricsQueryTest.java`

**Interfaces:**
- Produces: `LyricsQuery.forTitleAndAuthor(String title, String author) -> String`; `LyricsQuery.forTrack(com.sedmelluq.discord.lavaplayer.track.AudioTrack) -> String`; `LyricsQuery.cleanArtist(String) -> String` (package-private, tested).

- [ ] **Step 1: Write the failing test**

```java
package com.jagrosh.jmusicbot.lyrics;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LyricsQueryTest
{
    @Test public void artistAndTitleCombine() {
        assertEquals("NF - Time", LyricsQuery.forTitleAndAuthor("Time", "NF"));
    }
    @Test public void stripsYoutubeTopicSuffix() {
        assertEquals("NF - Time", LyricsQuery.forTitleAndAuthor("Time", "NF - Topic"));
    }
    @Test public void stripsVevoAndOfficial() {
        assertEquals("NF - Time", LyricsQuery.forTitleAndAuthor("Time", "NFVEVO"));
        assertEquals("Adele - Hello", LyricsQuery.forTitleAndAuthor("Hello", "Adele Official"));
    }
    @Test public void blankAuthorFallsBackToTitle() {
        assertEquals("Time", LyricsQuery.forTitleAndAuthor("Time", ""));
        assertEquals("Time", LyricsQuery.forTitleAndAuthor("Time", null));
    }
    @Test public void authorAlreadyInTitleIsNotDuplicated() {
        assertEquals("NF - Time", LyricsQuery.forTitleAndAuthor("NF - Time", "NF"));
    }
    @Test public void blankTitleReturnsEmpty() {
        assertEquals("", LyricsQuery.forTitleAndAuthor("", "NF"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=LyricsQueryTest`
Expected: FAIL (compile error — `LyricsQuery` does not exist).

- [ ] **Step 3: Write minimal implementation**

```java
package com.jagrosh.jmusicbot.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

/** Builds a canonical "Artist - Title" lyrics lookup key from track metadata,
 *  so preload and lookup agree and the artist disambiguates the match. */
public final class LyricsQuery
{
    private LyricsQuery() {}

    public static String forTrack(AudioTrack track)
    {
        if(track == null || track.getInfo() == null)
            return "";
        return forTitleAndAuthor(track.getInfo().title, track.getInfo().author);
    }

    public static String forTitleAndAuthor(String title, String author)
    {
        String t = title == null ? "" : title.trim();
        if(t.isEmpty())
            return "";
        String artist = cleanArtist(author);
        if(artist.isEmpty())
            return t;
        String tl = t.toLowerCase();
        String al = artist.toLowerCase();
        if(tl.contains(al) || tl.contains(" - "))
            return t;
        return artist + " - " + t;
    }

    static String cleanArtist(String author)
    {
        if(author == null)
            return "";
        String a = author.trim();
        // strip YouTube auto-channel noise
        a = a.replaceAll("(?i)\\s*-\\s*topic\\s*$", "");
        a = a.replaceAll("(?i)\\btopic\\b", " ");
        a = a.replaceAll("(?i)\\bvevo\\b", " ");
        a = a.replaceAll("(?i)\\bofficial\\b", " ");
        a = a.replaceAll("\\s{2,}", " ").trim();
        return a;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -q -Dtest=LyricsQueryTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/jellyo/jmusicbot/lyrics/LyricsQuery.java \
        src/test/java/net/jellyo/jmusicbot/lyrics/LyricsQueryTest.java
git commit -F - <<'EOF'
feat(lyrics): add canonical Artist - Title query builder

LyricsQuery.forTrack/forTitleAndAuthor build one key shared by preload and
lookup, cleaning YouTube channel noise (-Topic/VEVO/Official). Foundation for
the artist-aware accuracy fix.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 2: Testable ranking + artist-scoped query in `LrclibLyricsProvider`

**Files:**
- Modify: `src/main/java/net/jellyo/jmusicbot/lyrics/LrclibLyricsProvider.java`
- Test: `src/test/java/net/jellyo/jmusicbot/lyrics/LrclibRankingTest.java`

**Interfaces:**
- Produces: package-private static `LrclibLyricsProvider.selectBest(String scoringQuery, String providerQuery, java.util.List<LyricsResult> candidates) -> Optional<LyricsResult>`.
- Consumes: `LyricsResult` (existing), `InputValidator` (existing).

- [ ] **Step 1: Write the failing test** — proves the NF/"Time" regression is fixed at the ranking layer.

```java
package com.jagrosh.jmusicbot.lyrics;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.*;

public class LrclibRankingTest
{
    private static LyricsResult r(String artist, String title) {
        return new LyricsResult("lrclib", artist+title, "lrclib:"+artist+title,
                "https://lrclib.net/lyrics/1", artist, title, "la la la", Set.of());
    }

    @Test public void artistAwareQueryPrefersCorrectArtist() {
        List<LyricsResult> candidates = Arrays.asList(r("Drake", "From Time"), r("NF", "Time"));
        Optional<LyricsResult> best = LrclibLyricsProvider.selectBest("NF - Time", "NF Time", candidates);
        assertTrue(best.isPresent());
        assertEquals("NF", best.get().artist());
        assertEquals("Time", best.get().title());
    }

    @Test public void bareTitleStillReturnsExactTitleMatch() {
        List<LyricsResult> candidates = Arrays.asList(r("Drake", "From Time"), r("NF", "Time"));
        Optional<LyricsResult> best = LrclibLyricsProvider.selectBest("Time", "Time", candidates);
        assertTrue(best.isPresent());
        assertEquals("Time", best.get().title()); // exact-title (0.85) beats "From Time" fuzzy
    }

    @Test public void noCandidatesReturnsEmpty() {
        assertFalse(LrclibLyricsProvider.selectBest("x", "x", Arrays.asList()).isPresent());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=LrclibRankingTest`
Expected: FAIL (compile error — `selectBest` not defined).

- [ ] **Step 3: Refactor `score`/`tokenScore` to `static`, extract `selectBest`, and issue an artist-scoped LRCLIB query.**

In `LrclibLyricsProvider.java`:

(a) Change `private double score(...)` → `static double score(...)` and `private double tokenScore(...)` → `static double tokenScore(...)` (no other change to their bodies).

(b) Add the extracted selector (place after `score`):

```java
    static Optional<LyricsResult> selectBest(String scoringQuery, String providerQuery, java.util.List<LyricsResult> candidates)
    {
        LyricsResult best = null;
        double bestScore = -1d;
        for(LyricsResult candidate : candidates)
        {
            if(candidate == null || !candidate.hasLyrics())
                continue;
            double s = Math.max(score(scoringQuery, candidate), score(providerQuery, candidate));
            if(s > bestScore)
            {
                bestScore = s;
                best = candidate;
            }
        }
        if(best != null && bestScore >= 0.12d)
            return Optional.of(best);
        return Optional.empty();
    }
```

(c) Rewrite `searchOnce` to build a candidate list then delegate to `selectBest`:

```java
    private Optional<LyricsResult> searchOnce(String providerQuery, String scoringQuery) throws IOException
    {
        HttpUrl url = HttpUrl.parse(BASE + "/api/search").newBuilder()
                .addQueryParameter("q", providerQuery)
                .build();
        return runSearch(url, providerQuery, scoringQuery);
    }

    private Optional<LyricsResult> runSearch(HttpUrl url, String providerQuery, String scoringQuery) throws IOException
    {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "JellyoMusicBot lyrics lookup")
                .build();
        try(Response response = CLIENT.newCall(request).execute())
        {
            if(!response.isSuccessful() || response.body() == null)
                return Optional.empty();
            JsonNode root = MAPPER.readTree(response.body().byteStream());
            if(!root.isArray())
                return Optional.empty();
            java.util.List<LyricsResult> candidates = new java.util.ArrayList<>();
            for(JsonNode item : root)
            {
                LyricsResult candidate = toResult(item);
                if(candidate != null)
                    candidates.add(candidate);
            }
            return selectBest(scoringQuery, providerQuery, candidates);
        }
    }
```

(d) Add an artist-scoped attempt in `search` (before the free-text loop) so the correct track surfaces when we know the artist:

```java
    @Override
    public Optional<LyricsResult> search(String query, boolean allowDifferentArtistFallback) throws IOException
    {
        String[] artistTitle = InputValidator.splitArtistTitle(query);
        if(artistTitle != null)
        {
            HttpUrl url = HttpUrl.parse(BASE + "/api/search").newBuilder()
                    .addQueryParameter("track_name", artistTitle[1])
                    .addQueryParameter("artist_name", artistTitle[0])
                    .build();
            Optional<LyricsResult> scoped = runSearch(url, query, query);
            if(scoped.isPresent())
                return scoped;
        }
        for(String providerQuery : InputValidator.providerQueries(query))
        {
            Optional<LyricsResult> result = searchOnce(providerQuery, query);
            if(result.isPresent())
                return result;
        }
        return Optional.empty();
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -q -Dtest=LrclibRankingTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/jellyo/jmusicbot/lyrics/LrclibLyricsProvider.java \
        src/test/java/net/jellyo/jmusicbot/lyrics/LrclibRankingTest.java
git commit -F - <<'EOF'
fix(lyrics): make LRCLIB ranking artist-aware and testable

Extract selectBest() and add an artist-scoped track_name/artist_name query so
"NF - Time" resolves to NF's "Time" instead of "Drake - From Time". Regression
test covers artist-aware selection and bare-title exact match.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 3: `LyricsService.preloadPrimary` (LRCLIB-only warm)

**Files:**
- Modify: `src/main/java/net/jellyo/jmusicbot/lyrics/LyricsService.java`
- Test: `src/test/java/net/jellyo/jmusicbot/lyrics/LyricsServicePreloadTest.java`

**Interfaces:**
- Produces: `LyricsService.preloadPrimary(String rawQuery) -> Optional<LyricsCache.CachedLyrics>` (checks cache, then LRCLIB only; never the Genius fallback).
- Consumes: existing package-private ctor `LyricsService(LyricsCache, LyricsProvider, DirectLyricsProvider)`.

- [ ] **Step 1: Write the failing test** (uses in-memory fakes via the DI ctor and a `:memory:`-style temp DB).

```java
package com.jagrosh.jmusicbot.lyrics;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.*;

public class LyricsServicePreloadTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static class CountingPrimary implements LyricsProvider {
        int calls = 0;
        Optional<LyricsResult> next = Optional.empty();
        public Optional<LyricsResult> search(String q, boolean f) { calls++; return next; }
    }
    static class ThrowingFallback implements DirectLyricsProvider {
        public Optional<LyricsResult> search(String q, boolean f) { throw new AssertionError("Genius must not be used during preload"); }
        public Optional<LyricsResult> fetchByUrl(String url) { throw new AssertionError("Genius must not be used during preload"); }
    }

    private LyricsService service(CountingPrimary primary) throws Exception {
        LyricsCache cache = new LyricsCache(tmp.newFile("lyrics.db").toPath());
        return new LyricsService(cache, primary, new ThrowingFallback());
    }

    @Test public void preloadUsesPrimaryAndCaches() throws Exception {
        CountingPrimary primary = new CountingPrimary();
        primary.next = Optional.of(new LyricsResult("lrclib","1","lrclib:1",
                "https://lrclib.net/lyrics/1","NF","Time","the lyrics", Set.of()));
        LyricsService svc = service(primary);

        Optional<LyricsCache.CachedLyrics> first = svc.preloadPrimary("NF - Time");
        assertTrue(first.isPresent());
        assertEquals(1, primary.calls);

        // second identical call is served from cache — no additional provider hit
        Optional<LyricsCache.CachedLyrics> second = svc.preloadPrimary("NF - Time");
        assertTrue(second.isPresent());
        assertEquals(1, primary.calls);
    }

    @Test public void preloadNeverInvokesFallbackOnMiss() throws Exception {
        CountingPrimary primary = new CountingPrimary(); // returns empty
        LyricsService svc = service(primary);
        Optional<LyricsCache.CachedLyrics> result = svc.preloadPrimary("Unknown Song");
        assertFalse(result.isPresent());
        assertEquals(1, primary.calls); // and ThrowingFallback was never called (no AssertionError)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=LyricsServicePreloadTest`
Expected: FAIL (compile error — `preloadPrimary` not defined).

- [ ] **Step 3: Add `preloadPrimary`** (place after `fetchAndCache`):

```java
    /** Warm the cache using the primary (LRCLIB) provider only — never the
     *  rate-limited Genius fallback. Used by preload and auto-show. */
    public Optional<LyricsCache.CachedLyrics> preloadPrimary(String rawQuery) throws IOException
    {
        String sanitized = InputValidator.sanitizeQuery(rawQuery);
        if(sanitized == null)
            return Optional.empty();
        try
        {
            Optional<LyricsCache.CachedLyrics> cached = cache.findBestMatch(sanitized);
            if(cached.isPresent())
                return cached;
        }
        catch(SQLException ignored)
        {
        }
        try
        {
            return fetchFromProvider(primaryProvider, sanitized, false);
        }
        catch(IOException ignored)
        {
            return Optional.empty();
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -q -Dtest=LyricsServicePreloadTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/jellyo/jmusicbot/lyrics/LyricsService.java \
        src/test/java/net/jellyo/jmusicbot/lyrics/LyricsServicePreloadTest.java
git commit -F - <<'EOF'
feat(lyrics): add preloadPrimary (cache -> LRCLIB, no Genius)

Rate-limit-safe warm path shared by preload and auto-show; verified to hit the
cache on repeat and never touch the Genius fallback.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 4: `LyricsPreloader` (dedupe + limit + async warm)

**Files:**
- Create: `src/main/java/net/jellyo/jmusicbot/lyrics/LyricsPreloader.java`
- Test: `src/test/java/net/jellyo/jmusicbot/lyrics/LyricsPreloaderTest.java`

**Interfaces:**
- Produces: `new LyricsPreloader(java.util.concurrent.Executor, LyricsPreloader.Warmer, int maxRemembered)`; `preloadKeys(java.util.List<String> keys) -> void`; nested `interface Warmer { void warm(String query); }`.
- In production, `Warmer` = `svc::preloadPrimary` adapter (wired in Task 9); executor = a dedicated single-thread pool.

- [ ] **Step 1: Write the failing test** (synchronous executor + recording warmer isolates the dedupe/limit logic).

```java
package com.jagrosh.jmusicbot.lyrics;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class LyricsPreloaderTest
{
    private final List<String> warmed = new ArrayList<>();
    private LyricsPreloader newPreloader() {
        return new LyricsPreloader(Runnable::run, warmed::add, 100);
    }

    @Test public void warmsEachKeyOnce() {
        LyricsPreloader p = newPreloader();
        p.preloadKeys(Arrays.asList("NF - Time", "Adele - Hello"));
        assertEquals(Arrays.asList("NF - Time", "Adele - Hello"), warmed);
    }

    @Test public void skipsAlreadyAttemptedKeysAcrossOverlappingWindows() {
        LyricsPreloader p = newPreloader();
        p.preloadKeys(Arrays.asList("a", "b", "c"));
        p.preloadKeys(Arrays.asList("b", "c", "d")); // window advanced by one
        assertEquals(Arrays.asList("a", "b", "c", "d"), warmed); // only "d" is new
    }

    @Test public void ignoresBlankKeys() {
        LyricsPreloader p = newPreloader();
        p.preloadKeys(Arrays.asList("", "  ", "x"));
        assertEquals(Arrays.asList("x"), warmed);
    }

    @Test public void evictsOldestBeyondCapacitySoTheyCanReload() {
        LyricsPreloader p = new LyricsPreloader(Runnable::run, warmed::add, 2);
        p.preloadKeys(Arrays.asList("a", "b"));
        p.preloadKeys(Arrays.asList("c"));       // evicts "a"
        p.preloadKeys(Arrays.asList("a"));       // "a" no longer remembered -> warmed again
        assertEquals(Arrays.asList("a", "b", "c", "a"), warmed);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=LyricsPreloaderTest`
Expected: FAIL (compile error — `LyricsPreloader` not defined).

- [ ] **Step 3: Write minimal implementation**

```java
package com.jagrosh.jmusicbot.lyrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/** De-duplicates and asynchronously warms lyrics for a set of query keys.
 *  As the queue advances one track at a time the next-N window overlaps
 *  heavily; the bounded LRU of attempted keys prevents redundant fetches. */
public class LyricsPreloader
{
    public interface Warmer { void warm(String query); }

    private final Executor executor;
    private final Warmer warmer;
    private final Set<String> attempted;

    public LyricsPreloader(Executor executor, Warmer warmer, int maxRemembered)
    {
        this.executor = executor;
        this.warmer = warmer;
        this.attempted = java.util.Collections.newSetFromMap(new LinkedHashMap<String,Boolean>(16, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,Boolean> eldest) {
                return size() > Math.max(1, maxRemembered);
            }
        });
    }

    public void preloadKeys(List<String> keys)
    {
        if(keys == null)
            return;
        for(String key : keys)
        {
            if(key == null || key.trim().isEmpty())
                continue;
            final String q = key.trim();
            boolean fresh;
            synchronized(attempted)
            {
                fresh = attempted.add(q);
            }
            if(fresh)
                executor.execute(() -> warmer.warm(q));
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -q -Dtest=LyricsPreloaderTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/jellyo/jmusicbot/lyrics/LyricsPreloader.java \
        src/test/java/net/jellyo/jmusicbot/lyrics/LyricsPreloaderTest.java
git commit -F - <<'EOF'
feat(lyrics): add LyricsPreloader with LRU dedupe + async warm

Bounded attempted-key set avoids re-fetching the overlapping next-N window as
the queue advances; warms via an injected Warmer on an injected executor.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 5: `CommandParsers.parseToggle`

**Files:**
- Modify: `src/main/java/net/jellyo/jmusicbot/commands/CommandParsers.java`
- Test: `src/test/java/net/jellyo/jmusicbot/commands/CommandParsersTest.java` (append)

**Interfaces:**
- Produces: `CommandParsers.parseToggle(String args, boolean current) -> boolean`.

- [ ] **Step 1: Write the failing test** (append these methods to the existing `CommandParsersTest`):

```java
    @Test public void parseToggleEmptyFlipsCurrent() {
        org.junit.Assert.assertTrue(CommandParsers.parseToggle("", false));
        org.junit.Assert.assertFalse(CommandParsers.parseToggle("  ", true));
    }
    @Test public void parseToggleOnOff() {
        org.junit.Assert.assertTrue(CommandParsers.parseToggle("on", false));
        org.junit.Assert.assertTrue(CommandParsers.parseToggle("enable", false));
        org.junit.Assert.assertFalse(CommandParsers.parseToggle("off", true));
        org.junit.Assert.assertFalse(CommandParsers.parseToggle("disable", true));
    }
    @Test(expected = IllegalArgumentException.class)
    public void parseToggleInvalidThrows() {
        CommandParsers.parseToggle("maybe", false);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=CommandParsersTest`
Expected: FAIL (compile error — `parseToggle` not defined).

- [ ] **Step 3: Add `parseToggle`** (before the private `normalize`):

```java
    public static boolean parseToggle(String args, boolean current)
    {
        String normalized = normalize(args);
        if(normalized.isEmpty())
            return !current;
        if(normalized.equals("on") || normalized.equals("true") || normalized.equals("enable")
                || normalized.equals("enabled") || normalized.equals("yes"))
            return true;
        if(normalized.equals("off") || normalized.equals("false") || normalized.equals("disable")
                || normalized.equals("disabled") || normalized.equals("no"))
            return false;
        throw new IllegalArgumentException("Valid options are `on` or `off`");
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -q -Dtest=CommandParsersTest`
Expected: PASS (17 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/jellyo/jmusicbot/commands/CommandParsers.java \
        src/test/java/net/jellyo/jmusicbot/commands/CommandParsersTest.java
git commit -F - <<'EOF'
feat(commands): add parseToggle(on|off|empty=flip)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 6: Per-guild `autoPreloadLyrics` / `autoShowLyrics` settings

**Files:**
- Modify: `src/main/java/net/jellyo/jmusicbot/settings/Settings.java`
- Modify: `src/main/java/net/jellyo/jmusicbot/settings/SettingsManager.java`
- Test: `src/test/java/net/jellyo/jmusicbot/settings/SettingsTest.java`

**Interfaces:**
- Produces: `Settings.isAutoPreloadLyrics() -> boolean` (default true), `Settings.setAutoPreloadLyrics(boolean)`, `Settings.isAutoShowLyrics() -> boolean` (default false), `Settings.setAutoShowLyrics(boolean)`. Both constructors gain trailing params `boolean autoPreloadLyrics, boolean autoShowLyrics`.

- [ ] **Step 1: Write the failing test**

```java
package com.jagrosh.jmusicbot.settings;

import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsTest
{
    private Settings settings(boolean preload, boolean show) {
        return new Settings(null, 0L, 0L, 0L, 100, RepeatMode.OFF, AutoplayMode.OFF, null, -1, QueueType.FAIR, preload, show);
    }

    @Test public void defaultsPreloadOnAutoShowOff() {
        Settings s = settings(true, false);
        assertTrue(s.isAutoPreloadLyrics());
        assertFalse(s.isAutoShowLyrics());
    }

    @Test public void constructorCarriesFlags() {
        Settings s = settings(false, true);
        assertFalse(s.isAutoPreloadLyrics());
        assertTrue(s.isAutoShowLyrics());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -q -Dtest=SettingsTest`
Expected: FAIL (compile error — constructor arity / methods missing).

- [ ] **Step 3: Implement.**

In `Settings.java`:
- Add fields after `skipRatio`: `private boolean autoPreloadLyrics; private boolean autoShowLyrics;`
- Add `boolean autoPreloadLyrics, boolean autoShowLyrics` as the last two params of BOTH constructors, and in each body add `this.autoPreloadLyrics = autoPreloadLyrics; this.autoShowLyrics = autoShowLyrics;`
- Add getters/setters:

```java
    public boolean isAutoPreloadLyrics() { return autoPreloadLyrics; }
    public boolean isAutoShowLyrics() { return autoShowLyrics; }

    public void setAutoPreloadLyrics(boolean value) { this.autoPreloadLyrics = value; this.manager.writeSettings(); }
    public void setAutoShowLyrics(boolean value) { this.autoShowLyrics = value; this.manager.writeSettings(); }
```

In `SettingsManager.java`:
- In the load block (the `new Settings(this, ...)` call, string-arg ctor) append the two args:
  `,\n                        o.has("auto_preload_lyrics") ? o.getBoolean("auto_preload_lyrics") : true,\n                        o.has("auto_show_lyrics") ? o.getBoolean("auto_show_lyrics") : false`
- In `createDefaultSettings()` append `, true, false` to the `new Settings(...)` args.
- In `writeSettings()` (persist non-defaults only), before `obj.put(...)`:

```java
            if(!s.isAutoPreloadLyrics())
                o.put("auto_preload_lyrics", false);
            if(s.isAutoShowLyrics())
                o.put("auto_show_lyrics", true);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -q -Dtest=SettingsTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Verify no other `new Settings(` callers broke, then commit**

Run: `grep -rn "new Settings(" src/main/java src/test/java`
Expected: only `SettingsManager.java` constructs `Settings` (plus the new test). If any other caller exists, update it with the two new trailing args.

```bash
git add src/main/java/net/jellyo/jmusicbot/settings/Settings.java \
        src/main/java/net/jellyo/jmusicbot/settings/SettingsManager.java \
        src/test/java/net/jellyo/jmusicbot/settings/SettingsTest.java
git commit -F - <<'EOF'
feat(settings): per-guild autoPreloadLyrics (default on) / autoShowLyrics (off)

Persisted in serversettings.json (non-default values only), matching the
existing setting pattern.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 7: Toggle commands `/preloadlyrics` and `/autolyrics`

**Files:**
- Create: `src/main/java/net/jellyo/jmusicbot/commands/admin/PreloadLyricsCmd.java`
- Create: `src/main/java/net/jellyo/jmusicbot/commands/admin/AutoLyricsCmd.java`

**Interfaces:**
- Consumes: `Bot.getSettingsManager()`, `Settings.setAutoPreloadLyrics/isAutoPreloadLyrics`, `CommandParsers.parseToggle`.
- Produces: `PreloadLyricsCmd(Bot)`, `AutoLyricsCmd(Bot)` — both `AdminCommand implements UnifiedCommand`, names `preloadlyrics` / `autolyrics`.

- [ ] **Step 1: Create `PreloadLyricsCmd.java`** (mirror `QueueTypeCmd`):

```java
package com.jagrosh.jmusicbot.commands.admin;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.AdminCommand;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.CommandParsers;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.settings.Settings;

public class PreloadLyricsCmd extends AdminCommand implements UnifiedCommand
{
    private final Bot bot;

    public PreloadLyricsCmd(Bot bot)
    {
        super();
        this.bot = bot;
        this.name = "preloadlyrics";
        this.help = "toggles preloading lyrics for upcoming queued songs";
        this.arguments = "[on|off]";
        this.aliases = bot.getConfig().getAliases(this.name);
    }

    @Override
    protected void execute(CommandEvent event)
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext event)
    {
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        boolean value;
        try
        {
            value = CommandParsers.parseToggle(event.getArgs(), settings.isAutoPreloadLyrics());
        }
        catch(IllegalArgumentException ex)
        {
            event.replyError("Valid options are `on` or `off` (or leave empty to toggle).");
            return;
        }
        settings.setAutoPreloadLyrics(value);
        event.replySuccess("Lyrics preloading for upcoming songs is now `" + (value ? "on" : "off") + "`.");
    }
}
```

- [ ] **Step 2: Create `AutoLyricsCmd.java`** (same shape, different name/help/setting):

```java
package com.jagrosh.jmusicbot.commands.admin;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.AdminCommand;
import com.jagrosh.jmusicbot.commands.CommandContext;
import com.jagrosh.jmusicbot.commands.CommandParsers;
import com.jagrosh.jmusicbot.commands.MessageCommandContext;
import com.jagrosh.jmusicbot.commands.UnifiedCommand;
import com.jagrosh.jmusicbot.settings.Settings;

public class AutoLyricsCmd extends AdminCommand implements UnifiedCommand
{
    private final Bot bot;

    public AutoLyricsCmd(Bot bot)
    {
        super();
        this.bot = bot;
        this.name = "autolyrics";
        this.help = "toggles automatically posting lyrics when a song starts";
        this.arguments = "[on|off]";
        this.aliases = bot.getConfig().getAliases(this.name);
    }

    @Override
    protected void execute(CommandEvent event)
    {
        doCommand(new MessageCommandContext(event));
    }

    @Override
    public void doCommand(CommandContext event)
    {
        Settings settings = bot.getSettingsManager().getSettings(event.getGuild());
        boolean value;
        try
        {
            value = CommandParsers.parseToggle(event.getArgs(), settings.isAutoShowLyrics());
        }
        catch(IllegalArgumentException ex)
        {
            event.replyError("Valid options are `on` or `off` (or leave empty to toggle).");
            return;
        }
        settings.setAutoShowLyrics(value);
        event.replySuccess("Auto-posting lyrics on song start is now `" + (value ? "on" : "off") + "`.");
    }
}
```

- [ ] **Step 3: Compile check**

Run: `mvn -q -o test-compile` (or `mvn -q test-compile`)
Expected: BUILD SUCCESS (classes compile; not yet registered).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/jellyo/jmusicbot/commands/admin/PreloadLyricsCmd.java \
        src/main/java/net/jellyo/jmusicbot/commands/admin/AutoLyricsCmd.java
git commit -F - <<'EOF'
feat(commands): add /preloadlyrics and /autolyrics admin toggles

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 8: Shared `LyricsService` on `Bot` + repoint call sites

**Files:**
- Modify: `src/main/java/net/jellyo/jmusicbot/Bot.java` (field, construction, getter, shutdown)
- Modify: `src/main/java/net/jellyo/jmusicbot/commands/music/LyricsCmd.java` (use `bot.getLyricsService()` + `LyricsQuery.forTrack`)
- Modify: `src/main/java/net/jellyo/jmusicbot/SlashCommandListener.java` (`getLyricsService()` → `bot.getLyricsService()`; current-track query via `LyricsQuery.forTrack`)
- Modify: `src/main/java/net/jellyo/jmusicbot/audio/NowplayingHandler.java` (`getLyricsService()` → `bot.getLyricsService()`; panel-button query via `LyricsQuery.forTrack`)

**Interfaces:**
- Produces: `Bot.getLyricsService() -> com.jagrosh.jmusicbot.lyrics.LyricsService` (may be `null` if init failed).
- Consumes: `LyricsQuery.forTrack` (Task 1).

- [ ] **Step 1: Add the shared service to `Bot`.**
  - Field: `private final LyricsService lyricsService;` (import `com.jagrosh.jmusicbot.lyrics.LyricsService`).
  - In the constructor, near the other store construction, build it guarded (do not abort bot startup on failure):
    ```java
    LyricsService ls = null;
    try { ls = new LyricsService(java.nio.file.Path.of("lyrics-cache.db")); }
    catch(Exception ex) { LOG.warn("Failed to initialize lyrics service: " + ex); }
    this.lyricsService = ls;
    ```
    (Use the existing `Bot` logger name if present; otherwise reuse the pattern already in the file.)
  - Getter: `public LyricsService getLyricsService() { return lyricsService; }`
  - No explicit close needed (LyricsCache holds a SQLite connection; existing services are torn down via `shutdown()` — if `LyricsCache` exposes a close, call it in `shutdown()`; otherwise leave as-is since the process is exiting).

- [ ] **Step 2: Repoint `LyricsCmd`.**
  - Delete the `private static volatile LyricsService service;` + `initService()` and use `bot.getLyricsService()` (the `MusicCommand` base exposes `bot`). Guard null: if `bot.getLyricsService() == null`, `event.replyError("Lyrics are unavailable right now.")`.
  - Where the current-track title is used (line ~73), replace `getPlayingTrack().getInfo().title` with `LyricsQuery.forTrack(getPlayingTrack())`.

- [ ] **Step 3: Repoint `SlashCommandListener`.**
  - `getLyricsService()` (line ~1981) returns `bot.getLyricsService()`; remove the private lazy field/init.
  - In `handleLyrics` (line ~1900), when falling back to the current track, build the query via `LyricsQuery.forTrack(sendingHandler.getPlayer().getPlayingTrack())`.

- [ ] **Step 4: Repoint `NowplayingHandler`.**
  - `getLyricsService()` (line ~1158) returns `bot.getLyricsService()`; remove the private lazy field/init (`lyricsService`).
  - In `handleLyrics` (line ~1112), build the query via `LyricsQuery.forTrack(handler.getPlayer().getPlayingTrack())`.

- [ ] **Step 5: Build + full suite**

Run: `mvn -q clean test`
Expected: BUILD SUCCESS, 211+ tests pass. (Manual note: existing lyrics behavior now flows through one service and artist-aware keys.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/net/jellyo/jmusicbot/Bot.java \
        src/main/java/net/jellyo/jmusicbot/commands/music/LyricsCmd.java \
        src/main/java/net/jellyo/jmusicbot/SlashCommandListener.java \
        src/main/java/net/jellyo/jmusicbot/audio/NowplayingHandler.java
git commit -F - <<'EOF'
refactor(lyrics): one shared LyricsService on Bot; artist-aware /lyrics keys

Collapse the three separate LyricsService instances into bot.getLyricsService()
and build the current-track lookup key with LyricsQuery.forTrack so /lyrics uses
artist + title (fixes bare-title mismatches) and shares the preload cache.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 9: Preload trigger in `AudioHandler.onTrackStart`

**Files:**
- Modify: `src/main/java/net/jellyo/jmusicbot/Bot.java` (own a `LyricsPreloader` + dedicated executor)
- Modify: `src/main/java/net/jellyo/jmusicbot/audio/AudioHandler.java` (trigger after the now-playing update)

**Interfaces:**
- Consumes: `Bot.getLyricsService()`, `LyricsPreloader` (Task 4), `LyricsQuery.forTrack` (Task 1), `Settings.isAutoPreloadLyrics()` (Task 6).
- Produces: `Bot.getLyricsPreloader() -> LyricsPreloader` (may be `null` if lyrics unavailable).

- [ ] **Step 1: Build the preloader in `Bot`.**
  - Add a dedicated single-thread daemon executor:
    ```java
    private final java.util.concurrent.ExecutorService lyricsPreloadPool =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jmusicbot-lyrics-preload"); t.setDaemon(true); return t; });
    private final LyricsPreloader lyricsPreloader;
    ```
  - After `this.lyricsService` is set:
    ```java
    this.lyricsPreloader = (lyricsService == null) ? null
        : new LyricsPreloader(lyricsPreloadPool, q -> { try { lyricsService.preloadPrimary(q); } catch(Exception ignored) {} }, 256);
    ```
  - Getter `public LyricsPreloader getLyricsPreloader() { return lyricsPreloader; }`
  - In `shutdown()` add `lyricsPreloadPool.shutdownNow();`

- [ ] **Step 2: Trigger the preload at the end of `onTrackStart`** (after `manager.getBot().getNowplayingHandler().onTrackUpdate(guildId, track);`, line ~916):

```java
        maybePreloadUpcomingLyrics();
```

Add the helper method to `AudioHandler`:

```java
    private void maybePreloadUpcomingLyrics()
    {
        Bot bot = manager.getBot();
        LyricsPreloader preloader = bot.getLyricsPreloader();
        if(preloader == null)
            return;
        if(!bot.getSettingsManager().getSettings(guildId).isAutoPreloadLyrics())
            return;
        int limit = Integer.getInteger("lyrics.preloadCount", 5);
        java.util.List<QueuedTrack> upcoming;
        try { upcoming = new java.util.ArrayList<>(queue.getList()); }
        catch(Exception ex) { return; }
        java.util.List<String> keys = new java.util.ArrayList<>();
        for(int i = 0; i < upcoming.size() && keys.size() < limit; i++)
        {
            AudioTrack t = upcoming.get(i).getTrack();
            if(t == null || t.getInfo() == null || t.getInfo().isStream)
                continue;
            String key = LyricsQuery.forTrack(t);
            if(!key.isEmpty())
                keys.add(key);
        }
        preloader.preloadKeys(keys);
    }
```

(Imports: `com.jagrosh.jmusicbot.lyrics.LyricsPreloader`, `com.jagrosh.jmusicbot.lyrics.LyricsQuery`. `AudioTrack` and `QueuedTrack` are already imported.)

- [ ] **Step 3: Build + full suite**

Run: `mvn -q clean test`
Expected: BUILD SUCCESS, tests green.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/jellyo/jmusicbot/Bot.java \
        src/main/java/net/jellyo/jmusicbot/audio/AudioHandler.java
git commit -F - <<'EOF'
feat(lyrics): preload next N queued songs on track start (LRCLIB-only)

Owns a dedicated single-thread preload pool on Bot; on track start warms up to
lyrics.preloadCount (default 5) upcoming non-stream tracks when the guild has
autoPreloadLyrics enabled.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 10: Auto-show lyrics on track start (`NowplayingHandler`)

**Files:**
- Modify: `src/main/java/net/jellyo/jmusicbot/audio/NowplayingHandler.java`

**Interfaces:**
- Consumes: `Settings.isAutoShowLyrics()`, `getDefaultPanelChannel(Guild, AudioTrack)` (existing private), `bot.getLyricsService().preloadPrimary` (LRCLIB-only), the existing lyrics-embed builder in `handleLyrics`, `LyricsQuery.forTrack`.

- [ ] **Step 1: Post lyrics on track update when enabled.** In `onTrackUpdate(long guildId, AudioTrack track)` (line ~225), after the panel handling, add:

```java
        if(track != null)
            maybeAutoShowLyrics(guild, track);
```

- [ ] **Step 2: Implement `maybeAutoShowLyrics`** (runs off the JDA/playback thread on the shared blocking pool; LRCLIB-only; silent on miss; reuses the embed built like `handleLyrics`):

```java
    private void maybeAutoShowLyrics(Guild guild, AudioTrack track)
    {
        if(!bot.getSettingsManager().getSettings(guild.getIdLong()).isAutoShowLyrics())
            return;
        LyricsService service = bot.getLyricsService();
        if(service == null)
            return;
        if(track.getInfo() != null && track.getInfo().isStream)
            return;
        TextChannel channel = getDefaultPanelChannel(guild, track);
        if(channel == null || !guild.getSelfMember().hasPermission(channel,
                net.dv8tion.jda.api.Permission.MESSAGE_SEND, net.dv8tion.jda.api.Permission.MESSAGE_EMBED_LINKS))
            return;
        final String query = LyricsQuery.forTrack(track);
        if(query.isEmpty())
            return;
        final long channelId = channel.getIdLong();
        bot.getBlockingThreadpool().submit(() -> {
            try
            {
                Optional<LyricsCache.CachedLyrics> found = service.preloadPrimary(query); // cache -> LRCLIB, no Genius
                if(found.isEmpty())
                    return;
                LyricsCache.CachedLyrics lyrics = found.get();
                String content = lyrics.lyrics();
                if(content == null || content.isBlank() || content.length() > 3900)
                    return; // keep auto-post to a single tidy embed
                TextChannel target = guild.getTextChannelById(channelId);
                if(target == null)
                    return;
                String titleLine = (lyrics.artist() == null || lyrics.artist().isBlank() ? "" : lyrics.artist() + " - ") + lyrics.title();
                net.dv8tion.jda.api.EmbedBuilder eb = new net.dv8tion.jda.api.EmbedBuilder()
                        .setColor(guild.getSelfMember().getColor())
                        .setTitle(titleLine, lyrics.sourceUrl())
                        .setDescription(content);
                target.sendMessageEmbeds(eb.build()).queue(null, t -> {});
            }
            catch(Exception ignored) {}
        });
    }
```

(Imports needed at top of file if absent: `com.jagrosh.jmusicbot.lyrics.LyricsService`, `com.jagrosh.jmusicbot.lyrics.LyricsCache`, `com.jagrosh.jmusicbot.lyrics.LyricsQuery`, `java.util.Optional`, `net.dv8tion.jda.api.entities.channel.concrete.TextChannel`, `net.dv8tion.jda.api.EmbedBuilder`.)

- [ ] **Step 3: Build + full suite**

Run: `mvn -q clean test`
Expected: BUILD SUCCESS, tests green.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/jellyo/jmusicbot/audio/NowplayingHandler.java
git commit -F - <<'EOF'
feat(lyrics): opt-in auto-post lyrics when a song starts (LRCLIB-only)

When a guild enables autoShowLyrics, post a single lyrics embed to the
now-playing channel on track start, using the cache/LRCLIB path (never Genius);
silent when no lyrics are found or perms are missing.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 11: Register commands, settings embed, help

**Files:**
- Modify: `src/main/java/net/jellyo/jmusicbot/JMusicBot.java` (prefix `addCommands` list + admin help section)
- Modify: `src/main/java/net/jellyo/jmusicbot/SlashCommandListener.java` (fields + ctor + `buildSlashCommands` + switch cases)
- Modify: settings display — `commands/admin/SettingsCmd.java` (prefix embed) and `SlashCommandListener.handleSettings` (slash embed)

**Interfaces:**
- Consumes: `PreloadLyricsCmd`, `AutoLyricsCmd` (Task 7), `handleSharedAdminCommand`, `getOptionalStringArg` (existing).

- [ ] **Step 1: Register prefix commands.** In `JMusicBot.createCommandClient`'s `.addCommands(...)` add `new PreloadLyricsCmd(bot), new AutoLyricsCmd(bot),` (admin package is wildcard-imported). Add both to the Admin help array in `sendPrefixHelp`.

- [ ] **Step 2: Register slash commands.** In `SlashCommandListener`:
  - Fields: `private final PreloadLyricsCmd preloadLyricsCmd; private final AutoLyricsCmd autoLyricsCmd;`
  - Ctor: `this.preloadLyricsCmd = new PreloadLyricsCmd(bot); this.autoLyricsCmd = new AutoLyricsCmd(bot);`
  - `buildSlashCommands()` add (Manage-Server gated, like `queuetype`):
    ```java
    commands.add(slashCommand("preloadlyrics", "Toggle preloading lyrics for upcoming songs")
            .addOptions(new OptionData(OptionType.STRING, "state", "on or off", false)
                    .addChoice("on", "on").addChoice("off", "off"))
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
    commands.add(slashCommand("autolyrics", "Toggle auto-posting lyrics when a song starts")
            .addOptions(new OptionData(OptionType.STRING, "state", "on or off", false)
                    .addChoice("on", "on").addChoice("off", "off"))
            .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER)));
    ```
  - Switch cases in `onSlashCommandInteraction`:
    ```java
    case "preloadlyrics": handleSharedAdminCommand(event, preloadLyricsCmd, getOptionalStringArg(event, "state")); break;
    case "autolyrics": handleSharedAdminCommand(event, autoLyricsCmd, getOptionalStringArg(event, "state")); break;
    ```

- [ ] **Step 3: Surface state in the settings embed.** In `SettingsCmd` (prefix) and `handleSettings` (slash), add two lines, e.g. `"Preload lyrics: " + (s.isAutoPreloadLyrics() ? "On" : "Off")` and `"Auto lyrics: " + (s.isAutoShowLyrics() ? "On" : "Off")`, following the existing field-append style in each.

- [ ] **Step 4: Build + full suite**

Run: `mvn -q clean test`
Expected: BUILD SUCCESS, tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/jellyo/jmusicbot/JMusicBot.java \
        src/main/java/net/jellyo/jmusicbot/SlashCommandListener.java \
        src/main/java/net/jellyo/jmusicbot/commands/admin/SettingsCmd.java
git commit -F - <<'EOF'
feat(commands): register /preloadlyrics + /autolyrics (prefix+slash) and show in settings

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 12: Final verification

**Files:** none (verification only).

- [ ] **Step 1: Full clean build + tests**

Run: `mvn -q clean test`
Expected: BUILD SUCCESS; test count ≥ 211 + new (LyricsQuery 6, LrclibRanking 3, LyricsServicePreload 2, LyricsPreloader 4, CommandParsers +3, Settings 2), 0 failures/errors.

- [ ] **Step 2: Package the fat jar (smoke)**

Run: `mvn -q clean package -DskipTests`
Expected: BUILD SUCCESS; `target/JMusicBot-<version>-All.jar` produced.

- [ ] **Step 3: Manual/behavioural verification (use superpowers:verification-before-completion + /verify where possible).**
  - Confirm `.claude/` is ignored: `git status --porcelain` shows no `.claude` entries; `git add .claude` is refused.
  - Sanity-check the accuracy fix path with a tiny harness or by tracing `LyricsQuery.forTitleAndAuthor("Time","NF")` → `"NF - Time"` and `LrclibLyricsProvider.selectBest` picking NF (covered by tests).

- [ ] **Step 4: Requesting code review** (superpowers:requesting-code-review) before integrating.

- [ ] **Step 5: Finish the branch** (superpowers:finishing-a-development-branch): reconcile the main-tree WIP (commit/stash — user's call), then `--no-ff` merge into `master`, and remove the worktree.

## Self-review notes

- **Spec coverage:** preload (T4, T9), LRCLIB-only (T3), shared service (T8), accuracy fix (T1, T2, T8), DB cache reuse (T3 relies on existing `LyricsCache`), per-guild toggles (T6), commands (T7, T11), auto-show (T10). All spec sections map to a task.
- **Type consistency:** `preloadPrimary(String)` used identically in T3/T4/T9/T10; `LyricsQuery.forTrack(AudioTrack)` in T1/T8/T9/T10; `parseToggle(String,boolean)` in T5/T7; `isAutoPreloadLyrics()/isAutoShowLyrics()` in T6/T7/T9/T10/T11.
- **Integration tasks (T8–T11)** are gated on `mvn clean test` (the suite is pure/unit, no live Discord) plus manual verification, consistent with this codebase's testing approach.
