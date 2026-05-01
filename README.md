# ![Jellyo Music Bot Logo](./JellyoMusicBotLogo.png) JellyoMusicBot (Enhanced JMusicBot Fork)  

> A fork of the original [jagrosh/MusicBot](https://github.com/jagrosh/MusicBot), via the intermediate fork [SeVile/MusicBot](https://github.com/SeVile/MusicBot), adding Spotify playback support and Automatic Lyrics support.

## ✨ What’s New In This Fork

| Feature | Description |
|---------|-------------|
| Spotify Support | Powered by `lavasrc` + protocol modules (configure `spotifyid` & `spotifysecret`). |
| Lyrics Integration | Internal lyrics package (no external API key required for core behavior). |

## 🔧 Requirements

* Java 11+ (JRE or JDK)
* (Optional) Docker Engine running locally (for automatic YouTube tokens)
* A Discord Bot Token (create via the [Discord Developer Portal](https://discord.com/developers/applications))

## 🚀 Quick Start

1. Download (or build) the shaded JAR: `JMusicBot-<version>-All.jar`.
2. Place it in an empty folder (so generated files stay organized).
3. First run (will generate default config template if missing):

  ```bash
  java -Dnogui=true -jar JMusicBot.jar
  ```

1. Edit `config.txt` with at least: `token`, `owner`, and (optionally) Spotify + YouTube keys.
2. Re-run the bot. Type your prefix + `help` in Discord to explore commands.

### Building From Source

```bash
mvn -q -DskipTests package
```

Artifact will appear under `target/` (use the `*-All.jar` for a self‑contained build).

## ⚙️ Configuration Overview (config.txt)

Most options are embedded in the shipped `reference.conf`; on first run a minimal file is created if needed. Key additions in this fork:

| Key | Purpose | Notes |
|-----|---------|-------|
| `spotifyid` / `spotifysecret` | Enable Spotify track/playlist lookup via lavasrc. | Get from a Spotify Developer application. |

## 📝 Lyrics

The fork bundles an internal lyrics module (no separate setup). Use the lyrics command (see in‑bot help) while a track is playing to fetch and display lyrics when available.

## 🔄 Updating

* Replace the existing JAR with a newer release and restart.
* The `scripts/run_jmusicbot.sh` helper can auto‑download the latest release from this fork.

## Versioning

This fork uses its own CalVer release line instead of continuing upstream-style `0.6.0.x` versions:

```text
YYYY.M.patch[-qualifier]
```

Example stable releases: `2026.5.0`, `2026.5.1`, `2026.6.0`. Example prereleases: `2026.5.0-a1`, `2026.5.0-b1`, `2026.5.0-rc1`.

See [`VERSIONING.md`](VERSIONING.md) for the release rules.

## ❓ Support & Feedback

This fork is maintained on a best‑effort basis. For general JMusicBot usage questions, the original project resources and community still apply.

| Need | Where |
|------|-------|
| Upstream bugs/features | <https://github.com/jagrosh/MusicBot> |
| Fork‑specific issues | Open an Issue in this repo |
| Idea / discussion | GitHub Discussions (if enabled) |

## 📜 Attribution & Licensing

This project is licensed under the **Apache License 2.0** (see `LICENSE`).

Lineage:

1. Original: © 2017‑2025 John Grosh (jagrosh) – JMusicBot
2. Intermediate fork: SeVile/MusicBot (YouTube session‑related fixes)
3. Current fork: This repository (Spotify Integration + Automatic Lyrics)

All original notices are retained. Add your own notice if you redistribute further.

## ⚠️ Disclaimer

Spotify playback resolves tracks via available source managers; quality/availability may vary. Automated YouTube session handling is provided for convenience; ensure your usage complies with YouTube & Discord Terms of Service.

## 💎 Original READMEs

[Original README](https://github.com/jagrosh/MusicBot/blob/master/README.md)

[Fork that fixed YouTube sources README](https://github.com/SeVile/MusicBot/blob/master/README.md)

---
Enjoy the music! 🎶
