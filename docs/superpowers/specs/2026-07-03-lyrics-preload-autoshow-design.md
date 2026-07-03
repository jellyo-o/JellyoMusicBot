# Lyrics preloading, auto-show, and accuracy fix — design

**Date:** 2026-07-03
**Branch:** `feature/lyrics-preload-autoshow`
**Status:** approved (design), pending implementation

## Summary

A quality-of-life bundle for the lyrics subsystem, built around **one shared lyrics
service** and **one canonical query key** so every code path agrees on what a song's
lyrics are keyed under:

1. **Preload lyrics for upcoming queue songs** (up to 5) so `/lyrics` is instant.
2. **Auto-show lyrics** when a new song starts (per-guild, opt-in).
3. **Per-guild toggles** for both behaviours, persisted like every other guild setting.
4. **Fix the lyrics-accuracy bug** where a bare title ("Time") matches the wrong song
   ("Drake - From Time") — caused by discarding the artist at lookup time.
5. Confirm/rely on the existing **SQLite lyrics cache** so a repeat request never
   re-fetches.

Persistent lyrics storage already exists (`LyricsCache` → `lyrics-cache.db`); this work
adds **scheduling** (warm the cache ahead of time) and **accuracy** (better keys), not a
new store.

## Background / current state (verified)

- `LyricsService.fetchAndCache(rawQuery, allowDifferentArtistFallback)` already does
  *cache-check → LRCLIB → Genius → cache-store*. A cache hit returns instantly with no
  network call. So a preloaded song is a pure cache hit later.
- **Two providers, very different cost:** LRCLIB (primary, fast, **no** rate limit) and
  Genius (fallback, **blocking 10s-between-calls** file-lock limiter shared process-wide,
  `GeniusClient.RATE_LIMITER`). Preloading must never lean on Genius.
- **Three separate `LyricsService` instances** exist today, all opening `lyrics-cache.db`:
  `LyricsCmd` (static), `SlashCommandListener` (field), `NowplayingHandler` (field). No
  shared instance on `Bot`.
- **Accuracy bug root cause:** all three `/lyrics` entry points pass only
  `track.getInfo().title`; `getInfo().author` (the artist) is in scope but discarded.
  For query `"Time"`, `LrclibLyricsProvider` issues `q=Time`, the token scorer gives
  "Drake - From Time" ≈ 0.48, and the acceptance gate is a permissive **0.12**. With the
  query built as `"NF - Time"` instead, the existing scorer returns **1.0** for NF's exact
  track and **0.20** for Drake — NF wins. (`LrclibLyricsProvider.score`, lines 115–139.)
- **Queue enumeration:** `AudioHandler.getQueue().getList()` (copy first, per
  `snapshotQueue()`); index 0 is the next track; the current track lives on
  `audioPlayer.getPlayingTrack()`, not in the queue.
- **Now-playing channel:** resolved by `NowplayingHandler.getDefaultPanelChannel(guild,
  track)` — `Settings.getTextChannel(guild)` first, else the request's text channel from
  `RequestMetadata`. This is the channel auto-show posts to.
- **Guild settings** persist in `serversettings.json` via `SettingsManager` (JSON, not the
  SQLite DB). Adding a boolean means touching `Settings` (field + getter/setter + both
  constructors), `SettingsManager` (read + `createDefaultSettings` + `writeSettings`).
- **Track start hook:** `AudioHandler.onTrackStart` → `NowplayingHandler.onTrackUpdate`
  (line ~916). Guess-game snippets return early before this, so neither preload nor
  auto-show fires during the guessing game.
- **Toggle-command pattern:** `UnifiedCommand` (`execute(CommandEvent)` → `doCommand(new
  MessageCommandContext(event))`, real logic in `doCommand(CommandContext)`). Slash side =
  field + ctor init + `buildSlashCommands()` entry + switch case in
  `onSlashCommandInteraction`. Admin gating via `handleSharedAdminCommand` +
  `.setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))`,
  as `QueueTypeCmd` does.

## Decisions (settled)

