# Shared helpers for the CI scripts. Sourced, never run.

# Turns a CHANGELOG section into a release body: each entry reduced to its bold headline (the full
# sentences stay in CHANGELOG.md, linked from the body), under the "### Area" / "#### Added"
# headings it sits in, one blank line between blocks. A heading prints only above something that
# prints, so an empty Added or Changed never reaches a release. Both publishers use this, so the
# shape is defined once. POSIX awk only: CI runs mawk.
#
#   release_notes <section>              stable: every entry, plus the Highlights prose, each
#                                        paragraph joined onto one line so GitHub does not render
#                                        the file's hard wraps as line breaks
#   release_notes <section> <previous>   nightly: only entries whose headline <previous> lacks, so
#                                        editing an entry's detail sentence does not republish it.
#                                        An entry with no bold (Other) is known by its whole text.
#                                        Prose is left out: it describes the release, not the build
release_notes() {
  local program='
    function key(line) {
      if (match(line, /^- \*\*[^*]+\*\*/)) return substr(line, 5, RLENGTH - 6)
      return substr(line, 3)
    }
    function block(text) {
      if (printed) print ""
      print text
      printed = 1
      last = "block"
    }
    function headings() {
      if (area != shown_area) { if (area != "") block(area); shown_area = area; shown_cat = "" }
      if (cat != "" && cat != shown_cat) { block(cat); shown_cat = cat }
    }
    function flush() {
      if (para == "") return
      headings()
      block(para)
      para = ""
    }
    has_prev && FILENAME == ARGV[1] { if ($0 ~ /^- /) seen[key($0)] = 1; next }
    /^### / { flush(); area = $0; cat = ""; next }
    /^#### / { flush(); cat = $0; next }
    /^- / {
      flush()
      k = key($0)
      if (k in seen) next
      headings()
      if (last != "entry" && printed) print ""
      print "- " k
      printed = 1
      last = "entry"
      next
    }
    /^[ \t]*$/ { flush(); next }
    !has_prev { sub(/^[ \t]+/, ""); sub(/[ \t]+$/, ""); para = (para == "" ? $0 : para " " $0) }
    END { flush() }
  '
  if [ $# -ge 2 ]; then
    awk -v has_prev=1 "$program" "$2" "$1"
  else
    awk -v has_prev=0 "$program" "$1"
  fi
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
