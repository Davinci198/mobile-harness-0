#!/usr/bin/env python3
"""Fails when the offline APK does not actually carry its runtime bundles.

The offline flavour advertises "All selected bundles are included in this
offline app" and the installer prefers `assets/runtime/` for it, but the bundle
archives are gitignored (`dist/runtime-bundles/*.tar.zst`) and nothing downloads
them on a clean runner. `Sync` with a missing source is a no-op, not an error,
so the build succeeds and publishes an "offline" APK that has to download
everything — the one thing the flavour exists to avoid.

This inspects the APK's central directory, so it needs no Android tooling:
a real offline build lists every bundle from dist/runtime-bundles/manifest.json
under assets/runtime/.

Usage: verify-offline-bundles.py OFFLINE_APK [BUNDLE_MANIFEST]
"""

from __future__ import annotations

import json
import sys
import zipfile

# Mirrors app/build.gradle.kts prepareOfflineRuntimeAssets + prepareBundledAgentAssets.
OFFLINE_ASSET_PREFIX = "assets/runtime/"
SHARED_ASSET_PREFIX = "assets/shared/runtime/"
# The online flavour ships the agy bundle in every build (prepareBundledAgentAssets).
SHARED_BUNDLES = {"pocketdev-agy-arm64-2026.09.1.tar.zst"}


def bundles_from_manifest(path: str) -> tuple[list[str], set[str]]:
    with open(path, encoding="utf-8") as handle:
        manifest = json.load(handle)
    files = []
    for entry in manifest.get("bundles", {}).values():
        name = entry.get("file")
        if name:
            files.append(name)
    return files, set(manifest.get("bundles", {}).keys())


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: verify-offline-bundles.py OFFLINE_APK [MANIFEST]", file=sys.stderr)
        return 2
    apk = argv[1]
    manifest = argv[2] if len(argv) > 2 else "dist/runtime-bundles/manifest.json"

    try:
        expected, _ = bundles_from_manifest(manifest)
    except (OSError, json.JSONDecodeError) as error:
        print(f"cannot read {manifest}: {error}", file=sys.stderr)
        return 2
    if not expected:
        print(f"{manifest} lists no bundles; nothing to verify", file=sys.stderr)
        return 2

    try:
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
    except (OSError, zipfile.BadZipFile) as error:
        print(f"cannot read {apk}: {error}", file=sys.stderr)
        return 2

    missing = [
        name
        for name in expected
        if name not in SHARED_BUNDLES
        and f"{OFFLINE_ASSET_PREFIX}{name}" not in names
        and f"{SHARED_ASSET_PREFIX}{name}" not in names
    ]
    present = len(expected) - len(missing)
    print(f"{apk}: {present}/{len(expected)} bundles embedded")
    for name in expected:
        marker = "missing" if name in missing else "ok"
        print(f"  {marker:8s} {name}")

    if missing:
        print(
            "\nThis is an offline APK without the bundles it advertises. The "
            "archives live in dist/runtime-bundles/ and are gitignored, so a "
            "clean CI runner has none: fetch them from the bundle channel "
            "(see scripts/runtime-bundles/README.md) before assembling, or the "
            "offline build is just a bigger online build.",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
