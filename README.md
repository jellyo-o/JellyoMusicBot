# ![JellyoMusicBot logo](./JellyoMusicBotLogo.png) JellyoMusicBot

JellyoMusicBot is a self-hosted Discord music bot based on
[jagrosh/MusicBot](https://github.com/jagrosh/MusicBot), with updates from the
[SeVile/MusicBot](https://github.com/SeVile/MusicBot) fork and additional
features for modern Discord music servers.

The bot supports both prefix and slash commands, Spotify-backed loading,
YouTube/SoundCloud search, saved user playlists, Liked Songs, lyrics lookup,
audio filters, autoplay/radio mode, playback history, persistent music panels,
and an optional local playback dashboard.

## Highlights

- Music playback through Lavaplayer and source managers for YouTube,
  SoundCloud, Spotify, Bandcamp, Vimeo, Twitch, HTTP streams, local files, and
  other bundled sources.
- Discord slash commands registered per guild, with prefix command support kept
  for existing servers and owner maintenance commands.
- Saved user playlists, shared/followed playlists, Liked Songs, playlist
  pagination, and throttled playlist loading to reduce source rate limits.
- Persistent now-playing music panels with queue previews, playback controls,
  like/unlike actions, loop/autoplay status, and safer refresh behavior.
- Lyrics through LRCLIB with Genius fallback, local caching, and a correction
  command for cached lyrics.
- DJ tools for filters, volume, repeat, autoplay/radio, queue movement,
  force-skip, and queue cleanup.
- Playback history for the current voice session and persistent per-server
  history.
- Optional local read-only dashboard with playback stats and currently active
  sessions.
- Discord DAVE audio support through the bundled JDA/libdave integration.
- Global, per-user economy with currency, XP/levels, achievements, a daily
  chest, gambling games, and a guess-the-song game that pays out rewards.
- Sleep timer, a per-server autoplay avoid list, and automatic crash recovery
  with queue restore.
- A single unified database (`jmusicbot.db`) that older split databases are
  migrated into automatically on first launch.
- CalVer releases such as `2026.5.0`; see [VERSIONING.md](VERSIONING.md).

## Economy, XP, and achievements

The bot tracks a **global** profile for every user, keyed by their Discord
account (so it follows username changes and is shared across every server).

- **Currency (coins) and XP.** Requesting songs earns coins and XP; XP
  determines your level. Listening also earns XP — but only while music is
  actually playing (autoplay counts) and only once you've queued a song during
  the current session, so idle lurkers don't farm it. Level-ups and achievement
  unlocks are announced in chat, tagging only you.
- **`/stats [user]`** shows level, XP, balance, listening time, songs
  requested, guess-game record, gambling record, and achievement progress.
- **`/balance [user]`** is a quick balance + global wealth rank check.
- **`/daily`** claims a once-per-day coin chest with a growing streak bonus.
- **`/gamble <amount> [coinflip|dice|slots]`** bets coins on a game of chance
  (`all`/`half` are accepted by the prefix command).
- **`/leaderboard [coins|xp|time|songs|wins]`** shows the global top ten.
- **`/achievements [user]`** lists earned and locked achievements. Achievements
  (e.g. *First Request*, *Night Owl*, *Champion*, *Tycoon*) grant bonus coins
  and XP and are announced in chat when unlocked.
- The economy can be turned off with `economy.enabled = false` in the config.

The guess-the-song game (`/guess`) awards coins and XP for correct guesses and
wins, feeding the same global profile.

## Sleep timer, avoid list, and crash recovery

- **`/sleep <30m | 1h30m | track | 3 tracks | status | off>`** stops playback
  after a duration, after the current song, or after a number of songs, fading
  out gracefully before disconnecting. It is cancelled automatically if a new
  song is queued or everyone leaves the channel.
- **`/avoid [song]`** blocks a song from autoplay (and skips it if it is
  playing); **`/unavoid <song>`** removes it; **`/avoided`** lists the
  server's avoid list. The list is per-server and persistent, so autoplay will
  never pick an avoided song again.
- **Crash recovery.** The playing track and queue are saved periodically and on
  shutdown. After a crash, restart, or everyone leaving, **`/restore`** brings
  the queue back (resuming the current song's position). If you try to play
  something while a saved queue exists and nothing is playing, the bot asks
  whether to restore or start fresh.

## Requirements

- Java 11 or newer.
- A Discord application with a bot token.
- The bot owner's Discord user ID.
- The `bot` and `applications.commands` invite scopes.
- Message Content Intent if you use a custom text prefix instead of mention
  commands.
- Optional: Spotify application Client ID and Client Secret for Spotify links.
- Optional: Docker if you want to generate YouTube PO token and visitor data.
- Optional for development: Maven 3.x.

## Quick Start

1. Download the latest release jar from
   <https://github.com/jellyo-o/JellyoMusicBot/releases/latest>.
2. Put the jar in its own folder. The bot writes config, database, and settings
   files beside the jar by default.
3. Generate a config template, or just run the bot once and follow the prompts:

   ```bash
   java -Dnogui=true -jar JMusicBot-2026.5.0.jar generate-config
   ```

4. Edit `config.txt`.

   At minimum, set:

   ```hocon
   token = BOT_TOKEN_HERE
   owner = 123456789012345678
   ```

5. Start the bot:

   ```bash
   java -Dnogui=true -jar JMusicBot-2026.5.0.jar
   ```

6. In Discord, use `/help` or your configured prefix plus `help`.

For a different config path, pass either system property:

```bash
java -Dnogui=true -Dconfig=/path/to/config.txt -jar JMusicBot-2026.5.0.jar
```

## Configuration

The full example lives in [config.txt.example](config.txt.example), and the
runtime defaults are embedded in
[src/main/resources/reference.conf](src/main/resources/reference.conf).

Common settings:

| Setting | Purpose |
| --- | --- |
| `token` | Discord bot token. Do not use a client secret or user token. |
| `owner` | Discord user ID for owner-only commands and alerts. |
| `spotifyid`, `spotifysecret` | Enables Spotify loading through lavasrc. Set both to `"NONE"` to disable. |
| `prefix`, `altprefix`, `help` | Prefix command behavior. Custom prefixes require Message Content Intent. |
| `npimages` | Adds YouTube thumbnails to now-playing messages. Thumbnail messages do not refresh. |
| `stayinchannel` | Keeps the bot connected after the queue ends. |
| `maxtime`, `autoplaymaxtime` | Track length limits for normal loads and autoplay/radio picks. |
| `maxytplaylistpages` | Maximum YouTube playlist pages to load. |
| `maxspotifyplaylistpages` | Maximum Spotify playlist pages to load. |
| `skipratio` | Default skip vote ratio before a server-specific value is set. |
| `alonetimeuntilstop` | Leaves voice after being alone for the configured number of seconds. |
| `dashboard.*` | Enables and configures the local read-only dashboard. |
| `loglevel` | Runtime logging level. |
| `ytpotoken`, `ytvisitordata` | Optional YouTube Proof of Origin token and visitor data. |
| `ytroutingplanner`, `ytipblocks` | Advanced YouTube IP routing for operators with their own IP blocks. |
| `eval`, `evalengine` | Owner-only code execution. Keep disabled unless you fully understand the risk. |

Never commit a filled-in `config.txt`. It contains secrets.

## Discord Setup Notes

- Invite the bot with both `bot` and `applications.commands` scopes. The bot
  also generates invite URLs with both scopes.
- Recommended permissions include viewing and sending messages, reading message
  history, adding reactions, embedding links, attaching files, managing bot
  messages, connecting and speaking in voice, and changing its nickname.
- Slash commands are guild-scoped and sync on startup when the registered
  command data is stale.
- Prefix commands using `@mention` do not require Message Content Intent.
  Custom text prefixes do.

## Commands

Use `/help` or prefix `help` in Discord for the current command list. This is a
high-level overview.

| Area | Commands |
| --- | --- |
| General | `/about`, `/help`, `/ping`, `/settings` |
| Playback | `/play`, `/playtop`, `/playnext`, `/search`, `/scsearch`, `/nowplaying`, `/queue`, `/history`, `/skip`, `/remove`, `/shuffle`, `/seek` |
| Lyrics | `/lyrics`, `/correctlyrics` |
| Playlists | `/playlist`, `/playplaylist`, `/playlists`, `/like`, `/unlike`, `/liked` |
| DJ | `/forceskip`, `/pause`, `/resume`, `/stop`, `/volume`, `/filter`, `/repeat`, `/loop`, `/autoplay`, `/radio`, `/skipto`, `/move`, `/forceremove` |
| Admin | `/prefix`, `/setdj`, `/settc`, `/setvc`, `/skipratio`, `/setskip`, `/queuetype` |

Most user-facing music, DJ, and admin commands also have prefix equivalents.
Owner maintenance commands remain prefix-only: `debug`, `setavatar`, `setgame`,
`setname`, `setstatus`, `shutdown`, and optional `eval`.

Playlist commands support creating, renaming, deleting, viewing, playing,
adding current/queued/query tracks, moving/removing entries, sharing, following
shared playlists, unfollowing, and copying followed playlists into editable
personal playlists.

Audio filter presets are:

```text
off, bassboost, nightcore, 8d, vaporwave, tremolo, karaoke
```

Autoplay/radio modes are:

```text
off, smart, related, artist, playlist, server
```

## Runtime Files

Run the bot from a dedicated folder. It may create or update:

| Path | Purpose |
| --- | --- |
| `config.txt` | Local secrets and runtime configuration. |
| `serversettings.json` | Per-server prefix, DJ role, text/voice channel, volume, repeat, autoplay, skip ratio, and queue type. |
| `playlists.db` | User playlists, Liked Songs, shares, and followed playlists. |
| `playback-history.db` | Persistent per-server playback history. |
| `lyrics-cache.db` | Cached lyrics results and corrections. |
| `dashboard.db` | Optional dashboard telemetry database. |
| `Playlists/` | Legacy text-file playlists imported for the owner. |

These files are intentionally ignored by Git.

## Dashboard

Set `dashboard.enabled = true` to start the local dashboard server. By default
it listens on `127.0.0.1:8080`.

Available routes:

- `/dashboard` or `/`
- `/api/health`
- `/api/snapshot`

The dashboard is intended for the machine running the bot. It is read-only, but
it is not an authenticated public web app. Keep `bindaddress = "127.0.0.1"`
unless you intentionally want another machine on your network to access it.

## YouTube and Spotify

Spotify support uses a Spotify application Client ID and Client Secret. The bot
does not use short-lived Spotify access tokens in `config.txt`.

YouTube PO token and visitor data are optional. They are not Google API keys.
If YouTube playback starts failing, generate fresh values with the trusted
session generator documented in `config.txt.example`, then set both
`ytpotoken` and `ytvisitordata`.

Playback availability depends on the upstream source managers and on the
platforms they load from.

## Updating

Replace the jar with a newer release and restart the bot. Keep `config.txt`,
database files, and `serversettings.json` in place.

The helper script can download the latest release jar and restart the bot in a
loop:

```bash
scripts/run_jmusicbot.sh
```

Review release notes before updating if you run a busy server or rely on a
specific source provider.

## Building From Source

This is a Java 11 Maven project.

Run tests:

```bash
mvn test
```

Run the same validation used by release prep:

```bash
mvn integration-test
```

Build the regular and shaded jars:

```bash
mvn -q -DskipTests package
```

Use the shaded jar for normal self-hosting:

```text
target/JMusicBot-<version>-All.jar
```

## Troubleshooting

- If login fails, verify `token` is a bot token from the Discord Developer
  Portal, not a client secret.
- If slash commands do not appear, restart the bot and check that it was invited
  with the `applications.commands` scope.
- If prefix commands do not work with a custom prefix, enable Message Content
  Intent on the Discord Developer Portal Bot page.
- If Spotify links do not load, verify both `spotifyid` and `spotifysecret` are
  set and are from the same Spotify application.
- If YouTube playback fails, try fresh `ytpotoken` and `ytvisitordata`, or wait
  for upstream source manager fixes.
- If the dashboard does not start, check whether another process is already
  using the configured port.

## Versioning

This fork uses a fork-owned CalVer scheme:

```text
YYYY.M.patch[-qualifier]
```

Examples:

- `2026.5.0`
- `2026.5.1`
- `2026.6.0`
- `2026.5.0-a1`
- `2026.5.0-b1`
- `2026.5.0-rc1`

Tags match the Maven version exactly and do not use a leading `v`. See
[VERSIONING.md](VERSIONING.md) for the full release rules.

## Security

- Do not publish `config.txt`, Discord bot tokens, Spotify credentials, local
  databases, generated jars, or `target/` output.
- Keep `eval = false` unless you need owner-only runtime code execution and
  understand that it can expose secrets or damage the host.
- Treat dashboard data as operational telemetry. Bind it locally unless you
  place it behind your own access controls.

## Lineage and License

JellyoMusicBot is licensed under the Apache License 2.0. See [LICENSE](LICENSE).

Lineage:

1. Original project: [jagrosh/MusicBot](https://github.com/jagrosh/MusicBot).
2. Intermediate fork: [SeVile/MusicBot](https://github.com/SeVile/MusicBot).
3. Current fork: [jellyo-o/JellyoMusicBot](https://github.com/jellyo-o/JellyoMusicBot).

Original notices are retained in source files where applicable.
