# Repository Guidelines

## Project Structure & Module Organization

This is a Java 11 Maven project. Core bot code is under `src/main/java/net/jellyo/jmusicbot`, with command handlers grouped by role in `commands/admin`, `commands/dj`, `commands/music`, `commands/owner`, and shared audio, queue, lyrics, settings, and utility code in sibling packages. Existing Java files use the `com.jagrosh.jmusicbot` package namespace; follow nearby files when adding classes.

Resources live in `src/main/resources`, including `reference.conf`, `logback.xml`, and platform native connector libraries under `natives/`. Tests are in `src/test/java/net/jellyo/jmusicbot`. Build output goes to `target/`; use the shaded `JMusicBot-*-All.jar` for local runs and releases.

## Build, Test, and Development Commands

- `mvn test`: compiles the project and runs unit tests.
- `mvn integration-test`: matches the CircleCI validation command.
- `mvn -q -DskipTests package`: builds the regular and shaded jars quickly.
- `java -Dnogui=true -jar target/JMusicBot-<version>-All.jar`: runs the bot headlessly after building.
- `scripts/run_jmusicbot.sh`: downloads the latest release jar and restarts the bot in a loop.

## Command Implementation Notes

Prefix commands are registered from `JMusicBot#createCommandClient`; slash commands are registered in `SlashCommandListener#buildSlashCommands`. When adding or changing a user-facing prefix command in `commands/general`, `commands/music`, `commands/dj`, or `commands/admin`, also add or update the matching slash command unless there is a clear reason not to. Keep the slash command handler behavior aligned with the prefix command, including permission checks, voice/channel checks, aliases that users rely on, and help text.

Owner-only maintenance commands in `commands/owner` should normally remain prefix-only unless explicitly requested. Examples include `autoplaylist`, `debug`, owner playlist management, bot identity/status commands, `shutdown`, and optional `eval`.

When changing slash command coverage, update `SlashCommandListenerTest` so command registration stays unique and expected command parity is covered. If a prefix alias is important user-facing behavior, prefer making it explicit in code or `reference.conf` rather than relying only on an existing local config.

## Coding Style & Naming Conventions

Use Java 11 language features only. Keep the existing style: 4-space indentation, braces on their own lines for classes and methods, and concise Javadoc only where it adds useful context. Name classes in `PascalCase`; command classes generally end in `Cmd` such as `PlayCmd` or `SetvcCmd`. Prefer small changes scoped to the relevant command, service, or utility package.

## Testing Guidelines

Tests use JUnit 4 with `org.junit.Test` and `org.junit.Assert`. Add tests beside existing ones in `src/test/java/net/jellyo/jmusicbot`, name files `*Test.java`, and give test methods descriptive camelCase names. Run `mvn test` before committing; use `mvn integration-test` when touching Maven configuration, resources, or startup behavior.

## Commit & Pull Request Guidelines

Always create a feature branch before making changes so the work can be merged back into the repository's default branch afterward. This checkout currently uses `master`; if the repository moves to `main`, use `main` as the merge target.

Other people may be working in this repository at the same time, so expect conflicts when committing, pulling, rebasing, or pushing to GitHub. Review conflicts carefully and preserve unrelated user or collaborator changes. If a conflict resolution is not obvious, ask the user before choosing a side. Close all merge or rebase conflicts before handing work back so the branch is ready to merge.

Recent commits use short, imperative summaries such as `Add DAVE audio compatibility` or direct maintenance summaries like `Update QueueCmd.java`. Keep subjects focused and under one line.

Pull requests should follow `.github/PULL_REQUEST_TEMPLATE.md`: mark the change type, describe the change, explain the purpose or user impact, and link relevant issues with `Closes #123` when applicable. Include screenshots or logs only when they clarify user-visible behavior or runtime failures.

## Release Guidelines

Releases are created from the `origin` remote, which points to `jellyo-o/JellyoMusicBot`; `upstream` points to the source fork and should not be used for this project's releases. When using GitHub CLI, pass `--repo jellyo-o/JellyoMusicBot` if there is any ambiguity.

Use the fork-owned CalVer scheme documented in `VERSIONING.md`: `YYYY.M.patch[-qualifier]`. Stable examples are `2026.5.0`, `2026.5.1`, and `2026.6.0`. Prerelease examples are `2026.5.0-a1`, `2026.5.0-b1`, and `2026.5.0-rc1`; mark those GitHub releases as prereleases. Tags must match the Maven version exactly, without a leading `v`. Do not encode upstream JMusicBot or SeVile version numbers into this fork's release version.

Before publishing, run `mvn integration-test` and `mvn -q -DskipTests package`. Upload the shaded jar from `target/JMusicBot-<version>-All.jar`, but name the release asset `JMusicBot-<version>.jar` to match previous releases. Include both `md5sum` and `sha256sum` values in the release notes. Do not commit generated jars or other `target/` output.

## Security & Configuration Tips

Never commit `config.txt`, Discord bot tokens, Spotify credentials, generated databases, or release jars from `target/`. Configuration defaults belong in `src/main/resources/reference.conf`; user-specific values belong in local runtime files.
