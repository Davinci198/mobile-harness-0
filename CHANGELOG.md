# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The app's
`versionName` is the release identifier; `mobile-harness-update.json` carries
the machine-readable version code.

## [1.0.13] - 2026-11-26

First published build of the stable channel. `versionCode` 13 is the first
value the in-app updater will offer, since 12 is what the installed APKs carry.

## [Unreleased]

### Fixed
- **In-app updates were dead.** The manifest URL returned HTTP 404 and every
  failure was reported as "up to date". The release workflow now generates
  `mobile-harness-update.json` from the built APKs (SHA-256 included) and
  publishes it on the channel release the app reads; a failed check is now
  visible in Settings → Updates, with retry and a real "checking" state.
- **`Settings → Clear terminal history` did nothing.** It cleared a line buffer
  the app no longer used; it now closes the live PTY sessions and their guest
  processes.
- Clearing the terminal in Settings is translated (EN/RO) and reports honestly.
- Release builds are minified (`R8` + resource shrinking) with rules that keep
  the JNI entry points, manifest components, Room and plugin IPC intact.
- Provider endpoint/environment logging is debug-only.

### Changed
- Release pipeline publishes signed **online and offline** APKs; it previously
  shipped debug-signed APKs only and never an offline one.
- Android Lint runs in CI on every push.
- Transcript persistence is debounced (500 ms) instead of rewriting the whole
  chat JSON on every streamed token, with an explicit flush on turn boundaries
  and when the app leaves the foreground.
- Dependency versions have a single source of truth (`gradle/libs.versions.toml`)
  and Dependabot keeps them current.
- Baseline profile + `profileinstaller` for faster cold start.

### Removed
- `ui/SettingsScreen.kt` (871 lines) — `LegacySettingsScreen` was private and
  unreferenced.
- `ui/TerminalScreen.kt` (545 lines) — only reachable through the hardcoded
  `USE_PTY_TERMINAL = false` branch.
- The ViewModel's unused "quick terminal" state machine behind the same flag.

### Moved
- `bug.txt` → `docs/archive/bug-report-2026-10-08.txt`
- `.planning/` → `docs/archive/planning/`
