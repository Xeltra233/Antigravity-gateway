#!/usr/bin/env bash
# Cross-compile the JNI core for the four Android ABIs.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$(pwd)"
# Locate the NDK: explicit override, then the newest side-by-side install.
resolve_ndk() {
  if [ -n "${ANDROID_NDK_HOME:-}" ]; then echo "$ANDROID_NDK_HOME"; return; fi
  local roots=(
    "${ANDROID_NDK_ROOT:-}"
    "${ANDROID_SDK_ROOT:-}/ndk"
    "${ANDROID_HOME:-}/ndk"
    "${LOCALAPPDATA:-}/Android/Sdk/ndk"
    "$HOME/Android/Sdk/ndk"
  )
  for root in "${roots[@]}"; do
    [ -n "$root" ] && [ -d "$root" ] || continue
    # A root that already is a single NDK has a toolchains dir directly.
    if [ -d "$root/toolchains/llvm/prebuilt" ]; then echo "$root"; return; fi
    local latest
    latest="$(ls -1d "$root"/*/ 2>/dev/null | sort -V | tail -1)"
    [ -n "$latest" ] && [ -d "$latest/toolchains/llvm/prebuilt" ] && { echo "${latest%/}"; return; }
  done
}

NDK="$(resolve_ndk)"
if [ -z "$NDK" ]; then
  echo "Android NDK not found. Set ANDROID_NDK_HOME to a side-by-side NDK." >&2
  exit 1
fi

PREBUILT="$(ls -1d "$NDK"/toolchains/llvm/prebuilt/* 2>/dev/null | head -1)"
TC="$PREBUILT/bin"
echo "NDK: $NDK"
LIBS="$ROOT/android/app/src/main/jniLibs"

build_one() {
  local abi="$1" cc="$2"
  echo ">> building $abi with $cc"
  mkdir -p "$LIBS/$abi"
  ( cd "$ROOT" && \
    GOOS=android GOARCH="${GOARCH_FOR[$abi]}" GOARM="${GOARM_FOR[$abi]:-}" \
    CGO_ENABLED=1 CC="$TC/$cc" \
    go build -tags "android cgo_jni" -trimpath \
      -ldflags "-s -w -checklinkname=0" \
      -buildmode=c-shared \
      -o "$LIBS/$abi/libantigravity.so" ./cmd/androidbridge ) || return 1
  ls -l "$LIBS/$abi/libantigravity.so"
}

declare -A GOARCH_FOR=(
  [arm64-v8a]=arm64
  [armeabi-v7a]=arm
  [x86]=386
  [x86_64]=amd64
)
declare -A GOARM_FOR=(
  [armeabi-v7a]=7
)

build_one arm64-v8a   aarch64-linux-android24-clang
build_one armeabi-v7a armv7a-linux-androideabi24-clang
build_one x86         i686-linux-android24-clang
build_one x86_64      x86_64-linux-android24-clang

echo "all ABIs built"