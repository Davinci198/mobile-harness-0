# Contributing

## Ground rules

- **No local Android builds required.** CI builds and tests every push; a
  contributor can iterate on Kotlin without an SDK (unit tests are pure JVM).
  If you do build locally, use the Gradle wrapper.
- Formatting is the project style already in the tree: 4-space indent, trailing
  commas, explicit imports, no wildcard imports.
- Every user-visible string lives in both `res/values/strings.xml` and
  `res/values-ro/strings.xml` — `I18nGuardTest` fails the build otherwise.
  Comments and KDoc may be English or Romanian; UI copy must be translated.
- No new top-level dependency without a line in `gradle/libs.versions.toml`.

## Before you open a PR

```bash
./gradlew :app:testOnlineDebugUnitTest
./gradlew :app:lintOnlineDebug
```

Both run in CI (`.github/workflows/dany-debug-apk.yml`). A PR that changes the
update channel, the runtime bundle names/checksums, or the terminal/settings
surfaces should also mention which of the guard tests covers it
(`UpdateManifestTest`, `RuntimeBundleManifestTest`, `I18nGuardTest`).

When a CI run goes red and the job log will not load (the Actions log host is
occasionally unreachable), `dany-debug-apk` publishes the failing compiler
errors and test names as check annotations and uploads `unit-test-reports`.
There is also `.github/workflows/ci-diag.yml`: a non-blocking probe that runs
the unit-test compilation alone and attaches `probe-log` as an artifact. It is
the fastest way to see a Kotlin compile error without a local JDK.

## Where things live

| Path | What |
|---|---|
| `app/src/main/java/com/jarves/mh/ui` | Compose UI (PocketDevApp, screens) and `MainViewModel` |
| `app/src/main/java/com/jarves/mh/runtime` | Agent bridges, PRoot process launch, keep-alive, installer |
| `app/src/main/java/com/jarves/mh/tools` | Tool surface exposed to agents |
| `app/src/main/java/com/jarves/mh/storage` | File backends (SAF, all-files, Shizuku) |
| `app/src/main/jni`, `app/src/main/cpp` | `pocketspawn` native launcher (JNI symbol names are in `app/proguard-rules.pro`) |
| `dist/runtime-bundles/manifest.json` | Published bundle channel, verified against `RuntimeInstaller` by tests |
| `docs/` | Audits, ADRs, Play submissions; `docs/archive/` keeps closed reports |

## Release

Run **dany-debug-apk** from `main` with `workflow_dispatch`. It builds and signs
the release APK, generates `mobile-harness-update.json` from the APKs, and
republishes the `update-channel` release; the in-app updater reads
`releases/latest/download/mobile-harness-update.json`, so never hand-edit that
file.

The same dispatch on a **branch** builds the minified release variants but
publishes nothing: it uploads them as `mh-release-validation` (plus
`mapping.txt`). That is the way to prove a proguard change before merging.

Signing: `release` uses the `MH_UPLOAD_*` secrets when all four are present and
falls back to the stable debug keystore otherwise. The fallback matters —
everything shipped so far is debug-signed, so a brand-new upload key would
upgrade-over-install fail for the existing user base. The workflow verifies the
APK signature before writing the manifest and fails rather than publish an
unsigned build.

**Bump `versionCode` before publishing from `main`.** The in-app updater only
offers a build whose `versionCode` is strictly greater than the installed one,
so republishing with the current value reaches nobody.
