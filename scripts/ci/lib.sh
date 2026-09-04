# Shared helpers for the CI scripts. Sourced, never run.

# A release note is the one-line bold headline of each CHANGELOG entry; the full sentences stay in
# CHANGELOG.md, linked from the release body. Reduces "- **Headline.** detail" to "- Headline." and
# a "**Sub-header**" line to "#### Sub-header". Both publishers use this, so the shape of a release
# note is defined once.
headlines() {
  sed -E -e 's/^- \*\*([^*]+)\*\*.*/- \1/' -e 's/^\*\*([^*]+)\*\*$/#### \1/'
}

# Reads back the Reikai commit a published nightly was built from. Every nightly tag points at a
# stamp commit whose message records it, in the shape nightly.yml writes; keep the two in step.
# Prints nothing when the release, the stamp or the commit cannot be found.
nightly_source_commit() {
  gh api "repos/$PREVIEW_REPO/commits/$1" --jq '.commit.message' 2>/dev/null \
    | sed -n 's/^\(preview\|nightly\) r[0-9]* (\([0-9a-f]\{7,40\}\))$/\2/p' | head -1
}

# Newest PUBLISHED nightly tag, empty when the bucket has none. Drafts are excluded on purpose: a
# dry run leaves one behind and gh lists drafts by default, so the guard would compare against a
# number nothing was ever released at and skip the next real build. A draft has no tag either, so
# nightly_source_commit could not resolve it and the notes would fall back to all of [Unreleased].
latest_nightly_tag() {
  gh release list --repo "$PREVIEW_REPO" --exclude-drafts --json tagName --limit 1 \
    --jq 'select(length > 0) | .[0].tagName' || true
}
