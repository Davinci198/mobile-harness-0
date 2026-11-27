#!/usr/bin/env python3
"""Writes mobile-harness-update.json next to the release APKs.

The in-app updater reads this file from
https://github.com/<owner>/<repo>/releases/latest/download/mobile-harness-update.json
and refuses anything without a checksum, so the manifest is generated from the
built APKs themselves instead of being hand-edited (the checked-in copy had
drifted five versions behind the app).

Usage:
  scripts/generate_update_manifest.py OUT_DIR VERSION_CODE VERSION_NAME BASE_URL \
      --notes "notes" --channel update-channel
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

# Only the online flavour is built; the offline one was removed because it could
# never ship its bundles (gitignored archives, no fetch on a clean runner).
VARIANTS = ("online",)


def sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build(out_dir: str, version_code: int, version_name: str, base_url: str,
          notes: str, channel: str) -> dict:
    artifacts = {}
    for variant in VARIANTS:
        name = f"mobile-harness-{variant}-v{version_name}.apk"
        path = os.path.join(out_dir, name)
        if not os.path.isfile(path):
            raise SystemExit(f"missing release artifact: {path}")
        artifacts[variant] = {
            "url": f"{base_url}/{name}",
            "sha256": sha256(path),
            "sizeBytes": os.path.getsize(path),
        }
    return {
        "versionCode": version_code,
        "versionName": version_name,
        "notes": notes,
        "channel": channel,
        "artifacts": artifacts,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("out_dir")
    parser.add_argument("version_code", type=int)
    parser.add_argument("version_name")
    parser.add_argument("base_url")
    parser.add_argument("--notes", default="")
    parser.add_argument("--channel", default="update-channel")
    args = parser.parse_args()

    manifest = build(args.out_dir, args.version_code, args.version_name,
                     args.base_url, args.notes, args.channel)
    destination = os.path.join(args.out_dir, "mobile-harness-update.json")
    with open(destination, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)
        handle.write("\n")
    print(destination)
    return 0


if __name__ == "__main__":
    sys.exit(main())
