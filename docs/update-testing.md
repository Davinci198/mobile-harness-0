# The in-app update channel

## How the shipped channel works

The app reads

```
https://github.com/Davinci198/mobile-harness-0/releases/latest/download/mobile-harness-update.json
```

(`BuildConfig.APP_UPDATE_MANIFEST_URL`, set in `app/build.gradle.kts`).

That asset is produced by `.github/workflows/dany-debug-apk.yml` when the
workflow is dispatched **from `main`**:

1. `assembleOnlineRelease` + `assembleOfflineRelease` build signed release APKs
   (minified — `app/proguard-rules.pro` keeps the JNI entry points and the
   components referenced only from the manifest).
2. `scripts/generate_update_manifest.py` writes `mobile-harness-update.json`
   with the real size and SHA-256 of both APKs; the version comes from
   `app/build.gradle.kts`.
3. The `update-channel` release is recreated with both APKs and the manifest
   and marked *latest*, so the URL above always resolves to the current
   channel. Never hand-edit `mobile-harness-update.json` in the repo: the
   checked-in file is only a template.

`UpdateManifestTest` pins the parser contract: a newer version needs a
variant-matching **https** URL and a non-empty checksum, an older/equal version
is *up to date*, and anything malformed is a **failure** shown in
Settings → Updates (a broken channel used to look identical to "up to date").

`AppUpdater.download` still enforces the checksum, the package name, the
manifest version code and the signing certificate before handing the APK to the
installer.

## Testing the updater without publishing

Debug builds add a Settings entry that overrides the manifest URL, so the full
download / SHA-256 / package / signature / installer flow can be exercised
against a locally-built APK exposed via a temporary HTTPS tunnel. Nothing has to
be uploaded to GitHub.

## What you need

- A phone with an older debug APK installed.
- `cloudflared` *or* `ngrok` installed locally for the HTTPS tunnel.
- The same debug signing key Android Studio uses (the default Android
  debug keystore is fine — the OTA verifier compares the installed key
  against the downloaded APK's signer).

## 1. Build a newer APK

First start Cloudflare Tunnel (or ngrok) in one terminal. It can start
before the local server and will wait for port 8080:

```bash
cloudflared tunnel --url http://localhost:8080
# or: ngrok http 8080
```

Copy the HTTPS URL it prints. In another terminal, build and serve the
newer APK with that URL baked into its manifest:

```bash
./scripts/serve-update-server.sh 8080 https://example.trycloudflare.com 4 1.0.3-test
```

The final two arguments are optional and default to `4` and
`1.0.3-test`. The script writes generated files to the ignored
`dist/update-test/` directory.

## 2. Point the debug app at the manifest

On the Moto, open **Settings → Update channel** (debug builds only —
hidden in release builds).

Paste:

```
https://<your-tunnel-url>/mobile-harness-update.json
```

Tap **Use & check**. The updater immediately re-fetches the manifest. The
"newer version" card should appear on the dashboard.

## 3. Walk the real flow

Trigger the update from the dashboard. The full production path runs:

1. `Install unknown apps` permission check. If missing, the card explains
   why the install can't proceed and points at system Settings.
2. Streamed download with live MB / percentage progress.
3. SHA-256 check against the manifest.
4. Package name + versionCode + signing certificate verification against
   the installed app.
5. Hand-off to Android's installer. The system still shows its final
   confirmation screen — that's expected and not part of the app.

## 4. Reset

When you're done, tap **Reset** under Update channel. The app falls back
to the default GitHub manifest URL on the next check.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Manifest returns 404 | Tunnel points at the wrong port | Confirm Cloudflare/ngrok URL resolves to `localhost:<port>` |
| Settings shows "Could not check for updates" | The channel asset is missing (release not published, or the `update-channel` release was deleted) | Dispatch **dany-debug-apk** from `main` |
| Download never starts | Phone can't reach the tunnel | Visit the URL in the phone's browser to verify |
| "Update package name does not match" | The new APK was signed with a different key | Rebuild the test APK with the same debug keystore |
| "Downloaded APK failed its SHA-256 verification" | Manifest hash stale | Rerun the script after the rebuild |
| "Update version does not match its manifest" | APK and manifest version codes differ | Rerun the helper with a version code newer than the installed app |
| Tunnel URL rejected | Manifest URL doesn't start with `https://` | Cloudflare/ngrok always issue HTTPS — check the full URL was pasted |

## Re-running

The script wipes `dist/update-test/` on every run, so iteration is safe.
The phone's "Update channel" override persists across reinstalls unless
you clear app data, so subsequent runs only need a new tunnel URL.
