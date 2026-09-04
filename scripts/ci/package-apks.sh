#!/usr/bin/env bash
# Renames the built APKs to their published names: reikai[-<abi>]-<tag>.apk.
#
# Usage: package-apks.sh <variant> <tag>
#   package-apks.sh release v0.4.0
#   package-apks.sh nightly r1234
#
# A release also carries the FOSS APK and writes a CHECKSUMS table to $GITHUB_ENV; a nightly ships
# neither, which is a product difference rather than an oversight.
set -eu

variant="${1:?usage: package-apks.sh <variant> <tag>}"
tag="${2:?usage: package-apks.sh <variant> <tag>}"
GITHUB_ENV="${GITHUB_ENV:-/dev/stdout}"

dir="app/build/outputs/apk/$variant"
checksums="### Checksums"$'\n\n'"| Variant | SHA-256 |"$'\n'"| ------- | ------- |"

record() {
  checksums+=$'\n'"| \`$1\` | \`$(sha256sum "$1" | awk '{ print $1 }')\` |"
}

for abi in universal arm64-v8a armeabi-v7a x86 x86_64; do
  case "$abi" in
    universal) out="reikai-${tag}.apk" ;;
    *)         out="reikai-${abi}-${tag}.apk" ;;
  esac
  mv "$dir/app-${abi}-${variant}.apk" "$out"
  record "$out"
  echo "$out"
done

if [ "$variant" = "release" ]; then
  # FOSS ships universal only, and keeps "-foss" in the name so the in-app updater picks it for
  # anyone already running that variant.
  foss_out="reikai-foss-${tag}.apk"
  mv "app/build/outputs/apk/foss/app-universal-foss.apk" "$foss_out"
  record "$foss_out"
  echo "$foss_out"

  {
    echo "CHECKSUMS<<__EOF__"
    echo "$checksums"
    echo "__EOF__"
  } >> "$GITHUB_ENV"
fi
