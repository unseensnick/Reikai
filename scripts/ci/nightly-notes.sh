#!/usr/bin/env bash
# Builds the nightly release body and writes COMMIT_COUNT and RELEASE_BODY to $GITHUB_ENV.
#
# Nightly notes are the entries ADDED to CHANGELOG.md's [Unreleased] since the previous nightly, so
# the running list is not repeated every build. When the changelog was not touched in that range,
# they fall back to the commit subjects.
#
# Environment: PREVIEW_REPO, GH_TOKEN, REPO_URL, HEAD_SHA. Safe to run locally: without GITHUB_ENV
# the values are printed instead.
set -eu

: "${PREVIEW_REPO:?PREVIEW_REPO is required}"
: "${REPO_URL:?REPO_URL is required}"
: "${HEAD_SHA:?HEAD_SHA is required}"
GITHUB_ENV="${GITHUB_ENV:-/dev/stdout}"

# shellcheck source=scripts/ci/lib.sh
. "$(dirname "$0")/lib.sh"

commit_count=$(git rev-list --count HEAD)
echo "COMMIT_COUNT=$commit_count" >> "$GITHUB_ENV"

# Resolve the previous nightly's source commit by reading it back off that release's stamp commit,
# which records it. Arithmetic on the commit count cannot do this: counts on main and on a feature
# branch are unrelated, so a nightly following one built elsewhere would diff against a stranger
# commit that happened to sit that far back, and the notes would list the whole [Unreleased] section.
prev_ref=""
prev_tag=$(latest_nightly_tag)
if [ -n "$prev_tag" ]; then
  prev_sha=$(nightly_source_commit "$prev_tag")
  if [ -n "$prev_sha" ] && git cat-file -e "${prev_sha}^{commit}" 2>/dev/null; then
    if git merge-base --is-ancestor "$prev_sha" HEAD; then
      prev_ref="$prev_sha"
    else
      # That nightly came off another branch, so the honest base is where the two diverged.
      prev_ref=$(git merge-base "$prev_sha" HEAD || true)
    fi
  fi
fi
# A stamp with no recorded commit (or one this clone cannot see) leaves prev_ref empty, and the
# notes fall back to the whole [Unreleased] section rather than to a guess.
echo "Previous nightly ${prev_tag:-none}, diffing against ${prev_ref:-nothing}"

parse-changelog CHANGELOG.md Unreleased > /tmp/cur.txt 2>/dev/null || true
: > /tmp/prev.txt
if [ -n "$prev_ref" ]; then
  git show "$prev_ref:CHANGELOG.md" > /tmp/prevchg.md 2>/dev/null \
    && parse-changelog /tmp/prevchg.md Unreleased > /tmp/prev.txt 2>/dev/null || true
fi

new_lines=$(grep -vxF -f /tmp/prev.txt /tmp/cur.txt 2>/dev/null | sed '/^[[:space:]]*$/d' || true)
new_lines=$(printf '%s\n' "$new_lines" | headlines)

{
  echo "RELEASE_BODY<<__EOF__"
  if [ -n "$new_lines" ]; then
    echo "### New since the last nightly"
    echo ""
    echo "$new_lines"
    echo ""
    # Pin the link to the commit this nightly was built from, not to a branch. A nightly is usually
    # cut from a feature branch, so blob/main pointed at a changelog that does not contain these
    # entries, and a branch link would 404 once that branch is merged away.
    printf '**Full changelog:** %s/blob/%s/CHANGELOG.md\n' "$REPO_URL" "$HEAD_SHA"
  else
    commits=""
    if [ -n "$prev_ref" ]; then
      commits=$(git log --no-merges --format='- %s' "$prev_ref..HEAD" || true)
    fi
    if [ -n "$commits" ]; then
      echo "### Changes since the last nightly"
      echo ""
      echo "$commits"
    else
      echo "No changelog or commit changes since the last nightly."
    fi
  fi
  echo "__EOF__"
} >> "$GITHUB_ENV"
