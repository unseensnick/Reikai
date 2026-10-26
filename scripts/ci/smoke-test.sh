#!/usr/bin/env bash
# Installs a minified APK on a running emulator, takes it through first-run onboarding and every
# bottom tab, and fails if the app crashes or dies on the way. R8-only crashes never show in the
# unminified debug build, so this is the one place a missing keep surfaces before a tester hits it.
#
# Usage: scripts/ci/smoke-test.sh <apk> [package]. ANDROID_SERIAL picks the device. Screenshots of
# each step land in $SMOKE_OUT (default smoke-out), with the crash buffer when a step fails.
set -euo pipefail

apk="${1:?apk}"
pkg="${2:-app.reikai.debug}"
out="${SMOKE_OUT:-smoke-out}"
mkdir -p "$out"
# Git Bash on Windows rewrites /sdcard paths into Windows ones unless told not to.
export MSYS_NO_PATHCONV=1

step=0

fail() {
  echo "::error::$1"
  adb exec-out screencap -p > "$out/failure.png" || true
  adb logcat -d -b crash > "$out/crash.txt" || true
  adb logcat -d > "$out/logcat.txt" || true
  exit 1
}

alive() {
  if adb logcat -d -b crash | grep -q "Process: $pkg,"; then fail "$pkg crashed"; fi
  if ! adb shell pidof "$pkg" > /dev/null; then fail "$pkg is not running"; fi
}

shot() {
  step=$((step + 1))
  adb exec-out screencap -p > "$out/$(printf '%02d' "$step")-$1.png"
}

# The bounds of the first node whose text or description is $1, ignoring case. Compose exposes a
# label as a text node inside its button, and tapping the label's centre lands on the button.
bounds_of() {
  adb shell uiautomator dump /sdcard/smoke.xml > /dev/null 2>&1 || return 0
  adb exec-out cat /sdcard/smoke.xml | tr '>' '\n' |
    grep -i -E "(text|content-desc)=\"$1\"" |
    grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -n 1 || true
}

# Waits up to 30 s for $1 to show, since a screen may still be composing, then taps it.
tap() {
  local bounds x1 y1 x2 y2
  for _ in $(seq 1 30); do
    alive
    bounds=$(bounds_of "$1")
    if [ -n "$bounds" ]; then
      read -r x1 y1 x2 y2 <<< "$(grep -o '[0-9]*' <<< "$bounds" | tr '\n' ' ')"
      adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
      echo "tapped $1"
      sleep 2
      return
    fi
    sleep 1
  done
  fail "\"$1\" never showed"
}

adb logcat -b crash -c
adb install -r "$apk"
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null
sleep 5
alive
shot launched

# First run: theme, storage folder, permissions, guides.
tap "Next"
tap "Select a folder"
# The system picker refuses the storage root, so pick a folder inside it first.
tap "Documents"
tap "Use this folder"
tap "Allow"
shot storage-picked
tap "Next"
tap "Next"
tap "Get started"
shot onboarded

for tab in Library Updates History Browse More; do
  tap "$tab"
  shot "$tab"
done
tap "Settings"
shot settings

alive
echo "Smoke test passed: $pkg survived onboarding and every tab."
