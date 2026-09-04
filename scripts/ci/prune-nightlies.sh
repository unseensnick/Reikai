#!/usr/bin/env bash
# Deletes all but the newest nightlies from the release bucket.
#
# Keeps the highest build numbers (tags are r<commitCount>). Sorting by the numeric tag, not
# createdAt: a history-rewrite force-push can leave every release with the same createdAt, which
# made an earlier date sort delete the just-published release instead of the oldest. The list limit
# has to stay above the retention or the slice is always empty and the prune silently stops.
#
# Environment: PREVIEW_REPO, GH_TOKEN. KEEP defaults to 365.
set -eu

: "${PREVIEW_REPO:?PREVIEW_REPO is required}"
KEEP="${KEEP:-365}"
LIST_LIMIT=$((KEEP + 135))

gh release list --repo "$PREVIEW_REPO" --exclude-drafts --json tagName --limit "$LIST_LIMIT" \
  | jq -r --argjson keep "$KEEP" \
      'sort_by(.tagName | ltrimstr("r") | tonumber) | reverse | .[$keep:] | .[].tagName' \
  | while read -r tag; do
      [ -n "$tag" ] && gh release delete "$tag" --repo "$PREVIEW_REPO" --cleanup-tag --yes
    done
