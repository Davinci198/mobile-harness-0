#!/usr/bin/env python3
"""Fails when a release APK carries no signature.

`app/build.gradle.kts` only wires the `upload` signing config when all four
MH_UPLOAD_* variables are present. If one is missing the release variant is
still built — just unsigned — and the workflow would publish an APK that no
phone can install and that the in-app updater would offer forever (the update
is offered, the install fails). R8 output size is no evidence either way, so
the signature is checked here.

Recognises both schemes:
  * v1 (JAR signing): META-INF/*.RSA|*.DSA|*.EC inside the zip
  * v2/v3/v4: the "APK Sig Block 42" magic just before the central directory

Usage: verify-apk-signing.py APK [APK ...]
"""

from __future__ import annotations

import sys
import zipfile

SIG_BLOCK_MAGIC = b"APK Sig Block 42"
V1_SUFFIXES = (".RSA", ".DSA", ".EC")


def has_v1(archive: zipfile.ZipFile) -> bool:
    return any(
        name.startswith("META-INF/") and name.upper().endswith(V1_SUFFIXES)
        for name in archive.namelist()
    )


def has_sig_block(path: str) -> bool:
    # The signing block sits immediately before the end-of-central-directory
    # record; the magic string is the last 16 bytes of the block's footer, so
    # it is always within the final few KB of a large APK.
    with open(path, "rb") as handle:
        handle.seek(0, 2)
        size = handle.tell()
        handle.seek(max(0, size - 65_536))
        return SIG_BLOCK_MAGIC in handle.read()


def check(path: str) -> str | None:
    try:
        with zipfile.ZipFile(path) as archive:
            if has_v1(archive):
                return None
    except zipfile.BadZipFile:
        return "not a zip archive"
    if has_sig_block(path):
        return None
    return "no signature (v1, v2/v3) found"


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: verify-apk-signing.py APK [...]", file=sys.stderr)
        return 2
    failures = []
    for path in argv[1:]:
        problem = check(path)
        status = "signed" if problem is None else problem
        print(f"{path}: {status}")
        if problem is not None:
            failures.append(path)
    if failures:
        print(
            "\nUnsigned release APK. The upload signing config needs all of "
            "MH_UPLOAD_STORE_FILE, MH_UPLOAD_STORE_PASSWORD, MH_UPLOAD_KEY_ALIAS "
            "and MH_UPLOAD_KEY_PASSWORD on the repository (or use the debug "
            "keystore for a throwaway build).",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
