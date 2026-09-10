#!/usr/bin/env python3
"""Re-apply this alt-line's only delta to .github/workflows/build-apk.yml.

This fork = upstream main (logicflow-GYW/RikkaMinis) + exactly ONE delta: the
build injects MINIS_APP_ID_OVERRIDE=com.rikkaminis.app.lab, so the APK installs
side by side with the stable app (own data dir, own sub-process names, own
launcher label) instead of overwriting it.

Upstream owns build-apk.yml and keeps editing it, so the delta cannot survive as
a merged hunk — .github/workflows/sync-fork-main.yml takes upstream's version of
the file and calls this script to re-synthesise the delta on top.

Idempotent: a file that already carries both parts is left byte-identical.
Anchoring is a regex on the version echo (not an exact line) because upstream
edits its own text freely — upstream appending to that echo must not break the
delta. Only if the echo disappears entirely does this fail loudly, on purpose: a
silently delta-less build would still install over the stable app, and nothing
else in CI would notice.
"""

import pathlib
import re
import sys

PATH = pathlib.Path(".github/workflows/build-apk.yml")
MARKER = "MINIS_APP_ID_OVERRIDE=com.rikkaminis.app.lab"
APP_ID_ANNOTATION = "appId=com.rikkaminis.app.lab"

# The staging step's version echo. Groups: indent, any upstream-added tail, quote.
ECHO_RE = re.compile(
    r'^(?P<indent>[ \t]*)echo "versionCode=\$VC[ ]+versionNameSuffix=\+\$GITHUB_RUN_NUMBER'
    r'(?P<tail>[^"]*)"[ \t]*\n$'
)
ENV_LINE = '          echo "MINIS_APP_ID_OVERRIDE=com.rikkaminis.app.lab" >> "$GITHUB_ENV"\n'
COMMENT = [
    "          # [dual-appid] Alt-account (lab) builds ship the experimental identity",
    "          # so they install CO-EXISTING with the stable main-account app",
    "          # (applicationId com.rikkaminis.app.lab) instead of overwriting it.",
    "          # This is the only delta this fork carries; everything else tracks",
    "          # logicflow-GYW/RikkaMinis main verbatim.",
]


def main() -> int:
    if not PATH.exists():
        print(f"::error::{PATH} not found", file=sys.stderr)
        return 1

    raw = PATH.read_text()
    has_env = MARKER in raw
    has_annotation = APP_ID_ANNOTATION in raw
    if has_env and has_annotation:
        print("dual-appid delta already present — no change")
        return 0

    out: list[str] = []
    anchored = False
    for line in raw.splitlines(keepends=True):
        m = ECHO_RE.match(line)
        if not m:
            out.append(line)
            continue
        anchored = True
        if not has_env:
            out.extend(c + "\n" for c in COMMENT)
            out.append(ENV_LINE)
        tail = m.group("tail")
        if APP_ID_ANNOTATION not in tail:
            tail = f"{tail}  {APP_ID_ANNOTATION}"
        out.append(
            f'{m.group("indent")}echo "versionCode=$VC  versionNameSuffix=+$GITHUB_RUN_NUMBER{tail}"\n'
        )

    if not anchored:
        print(
            "::error::the version echo line is gone from .github/workflows/build-apk.yml — "
            "upstream refactored the staging step; re-anchor the dual-appid delta by hand",
            file=sys.stderr,
        )
        return 1

    PATH.write_text("".join(out))
    print("dual-appid delta applied")
    return 0


if __name__ == "__main__":
    sys.exit(main())
