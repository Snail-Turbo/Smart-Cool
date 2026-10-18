#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/smartcool-keepalive.zip"

rm -f "$OUT"
(cd "$HERE/smartcool_keepalive" && zip -r "$OUT" .)
echo "$OUT"
