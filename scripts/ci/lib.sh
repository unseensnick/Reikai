# Shared helpers for the CI scripts. Sourced, never run.

# Turns a CHANGELOG section into a release body: each entry reduced to its bold headline (the full
# sentences stay in CHANGELOG.md), under the "### Area" / "#### Added" headings it sits in, one
# blank line between blocks. A heading prints only above something that prints, so an empty Added
# or Changed never reaches a release. Both publishers use this, so the shape is defined once. POSIX
# awk only: CI runs mawk.
#
#   release_notes stable <section> <url>        the GitHub release, which the in-app update screen
#       also shows. Highlights and Before you upgrade pass through whole, each paragraph joined onto
#       one line so GitHub does not render the file's hard wraps as line breaks. Every other area
#       lists the first five headlines of each of its Added, Changed and Fixed lists in file order,
#       which authors keep in order of importance, then "+M more" linking <url>, the release's
#       uncapped page. The cap is per list so a long Added list cannot push every fix out. Other is left out: it has no
#       user-facing effect, and that page carries it.
#   release_notes nightly <section> <previous>  only entries whose headline <previous> lacks, so
#       editing an entry's detail sentence does not republish it. An entry with no bold (Other) is
#       known by its whole text. Nothing is capped. Prose is left out, and so are the two prose
#       areas: they describe the release, not the build
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
      if (item && last == "item") print para
      else block(para)
      if (item) last = "item"
      para = ""
      item = 0
    }
    function more() {
      if (hidden) block("+" hidden " more in the [full changelog](" url ").")
      shown = 0
      hidden = 0
    }
    has_prev && FILENAME == ARGV[1] { if ($0 ~ /^- /) seen[key($0)] = 1; next }
    /^### / {
      flush()
      more()
      area = $0
      cat = ""
      whole = (area == "### Highlights" || area == "### Before you upgrade")
      skip = has_prev ? whole : (area == "### Other")
      next
    }
    skip { next }
    /^#### / { flush(); cat = $0; shown = 0; next }
    whole && /^- / { flush(); para = $0; item = 1; next }
    /^- / {
      flush()
      k = key($0)
      if (k in seen) next
      if (!has_prev && shown == 5) { hidden++; next }
      shown++
      headings()
      if (last != "entry" && printed) print ""
      print "- " k
      printed = 1
      last = "entry"
      next
    }
    /^[ \t]*$/ { flush(); next }
    !has_prev { sub(/^[ \t]+/, ""); sub(/[ \t]+$/, ""); para = (para == "" ? $0 : para " " $0) }
    END { flush(); more() }
  '
  case "$1" in
    stable) awk -v has_prev=0 -v url="$3" "$program" "$2" ;;
    nightly) awk -v has_prev=1 "$program" "$3" "$2" ;;
    *) echo "release_notes: unknown mode '$1'" >&2; return 2 ;;
  esac
}

# Entries a Markdown-only commit in <base>..<head> introduced into [Unreleased], as "- headline"
# lines the nightly counts as already published. Such a commit ships no change, so what it adds is
# an existing entry reworded or merged (the rewrite before a cut); without this the next nightly
# would list every reworded headline as new. An entry added earlier in the same range and then
# reworded by one is left out of that nightly too. Needs parse-changelog.
reworded_entries() {
  local c before after md
  before=$(mktemp)
  after=$(mktemp)
  md=$(mktemp)
  for c in $(git rev-list --no-merges "$1..$2" -- CHANGELOG.md); do
    git diff-tree --root --no-commit-id --name-only -r "$c" | grep -qv '\.md$' && continue
    { git show "$c:CHANGELOG.md" > "$md" && parse-changelog "$md" Unreleased > "$after"; } 2>/dev/null || : > "$after"
    { git show "$c^:CHANGELOG.md" > "$md" && parse-changelog "$md" Unreleased > "$before"; } 2>/dev/null || : > "$before"
    release_notes nightly "$after" "$before" | grep '^- ' || true
  done
  rm -f "$before" "$after" "$md"
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
