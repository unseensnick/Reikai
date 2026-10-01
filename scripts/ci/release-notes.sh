#!/usr/bin/env bash
# Builds the stable release body and writes it to $GITHUB_ENV as RELEASE_BODY.
#
# Notes come ONLY from the matching [version] section, cut by renaming [Unreleased] to [version].
# No [Unreleased] fallback, or nightly-only changes would leak into a stable release.
#
# Environment: VERSION_TAG, REPO_URL, MESSAGE (optional). Safe to run locally: without GITHUB_ENV
# the body is printed instead.
set -eu

: "${VERSION_TAG:?VERSION_TAG is required}"
: "${REPO_URL:?REPO_URL is required}"
MESSAGE="${MESSAGE:-}"
GITHUB_ENV="${GITHUB_ENV:-/dev/stdout}"

# shellcheck source=scripts/ci/lib.sh
. "$(dirname "$0")/lib.sh"

version="${VERSION_TAG#v}"
if ! changelog="$(parse-changelog CHANGELOG.md "$version" 2>/dev/null)"; then
  echo "::error::CHANGELOG.md has no [$version] section. Rename [Unreleased] to [$version] (and add a fresh empty [Unreleased]) before cutting v$version."
  exit 1
fi

# The body is capped per area, so it links the release's page on reikai.app, which renders this same
# CHANGELOG section uncapped (Reikai-website's sync-changelogs.mjs). The page appears once the site
# rebuilds for the published release (site-release.yml).
page="https://reikai.app/changelogs/$version"

section_file=$(mktemp)
printf '%s\n' "$changelog" > "$section_file"
notes="$(release_notes stable "$section_file" "$page")"
rm -f "$section_file"

# Previous tag (linear main), for a "changes since" compare link. Empty on the first release.
prev="$(git describe --tags --abbrev=0 "${VERSION_TAG}^" 2>/dev/null || true)"

{
  echo "RELEASE_BODY<<__EOF__"
  if [ -n "$MESSAGE" ]; then
    printf '%s\n\n' "$MESSAGE"
  fi
  printf '%s\n\n' "$notes"
  printf '**Full changelog:** %s\n' "$page"
  if [ -n "$prev" ]; then
    printf '**Changes since %s:** %s/compare/%s...%s\n' "$prev" "$REPO_URL" "$prev" "$VERSION_TAG"
  fi
  echo "__EOF__"
} >> "$GITHUB_ENV"
