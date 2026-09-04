#!/usr/bin/env bash
# Refuses a nightly that would publish nothing new. Adapted from Mihon's own preview workflow
# (mihonapp/mihon-preview 64135ef), which skips a scheduled run when no commits landed since the
# last release. Reikai has no schedule, so the same idea is pointed at what actually bites here:
# a tag that does not climb, and a merge that rebuilds a tree already published.
#
# Writes skip=true to $GITHUB_OUTPUT when the build should not run. Safe to run locally: without
# GITHUB_OUTPUT the decision is only printed.
#
# Environment: PREVIEW_REPO, GH_TOKEN, EVENT_NAME (github.event_name).
set -eu

: "${PREVIEW_REPO:?PREVIEW_REPO is required}"
EVENT_NAME="${EVENT_NAME:-workflow_dispatch}"
GITHUB_OUTPUT="${GITHUB_OUTPUT:-/dev/null}"

# shellcheck source=scripts/ci/lib.sh
. "$(dirname "$0")/lib.sh"

# Only ever written when skipping, so the build job's `!= 'true'` does not depend on which of two
# writes to the same output key wins.
skip() {
  echo "skip=true" >> "$GITHUB_OUTPUT"
  echo "::notice::Skipping nightly: $1"
  exit 0
}

# A commit carrying a release tag ships as that release; a nightly of it duplicates it.
if git tag --points-at HEAD | grep -qE '^v[0-9]'; then
  skip "$(git tag --points-at HEAD | grep -E '^v[0-9]' | head -1) is a release commit"
fi

commit_count=$(git rev-list --count HEAD)
prev_tag=$(latest_nightly_tag)
if [ -z "$prev_tag" ]; then
  echo "No previous nightly; building r${commit_count}"
  exit 0
fi

# Tags are r<commitCount> and the prune keeps the highest numbers, so a build numbered at or below
# the newest would be deleted by its own run and ignored by the in-app updater, which compares the
# tag number against the installed build's commit count.
prev_count=${prev_tag#r}
if [ "$commit_count" -le "$prev_count" ]; then
  skip "r${commit_count} would not climb above ${prev_tag}; is this branch behind the one that built it?"
fi

# Same content as the last nightly means the same APK. Compare trees, not commits: a merge into
# another branch is a new commit over an identical tree. A manual run builds anyway, since asking
# for one is deliberate.
prev_sha=$(nightly_source_commit "$prev_tag")
if [ "$EVENT_NAME" = 'push' ] && [ -n "$prev_sha" ] && git cat-file -e "${prev_sha}^{commit}" 2>/dev/null; then
  if [ "$(git rev-parse 'HEAD^{tree}')" = "$(git rev-parse "${prev_sha}^{tree}")" ]; then
    skip "tree is identical to ${prev_tag}, so the APK would be the same"
  fi
fi

echo "Building r${commit_count}, previous ${prev_tag}"
