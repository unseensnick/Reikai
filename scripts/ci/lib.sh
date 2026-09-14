# Shared helpers for the CI scripts. Sourced, never run.

# A release note is the one-line bold headline of each CHANGELOG entry; the full sentences stay in
# CHANGELOG.md, linked from the release body. Reduces "- **Headline.** detail" to "- Headline.".
# Headings pass through untouched, so a version section's own "### Area" / "#### Added" nesting is
# the nesting of the release body. Both publishers use this, so the shape is defined once.
headlines() {
  sed -E 's/^- \*\*([^*]+)\*\*.*/- \1/'
}

# The [Unreleased] entries in <current> whose headline is not in <previous>, as release-note lines
# under the "### Area" / "#### Added" headings they sit in. An entry is known by its bold headline,
# so editing its trailing sentence does not republish it; an entry with no bold (Other) is known by
# its whole text. Headings print only above an entry that prints, and prose such as Highlights never
# does, since it describes the whole release rather than one nightly. POSIX awk only: CI runs mawk.
new_changelog_entries() {
  awk '
    function key(line) {
      if (match(line, /^- \*\*[^*]+\*\*/)) return substr(line, 5, RLENGTH - 6)
      return substr(line, 3)
    }
    function heading(text) {
      if (printed) print ""
      print text
      printed = 1
      last = "heading"
    }
    FILENAME == ARGV[1] { if ($0 ~ /^- /) seen[key($0)] = 1; next }
    /^### / { area = $0; cat = ""; next }
    /^#### / { cat = $0; next }
    /^- / {
      k = key($0)
      if (k in seen) next
      if (area != shown_area) { heading(area); shown_area = area; shown_cat = "" }
      if (cat != "" && cat != shown_cat) { heading(cat); shown_cat = cat }
      if (last == "heading") print ""
      print "- " k
      printed = 1
      last = "entry"
    }
  ' "$1" "$2"
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