| Decision | Choice |
|---|---|
| Preload provider policy | **LRCLIB only** — never touch Genius / its rate limiter |
| Preload count | **5** upcoming tracks; override via `-Dlyrics.preloadCount` |
| Service wiring | **One shared `LyricsService` on `Bot`**; all sites use it |
| `autoPreloadLyrics` default | **ON** (invisible, free background warming) |
| `autoShowLyrics` default | **OFF** (posts every song; opt-in to avoid spam) |
| Commands | `/preloadlyrics`, `/autolyrics`, both `[on\|off]`, **Manage Server** gated |
| Config keys | System properties only (`-Dlyrics.preloadCount`); **no new HOCON keys** → `BotConfigTest` untouched |

## Design

### A. Shared lyrics service on `Bot`

- `Bot` constructs and owns one `LyricsService` (path `lyrics-cache.db`), exposed via
  `getLyricsService()`. Lazy/eager: construct eagerly in `Bot` (it already builds every
  store), guarding the checked-exception constructor; on failure log and leave the getter
  returning `null` so lyrics degrade gracefully rather than crashing the bot.
- Repoint the three existing instantiations at `bot.getLyricsService()`:
  `LyricsCmd`, `SlashCommandListener`, `NowplayingHandler`. Remove their private
  lazy-init fields/methods (or have them delegate to Bot). One SQLite connection, one
  monitor — the added preload writes don't increase cross-connection lock contention.

### B. Canonical query builder (accuracy fix)

New `com.jagrosh.jmusicbot.lyrics.LyricsQuery`:

```java
public static String forTrack(AudioTrack track);          // uses getInfo().title + author
public static String forTitleAndAuthor(String title, String author);
static String cleanArtist(String author);                  // strip "- Topic"/VEVO/Official/…
```

- Returns `cleanArtist(author) + " - " + title` when the cleaned author is meaningful and
  not already contained in the title; otherwise the bare `title`. Mirrors the existing
  author-cleaning in `AutoplayService.artistSearchName` / `GuessMusicTitleMatcher.cleanArtist`
  (kept local to avoid cross-package coupling).
- **All** lyrics sites build their key through this: `/lyrics` prefix + slash, the
  now-playing lyrics button, the preloader, and auto-show. Because preload and lookup use
  the *identical* builder, a preloaded entry is guaranteed to be found later.

