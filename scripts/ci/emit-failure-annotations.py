#!/usr/bin/env python3
"""Turns a failing Gradle run into GitHub annotations.

The job log is not always retrievable (the Actions log host can be unreachable
from CI tooling), but check-run annotations are. This reads the captured Gradle
output and the JUnit XML reports and emits `::error::` lines, so a red build is
diagnosable from the PR checks view and from the API.

Usage: emit-failure-annotations.py LOG_FILE [TEST_RESULTS_DIR]
"""

from __future__ import annotations

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 25
COMPILER_ERROR = re.compile(r"^(e: .+|.*\.kt:\d+:\d+ .+)$")
FAILED_TASK = re.compile(r"^> Task .+ FAILED$")


def escape(value: str) -> str:
    return value.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def emit(message: str, file: str | None = None, title: str | None = None) -> None:
    parts = ["::error"]
    if file:
        parts.append(f"file={file}")
    if title:
        parts.append(f"title={title}")
    print(",".join(parts) + "::" + escape(message))


def annotations_from_log(path: str) -> list[str]:
    if not os.path.isfile(path):
        return []
    lines = []
    with open(path, encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.rstrip("\n")
            if COMPILER_ERROR.match(line) or FAILED_TASK.match(line):
                lines.append(line.strip())
            elif "FAILED" in line and ("Test" in line or ">" in line):
                lines.append(line.strip())
    # Keep the most informative tail, deduplicated, errors first.
    errors = [line for line in lines if line.startswith("e: ")]
    others = [line for line in dict.fromkeys(lines) if line not in errors]
    return (errors + others)[:MAX_ANNOTATIONS]


def annotations_from_reports(directory: str) -> list[str]:
    found = []
    for report in glob.glob(os.path.join(directory, "**", "TEST-*.xml"), recursive=True):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        suite = root.get("name", os.path.basename(report))
        for case in root.iter("testcase"):
            for failure in list(case.iter("failure")) + list(case.iter("error")):
                message = (failure.get("message") or failure.text or "").strip()
                first_line = message.splitlines()[0] if message else "failed"
                found.append(f"{suite}.{case.get('name')}: {first_line[:300]}")
    return found[:MAX_ANNOTATIONS]


def main() -> int:
    log_path = sys.argv[1] if len(sys.argv) > 1 else "dist/ci/unit-tests.log"
    results_dir = sys.argv[2] if len(sys.argv) > 2 else "app/build/test-results"
    reports = annotations_from_reports(results_dir)
    if reports:
        for line in reports:
            emit(line, title="unit test failed")
        return 0
    from_log = annotations_from_log(log_path)
    if not from_log:
        # Shell steps fail with whatever the last command printed; keeping the
        # tail is what makes an unparsable failure readable at all.
        tail = []
        if os.path.isfile(log_path):
            with open(log_path, encoding="utf-8", errors="replace") as handle:
                tail = [line.strip() for line in handle if line.strip()][-8:]
        if not tail:
            tail = ["Step failed without output; no log was captured."]
        for line in tail:
            emit(line[:900], title="step output")
        return 0
    for line in from_log:
        emit(line, title="gradle")
    return 0


if __name__ == "__main__":
    sys.exit(main())
