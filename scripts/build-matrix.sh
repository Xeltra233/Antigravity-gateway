#!/usr/bin/env bash
# Cross-compile the gateway CLI for every released platform.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
OUT="${1:-/tmp/agw_matrix}"
mkdir -p "$OUT"

TARGETS=(
  "windows amd64"
  "windows arm64"
  "linux amd64"
  "linux arm64"
  "darwin amd64"
  "darwin arm64"
)

fail=0
for target in "${TARGETS[@]}"; do
  read -r goos goarch <<<"$target"
  ext=""
  [ "$goos" = "windows" ] && ext=".exe"
  out="$OUT/agw-${goos}-${goarch}${ext}"
  if GOOS="$goos" GOARCH="$goarch" CGO_ENABLED=0 go build -trimpath \
      -ldflags "-s -w" -o "$out" ./cmd/gateway; then
    printf 'OK   %-8s %-8s %s\n' "$goos" "$goarch" "$(du -h "$out" | cut -f1)"
  else
    printf 'FAIL %-8s %-8s\n' "$goos" "$goarch"
    fail=1
  fi
done

exit "$fail"