Provider robustness (in `LrclibLyricsProvider`): when the query splits into artist/title
(`InputValidator.splitArtistTitle`), additionally issue LRCLIB's artist-scoped search
(`track_name` + `artist_name` query params) so the correct track surfaces even when the
free-text `q=` ranking buries it. Existing scoring (artist/title bonus) already separates
right from wrong once the artist is present; keep the `0.12` gate but verify the
artist-aware path in tests. (Optional, low-risk: a small wrong-artist penalty — only if
tests show it's needed.)

### C. Lyrics preloader

New `com.jagrosh.jmusicbot.lyrics.LyricsPreloader` (owned by `Bot`, or a small helper the
`AudioHandler` calls):

- Trigger: `AudioHandler.onTrackStart`, after the now-playing update, **only if the guild's
  `autoPreloadLyrics` is ON**.
- Snapshot the next `N` (=5) `QueuedTrack`s via `getQueue().getList()` (copied), build each
  key with `LyricsQuery.forTrack(qt.getTrack())`, skip streams (`getInfo().isStream`).
- Warm the cache on a **dedicated single-thread daemon `ExecutorService`** (isolated from
  `getBlockingThreadpool()` so preloads never contend with real-time `/lyrics`, economy, or
  dashboard I/O). Never on `getThreadpool()` (the single scheduler).
- **LRCLIB-only:** new `LyricsService.preloadPrimary(String rawQuery)` runs
  *cache-check → LRCLIB → cache-store*, **skipping Genius entirely**. Guarantees preloading
  never blocks on the 10s Genius limiter or spends its shared budget speculatively.
- **Dedupe:** a bounded LRU `Set` of already-attempted normalized keys in the preloader.
  As the queue advances one track at a time, the next-5 window overlaps by 4 each time —
  the set prevents redundant LRCLIB calls (and repeated misses for songs LRCLIB lacks).
- Count via `Integer.getInteger("lyrics.preloadCount", 5)`.

### D. Per-guild toggles

Add two booleans to `Settings`: `autoPreloadLyrics` (default **true**), `autoShowLyrics`
(default **false**). For each: field + `is…()` getter + `set…(boolean)` setter (setter calls
`manager.writeSettings()`); add to **both** constructors; read in the `SettingsManager`
constructor (`o.has("auto_preload_lyrics") ? o.getBoolean(...) : true`, and
`… : false` for auto-show); add to `createDefaultSettings()`; write in `writeSettings()`
(only persist non-default values, matching the existing style). Surface both in the
settings embed (`SettingsCmd` + slash `handleSettings`).

### E. Auto-show lyrics on track start

- In `NowplayingHandler` (it already has the guild, resolved channel, and now the shared
  `LyricsService`): when a track starts and the guild's `autoShowLyrics` is ON, resolve the
  panel channel (`getDefaultPanelChannel`), fetch lyrics via the shared service, and post
  the existing lyrics embed.
- **LRCLIB-only on the auto path too:** auto-show uses the same *cache → LRCLIB* path as
  preload (`preloadPrimary`), **never Genius**. This keeps an opt-in guild from triggering
  the 10s Genius limiter on every track start, and it means auto-show is essentially free
  when preload already warmed the entry. Genius stays reserved for explicit manual
  `/lyrics`.
- Runs **off the JDA/playback thread** (dedicated executor or `CompletableFuture`). Skips:
  guess-game (already short-circuited upstream), streams/live, and the case where no lyrics
  are found (post nothing — silent, to avoid channel spam). Reuse the embed builder from
  `NowplayingHandler.handleLyrics` (title = `artist - title` linked to `sourceUrl`).

### F. Commands

Two `UnifiedCommand`s under `commands/admin` (Manage Server gated, like `QueueTypeCmd`):

- `PreloadLyricsCmd` — `/preloadlyrics [on|off]` toggles `autoPreloadLyrics`.
- `AutoLyricsCmd` — `/autolyrics [on|off]` toggles `autoShowLyrics`.

Empty arg toggles the current value. Register: prefix list in `JMusicBot.createCommandClient`
(+ help), slash field/ctor/`buildSlashCommands`/switch-case in `SlashCommandListener`,
dispatched via `handleSharedAdminCommand`. Optional shared parser
`CommandParsers.parseToggle(String, boolean)` for `on|off|<empty=toggle>`.

## Testing

JUnit 4, mirroring the main tree under `src/test/.../lyrics/` and `commands/`:

- **`LyricsQueryTest`** — `forTrack`/`forTitleAndAuthor`/`cleanArtist`: `"NF"`+`"Time"` →
  `"NF - Time"`; YouTube noise (`"NF - Topic"`, `"NFVEVO"`, `"… Official"`) cleaned; blank
  or title-contained author falls back to bare title.
- **`LrclibLyricsProvider` scoring test** — with a stubbed candidate set containing NF/Time
  and Drake/From Time, assert query `"NF - Time"` selects NF (score 1.0 > 0.20). Use the
  existing provider test seams (stubbed loader/HTTP) if present; otherwise unit-test the
  extracted scoring on the `LyricsResult` shape.
- **`LyricsPreloader` test** — a fake `LyricsService` recording `preloadPrimary` calls:
  asserts ≤5 keys, dedupe across overlapping windows, streams skipped, and that Genius is
  never invoked (fake primary-only path).
- **`Settings`/`SettingsManager` round-trip** — new booleans persist and reload with the
  right defaults (ON / OFF); non-default-only write keeps the JSON minimal.
- **Toggle command tests** — extend `SlashCommandListenerTest` style: on/off/empty-toggle
  flips the setting; Manage-Server gating enforced.
- Full `mvn clean test` green before any commit/merge.

## Out of scope (YAGNI)

- Preloading on enqueue/bulk-add (only next-up on track start).
- Negative-result DB caching (in-memory dedupe set suffices).
- New HOCON config keys / dashboard surfacing.
- Reworking the Genius scraper or its rate limiter.

## Integration note

The main working tree has **uncommitted WIP** (the `/hostgame` feature) touching
`AudioHandler`, `SlashCommandListener`, `JMusicBot`. This feature also edits those files on
its branch. Before the final `--no-ff` merge into `master`, that WIP must be committed or
stashed (user's call) to avoid "local changes would be overwritten" — surface at merge time.
