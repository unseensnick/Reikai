#!/usr/bin/env bash
# Creates the commit a nightly release hangs its tag on, and writes STAMP_SHA to $GITHUB_ENV.
#
# The releases page orders by the TAG's commit date, not the publish date. Every nightly tag used to
# point at the release bucket's single LICENSE commit, so that key tied across all of them and GitHub
# fell back to comparing tag names as strings, which sorts r1350 below r380. Each release now gets
# its own commit dated at build time, so the primary sort works and the tie-break never runs.
#
# The commit reuses the bucket's tree and hangs off it as a sibling, reachable only through its tag,
# so the bucket's own history stays a single LICENSE commit. Its message records the Reikai commit
# this nightly was built from, in the exact shape lib.sh's nightly_source_commit parses. Keep those
# two in step.
#
# A dry run reuses the bucket's own head instead of writing a commit. A draft creates no tag, so
# the stamp would date nothing and would sit there unreachable once the draft is deleted.
#
# Environment: PREVIEW_REPO, GH_TOKEN, COMMIT_COUNT, DRY_RUN.
set -eu

: "${PREVIEW_REPO:?PREVIEW_REPO is required}"
: "${COMMIT_COUNT:?COMMIT_COUNT is required}"
GITHUB_ENV="${GITHUB_ENV:-/dev/stdout}"

base=$(gh api "repos/$PREVIEW_REPO/git/ref/heads/main" --jq '.object.sha')
tree=$(gh api "repos/$PREVIEW_REPO/commits/main" --jq '.commit.tree.sha')

if [ "${DRY_RUN:-false}" = "true" ]; then
  echo "STAMP_SHA=$base" >> "$GITHUB_ENV"
  echo "Dry run: hanging the draft off $base rather than writing a stamp commit."
  exit 0
fi

bot='{"name":"github-actions[bot]","email":"41898282+github-actions[bot]@users.noreply.github.com"}'

stamp=$(jq -n \
          --arg m "nightly r${COMMIT_COUNT} ($(git rev-parse HEAD))" \
          --arg t "$tree" \
          --arg p "$base" \
          --arg d "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
          --argjson who "$bot" \
          '{message:$m, tree:$t, parents:[$p],
            author:    ($who + {date:$d}),
            committer: ($who + {date:$d})}' \
        | gh api "repos/$PREVIEW_REPO/git/commits" --input - --jq '.sha')

echo "STAMP_SHA=$stamp" >> "$GITHUB_ENV"
