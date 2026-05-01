# Versioning

JellyoMusicBot uses a fork-owned CalVer scheme:

```text
YYYY.M.patch[-qualifier]
```

Examples:

- `2026.5.0` - first stable release in May 2026.
- `2026.5.1` - follow-up patch release in May 2026.
- `2026.6.0` - first stable release in June 2026.
- `2026.5.0-a1` - first alpha for the May 2026 release.
- `2026.5.0-b1` - first beta.
- `2026.5.0-rc1` - first release candidate.

Use this scheme for Maven versions, Git tags, and GitHub release names. Tags should match the Maven version exactly, without a leading `v`.

## Rules

- The first two numbers are the release year and month.
- The third number is a patch counter that starts at `0` for each month.
- Use `-aN`, `-bN`, or `-rcN` for prereleases and mark those GitHub releases as prereleases.
- Do not encode upstream JMusicBot or SeVile version numbers into this fork's release version.
- Mention upstream lineage or important upstream merge points in release notes when relevant.

## Current Line

The current development line is `2026.5.0-a3`.
