# Security policy

## Reporting a vulnerability

Open a **private** security advisory on this repository
(*Security → Report a vulnerability*) instead of a public issue. Please include
the affected version (`Settings → About` shows `versionName`), the Android
version, and reproduction steps. We aim to acknowledge within 7 days.

## Threat model in one paragraph

Mobile Harness runs a full Ubuntu userspace **inside the app's own sandbox**
using PRoot. PRoot is a `ptrace`-based path translator, not a kernel boundary:
code inside the guest runs with the app's UID and can read anything the app can
read. Do not treat the guest as a security jail, and only run projects and
dependencies you trust. This is stated up front in the README for the same
reason.

## What the app does protect

| Asset | Protection |
|---|---|
| Provider API keys, GitHub token | AES-256-GCM, key material in AndroidKeyStore; ciphertext in Room. Never written to logs, never in SharedPreferences. |
| Update channel | APK must match the installed signing certificate *and* the SHA-256 published in `mobile-harness-update.json`; version code must be newer. |
| Runtime bundles | Every archive is verified against a pinned SHA-256 before extraction. |
| Network | Cleartext HTTP is disabled app-wide; only `localhost`/`127.0.0.1` (the guest preview server and local proxies) is allowed. |
| Backups | `allowBackup=false`, plus explicit exclusion rules for the credential store and the PRoot workspace. |
| Device access beyond the sandbox | Requires Shizuku (`adb shell` level), Accessibility, or `MANAGE_EXTERNAL_STORAGE`; each is opt-in and off by default. |

## What it does not protect

- Root/shizuku-level access granted to a *different* app on the same device.
- Anything the user pastes into the terminal or a project.
- Telemetry: there is none. No remote proxies, no analytics endpoint.

## Supported versions

Only the latest published release receives fixes; the app updates itself
through the channel described in `docs/update-testing.md`.
