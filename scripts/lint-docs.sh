#!/usr/bin/env bash
# The doc and comment conventions from .claude/rules/workflow.md ("Public-facing naming", "After
# completing any code change") and code-quality.md, in one place.
#
# Both .githooks/pre-commit and .github/workflows/docs-lint.yml call this, because they enforce the
# same rules on different content: the hook checks what you staged, CI checks the whole tree. Each
# subcommand therefore takes the content to scan, and the caller decides what that is.
#
# Usage:
#   lint-docs.sh source-names <file> <label>     no content-source names
#   lint-docs.sh changelog-entries <file>        [Unreleased] headline shape and length cap
#   lint-docs.sh em-dash <file> <label>          no em dash
#   lint-docs.sh issue-refs <file> <label> <hint>   no bare #N
#   lint-docs.sh codenames                       plan/roadmap codenames, candidate lines on stdin
#   lint-docs.sh twin-pins [--stdin|--tree]      a twin/mirrors comment names its pin
#   lint-docs.sh manifest-rows <file>            every off-path row still describes reality
#   lint-docs.sh key-files <file>...             every path a plan record's Key files names exists
#   lint-docs.sh kdoc-links [--stdin|--tree]     a [Symbol] in a comment names live code
#   lint-docs.sh history-words                   comments narrating history, diff on stdin (warns)
#   lint-docs.sh subsystem-docs [<file>...]      docs/dev/subsystems/ pages carry no history
#
# Exits non-zero when a check fails. Under GitHub Actions the heading is emitted as ::error:: so it
# lands as an annotation; locally it is printed plainly.
set -u

# Content-source names (adult and mainstream) that must be genericized or use an approved shorthand
# (EH / ExH / MD / CMK) in the public-facing docs. Trackers (MangaUpdates, Shikimori, AniList, ...)
# are NOT sources and stay allowed. Extend this list as new sources get named in the repo.
DENY='e-hentai|ehentai|exhentai|nhentai|pururin|8muses|hentaifox|asmhentai|koharu|schalenetwork|mangadex|comick'

# A bare '#N' auto-links to a Reikai issue. A '#' + digits not preceded by an alphanumeric; the
# owner/repo#N form is fine.
BARE_ISSUE_REF='(^|[^A-Za-z0-9])#[0-9]'

# A comment line: //, a block-comment opener, a KDoc *-line, or a SQL -- line.
COMMENT_LINE='(//|/\*|^\+?[[:space:]]*\*|^\+?[[:space:]]*--)'

# Plan and roadmap codenames, which rot as the plan moves on. Caught: Phase N, Stage N, the P<phase> /
# P5 S5 shorthand, Y<n> Yokai-era refs and R<n> roadmap refs of any length (Y11, R12), the R-feature /
# Y-feature tags, Active #N, and in-words "Roadmap N" / "Roadmap:" (the bare document name ROADMAP.md
# stays allowed). Spared: R8 (the code shrinker, so a one-digit R skips 8), M3 (Material 3, M is not
# matched) and Y2K (no word boundary follows its digit).
CODENAME='(Phase[[:space:]]*[0-9]|\b[Ss]tage[[:space:]]*[0-9]|Active[[:space:]]*#[0-9]|\b[PY][0-9]+[a-z]?\b|\bR([0-79]|[0-9]{2,})[a-z]?\b|\b[RY]-feature\b|\b[Ss]tep[[:space:]]*[0-9]|[Rr]oadmap[[:space:]]*(#?[0-9]|:))'

# A colon-led algorithm step ("Step 1:", "Stage 1:") is fine; a plan-style "Step 3" is not. The step match is
# case-insensitive because a lower-case "step 2" reached main once. Two Kotlin range shapes quoted
# in comments are spared too: "2..20 step 6" and a fractional "step 0.5".
CODENAME_SPARED='(\b[Ss]t(ep|age)[[:space:]]*[0-9]:|[0-9]\.\.[0-9]+[[:space:]]+step[[:space:]]|[Ss]tep[[:space:]]*[0-9]+\.[0-9])'

# Comment words that narrate history, which git and the plan docs already hold (a warning, not a block).
HISTORY_WORDS="used to (be|sit|have|always)|previously|was found by|found by an audit|owner('s)? ruling|\\(owner"

# What a docs/dev/subsystems/ page must not carry: it describes the subsystem as it is, so dates,
# commit SHAs, plan steps, rounds, phases, rulings and a Status section belong in docs/dev/plans/.
SUBSYSTEM_HISTORY='\b20[0-9]{2}-[0-9]{2}-[0-9]{2}\b|`[0-9a-f]{7,40}`|\bstep[[:space:]]+[0-9]|\bround[[:space:]]+(one|two|[0-9])|\bphase[[:space:]]+[0-9]|\(owner,|^#+[[:space:]]+status\b'
SUBSYSTEM_MAX_LINES=300

# Shared awk prologue for the checks that read either a unified diff (git diff -U0, the hook's feed) or
# whole files (tree=1). It sets `file`, `line` (without the diff's '+') and `where` (path:line) for each
# added or scanned line, and calls the program's own boundary() wherever a run of lines is broken.
DIFF_WALK='
  tree && FNR == 1 { boundary() }
  !tree && /^\+\+\+ / { boundary(); file = $0; sub(/^\+\+\+ (b\/)?/, "", file); next }
  !tree && /^@@/ { boundary(); ln = $0; sub(/^@@ -[0-9,]+ \+/, "", ln); sub(/[ ,].*/, "", ln); ln += 0; next }
  !tree && !/^\+/ { boundary(); next }
  { if (tree) { line = $0; file = FILENAME; where = FILENAME ":" FNR } else { line = substr($0, 2); where = file ":" ln; ln++ } }'

# The comment and the code part of one Kotlin line, by the same heuristic as COMMENT_LINE: a line
# opening with //, /* or * is all comment, otherwise a // starts one.
COMMENT_SPLIT='
  function comment_of(l,  s) {
    s = l; sub(/^[ \t]+/, "", s)
    if (s ~ /^(\/\/|\/\*|\*)/) return s
    return index(s, "//") > 0 ? substr(s, index(s, "//")) : ""
  }
  function code_of(l,  s) {
    s = l; sub(/^[ \t]+/, "", s)
    if (s ~ /^(\/\/|\/\*|\*)/) return ""
    return index(s, "//") > 0 ? substr(s, 1, index(s, "//") - 1) : s
  }'

report() {
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::error::$1"
  else
    echo "$1"
  fi
  shift
  printf '%s\n' "$@"
}

cmd="${1:?usage: lint-docs.sh <check> [args]}"
shift

case "$cmd" in
  source-names)
    file="${1:?file}"; label="${2:?label}"
    if hits=$(grep -inE "$DENY" "$file"); then
      report "$label names a content source; genericize it or use an approved shorthand (EH / ExH / MD / CMK). See Public-facing naming." "$hits"
      exit 1
    fi
    ;;

  changelog-entries)
    file="${1:?file}"
    issues=$(awk '
      /^## \[Unreleased\]/ { u=1; next }
      /^## \[/             { u=0 }
      !u                  { next }
      /^### /             { sec=$0; sub(/^### /,"",sec); next }
      /^\*\*/             { next }
      /^- / {
        if (length($0) > 320)
          print "  - over the length cap (>320 chars); trim to a bold headline + one sentence:\n    " $0
        if (sec != "Other" && $0 !~ /^- \*\*[^*]+[.!?]\*\*/)
          print "  - needs a self-contained bold headline ending in . ! or ? (Other is exempt):\n    " $0
      }
    ' "$file")
    if [ -n "$issues" ]; then
      report "CHANGELOG [Unreleased] entry issues:" "$issues"
      exit 1
    fi
    ;;

  em-dash)
    file="${1:?file}"; label="${2:?label}"
    if hits=$(grep -n '—' "$file"); then
      report "$label contains an em dash; use commas, parentheses, periods or colons." "$hits"
      exit 1
    fi
    ;;

  issue-refs)
    file="${1:?file}"; label="${2:?label}"; hint="${3:?hint}"
    if hits=$(grep -nE "$BARE_ISSUE_REF" "$file"); then
      report "$label has a bare '#N', which auto-links to a Reikai issue; use $hint." "$hits"
      exit 1
    fi
    ;;

  codenames)
    # --stdin reads candidate lines (the hook pipes the lines a commit adds); --tree scans every
    # tracked source file. Both apply the same three patterns, which is the point of sharing this.
    #
    # A leading '+' from a diff is tolerated. In --tree mode the patterns go to git grep rather than
    # to a pipe, so they match file content and never the "path:line:" prefix git prints after.
    mode="${1:---stdin}"
    if [ "$mode" = "--tree" ]; then
      hits=$(git grep -nE -e "$COMMENT_LINE" --and -e "$CODENAME" --and --not -e "$CODENAME_SPARED" \
        -- '*.kt' '*.kts' '*.sq' '*.sqm' || true)
    else
      hits=$(grep -E "$COMMENT_LINE" | grep -E "$CODENAME" | grep -vE "$CODENAME_SPARED" || true)
    fi
    if [ -n "$hits" ]; then
      report "a code comment carries a plan/roadmap codename marker (Phase N, Stage N, P5 S5, Y3, R12, R-feature, Active #N, Roadmap N, plan Step N); state the durable fact instead. See code-quality.md." "$hits"
      exit 1
    fi
    ;;

  twin-pins)
    # content-layer.md: a "twin of" / "mirrors" comment claims two halves must behave alike, so it names
    # what pins them in one fixed spelling: "pinned by <kernel|capability|...Test>", "type only" for a
    # data shape with no rule, or an explicit "no pin: <reason>". A comment block is a run of
    # consecutive comment lines, so a pin on the line below the marker counts.
    #
    # --stdin reads a unified diff (git diff -U0) and checks the blocks its added lines form, which is
    # the hook's feed and fails. --tree scans every tracked .kt/.kts and only reports, because the
    # markers written before the spelling was fixed are paid off as their files are next touched.
    mode="${1:---stdin}"
    twin_awk='
      function flush() {
        t = tolower(text)
        if (n > 0 && t ~ /twin of|mirrors (the )?(manga|novel|mihon)|as manga does/ && t !~ /pinned by|type only|no pin:/)
          print loc ": " first
        n = 0; text = ""
      }
      function boundary() { flush() }
      '"$DIFF_WALK"'
      {
        s = line; sub(/^[ \t]+/, "", s)
        if (s ~ /^(\/\/|\/\*|\*)/) { sub(/^(\/+|\/\*+|\*+)[ \t]*/, "", s) }
        else if (index(s, "//") > 0) { s = substr(s, index(s, "//") + 2) }
        else { flush(); next }
        sub(/\*\/[ \t]*$/, "", s)
        if (n == 0) { loc = where; first = line }
        n++; text = text " " s
      }
      END { flush() }'
    if [ "$mode" = "--tree" ]; then
      hits=$(git ls-files -z -- '*.kt' '*.kts' | xargs -0 awk -v tree=1 "$twin_awk" || true)
      if [ -n "$hits" ]; then
        echo "twin-pins: $(printf '%s\n' "$hits" | wc -l | tr -d ' ') twin/mirrors comment(s) name no pin (report only; pay each off when its file is next touched):"
        printf '%s\n' "$hits"
      fi
    else
      hits=$(awk -v tree=0 "$twin_awk")
      if [ -n "$hits" ]; then
        report "a twin/mirrors comment names no pin; write 'twin of X, pinned by Y' (a kernel, a capability or a ...Test), 'twin of X, type only', or 'no pin: <reason>'. See content-layer.md." "$hits"
        exit 1
      fi
    fi
    ;;

  manifest-rows)
    # The manifest only works if every row is real and no listed file comes back. Both failures are
    # otherwise silent: a resurrected file gives two implementations of one surface, and a row
    # pointing nowhere protects nothing.
    file="${1:?file}"
    # A tree with no manifest has nothing to protect, which is the case on a branch cut before the
    # delete-and-manifest policy existed. Absent is not a violation; an empty one is, below.
    if [ ! -f "$file" ]; then
      echo "off-path manifest: $file does not exist here, nothing to check."
      exit 0
    fi
    fail=0
    # Data rows look like `| <path> | <upstream> | <replacement> |`, the shape off-path-check.ps1 parses.
    rows=$(grep -E '^\|[[:space:]]*[a-z0-9-]+/' "$file" || true)
    if [ -z "$rows" ]; then
      report "off-path manifest has no machine-readable rows, so the sync check would silently pass."
      exit 1
    fi
    while IFS='|' read -r _ path _ repl _; do
      path=$(echo "$path" | tr -d ' ')
      repl=$(echo "$repl" | tr -d ' ')
      [ -z "$path" ] && continue
      if [ -e "$path" ]; then
        report "off-path manifest: '$path' is listed as deleted but exists in the tree." \
          "  Either it was resurrected (delete it, its twin renders the surface), or it is live again" \
          "  (drop its manifest row and say why in the commit)."
        fail=1
      fi
      [ -z "$repl" ] && continue
      found=0
      for root in app/src/main/java domain/src/main/java data/src/main/java core/common/src/main/kotlin ""; do
        [ -e "${root:+$root/}$repl" ] && found=1 && break
      done
      if [ "$found" -eq 0 ]; then
        report "off-path manifest: replacement '$repl' does not exist, so the row protects nothing."
        fail=1
      fi
    done <<< "$rows"
    [ "$fail" -eq 0 ] || exit 1
    ;;

  key-files)
    # A plan record's Key files list is what a reader opens first, so a path in it that no longer
    # exists sends them nowhere while reading as current (roadmap-plans.md). Checked: each backticked
    # path and each markdown link target in the "## Key files" section. A link resolves from the
    # record's own directory. A backticked path passes when it exists from the repo root or a tracked
    # path ends with it, so a bare file name or a path relative to a source root is enough, and an
    # elided ".../x/y.kt" is matched on what follows the dots. A token that is no path and no file name
    # (a symbol, a pref key, a branch) is not checked, and a line saying the file was deleted or
    # manifested is history, so it is skipped.
    tracked=$(mktemp); refs=$(mktemp)
    git ls-files > "$tracked"
    # is_tracked <path>: some tracked path is it, ends with /it, or sits under it as a directory.
    is_tracked() {
      awk -v p="$1" '
        BEGIN { n = length(p) }
        $0 == p || substr($0, length($0) - n) == "/" p || index($0, p "/") == 1 || index($0, "/" p "/") { f = 1; exit }
        END { exit !f }' "$tracked"
    }
    fail=0
    for file in "$@"; do
      [ -f "$file" ] || continue
      dir=$(dirname "$file")
      # A line "In `../Other-repo`:" roots the bullets after it in that sibling repo, until "In this
      # repo". There only each bullet's leading path is read, since the prose around it names selectors
      # and versions in backticks too.
      awk '/^## Key files/ { on = 1; next } /^## / { on = 0 } on' "$file" \
        | grep -viE 'deleted|manifested|no longer exist' \
        | { sibling=0; while IFS= read -r line; do
            case "$line" in
              'In `../'*) r=${line#In \`}; echo "ROOT ${r%%\`*}"; sibling=1; continue ;;
              'In this repo'*) echo "ROOT ."; sibling=0; continue ;;
            esac
            if [ "$sibling" = 1 ]; then
              printf '%s\n' "$line" | grep -oE '^- `[^`]+`' | cut -c3-
            else
              printf '%s\n' "$line" | grep -oE '`[^`]+`|\]\([^)]+\)'
            fi
          done; } > "$refs" || true
      missing=""; root=.
      while IFS= read -r ref; do
        case "$ref" in 'ROOT '*) root=${ref#ROOT }; continue ;; esac
        if [ "$root" != . ]; then
          # A sibling repo is checked where it is cloned, and skipped where it is not (CI).
          p=${ref:1:${#ref}-2}
          [ -d "$root" ] && [ "${ref:0:1}" = '`' ] && [[ "$p" == */* || "$p" == *.* ]] \
            && [[ "$p" != *' '* && "$p" != *...* ]] && [ ! -e "$root/$p" ] && missing="$missing  $root/$p"$'\n'
          continue
        fi
        if [ "${ref:0:1}" = '`' ]; then
          p=${ref:1:${#ref}-2}; kind=code
        else
          p=${ref:2:${#ref}-3}; p=${p%%#*}; kind=link
        fi
        case "$p" in ''|*' '*|*'*'*|*'<'*|*'{'*|*'#'*|http*|../refs/*|refs/*) continue ;; esac
        if [ "$kind" = link ]; then
          [ -e "$dir/$p" ] || missing="$missing  $p"$'\n'
          continue
        fi
        case "$p" in
          */*|*.kt|*.kts|*.sq|*.sqm|*.md|*.sh|*.ps1|*.yml|*.json|*.xml|*.proto|*.toml) ;;
          *) continue ;;
        esac
        case "$p" in .*/*) ;; .*|*...) continue ;; esac
        [ -e "$p" ] && continue
        q=${p##*.../}; q=${q%/}
        is_tracked "$q" && continue
        # A class or member named by its package path (reikai/domain/entry/EntryId, X.member).
        base=${q##*/}
        case "$base" in
          *.*) is_tracked "${q%.*}.kt" && continue ;;
          *) is_tracked "$q.kt" && continue ;;
        esac
        # An elided directory (".../yokai-y2k/app", the checkout itself) cannot be judged by its tail.
        case "$p" in .../*) [[ "${q##*/}" != *.* ]] && continue ;; esac
        # A branch named as a port source (design/library-compose).
        git show-ref -q "$p" 2>/dev/null && continue
        missing="$missing  $p"$'\n'
      done < "$refs"
      if [ -n "$missing" ]; then
        report "$file: a Key files path does not exist; point it at the live file or drop it." "${missing%$'\n'}"
        fail=1
      fi
    done
    rm -f "$tracked" "$refs"
    [ "$fail" -eq 0 ] || exit 1
    ;;

  kdoc-links)
    # A [Symbol] in a comment names code, and one whose symbol was renamed or deleted reads as current
    # while pointing nowhere. A reference resolves when its last dotted segment appears in non-comment
    # Kotlin anywhere in the tree (main and test, any module), which also covers a documented
    # function's own parameter names, since a signature is code. In --stdin mode the added code lines
    # count too, so a symbol the same diff declares resolves. Skipped: [text](url) links, the label of
    # [label][Symbol] (the symbol is checked), and an index such as list[i], whose '[' follows a name.
    # Scope: Kotlin under app/src, domain/src, data/src, core and source-api. --stdin reads a unified
    # diff and fails; --tree reports only.
    mode="${1:---stdin}"
    ids=$(mktemp)
    git ls-files -z -- '*.kt' '*.kts' | xargs -0 awk "$COMMENT_SPLIT"'
      { c = code_of($0); while (match(c, /[A-Za-z_][A-Za-z0-9_]*/)) { seen[substr(c, RSTART, RLENGTH)] = 1; c = substr(c, RSTART + RLENGTH) } }
      END { for (k in seen) print k }' | sort -u > "$ids"
    kdoc_awk="$COMMENT_SPLIT"'
      BEGIN { while ((getline t < ENVIRON["KDOC_IDS"]) > 0) ids[t] = 1 }
      function boundary() {}
      '"$DIFF_WALK"'
      {
        c = code_of(line)
        while (match(c, /[A-Za-z_][A-Za-z0-9_]*/)) { ids[substr(c, RSTART, RLENGTH)] = 1; c = substr(c, RSTART + RLENGTH) }
        if (file !~ /^(app\/src|domain\/src|data\/src|core|source-api)\/.*\.kt$/) next
        t = comment_of(line)
        while (match(t, /\[[A-Za-z_][A-Za-z0-9_.]*\]/)) {
          pre = RSTART > 1 ? substr(t, RSTART - 1, 1) : ""
          post = substr(t, RSTART + RLENGTH, 1)
          name = substr(t, RSTART + 1, RLENGTH - 2)
          t = substr(t, RSTART + RLENGTH)
          if (pre ~ /[A-Za-z0-9_)>]/ || post == "(" || post == "[") continue
          sub(/\.+$/, "", name); n = split(name, part, ".")
          nr++; ref[nr] = part[n]; at[nr] = where; whole[nr] = name
        }
      }
      END { for (i = 1; i <= nr; i++) if (!(ref[i] in ids)) print at[i] ": [" whole[i] "]" }'
    if [ "$mode" = "--tree" ]; then
      hits=$(git ls-files -z -- 'app/src/*.kt' 'domain/src/*.kt' 'data/src/*.kt' 'core/*.kt' 'source-api/*.kt' \
        | KDOC_IDS="$ids" xargs -0 awk -v tree=1 "$kdoc_awk" || true)
      rm -f "$ids"
      if [ -n "$hits" ]; then
        echo "kdoc-links: $(printf '%s\n' "$hits" | wc -l | tr -d ' ') comment reference(s) name no symbol in the code (report only):"
        printf '%s\n' "$hits"
      fi
    else
      hits=$(KDOC_IDS="$ids" awk -v tree=0 "$kdoc_awk")
      rm -f "$ids"
      if [ -n "$hits" ]; then
        report "a comment's [Symbol] names nothing in the code; point it at the live symbol, or drop the brackets if it names no symbol." "$hits"
        exit 1
      fi
    fi
    ;;

  history-words)
    # A comment states the durable fact; how it came to be (what something used to do, who ruled, which
    # audit found it) is history that git and the feature's plan doc already hold. Read on the lines a
    # unified diff adds: comments under reikai/ or exh/, and RK lines in Mihon's files. Warns, never
    # blocks, since a word like "previously" is sometimes the plain fact.
    hits=$(HIST="$HISTORY_WORDS" awk -v tree=0 "$COMMENT_SPLIT"'
      function boundary() {}
      '"$DIFF_WALK"'
      {
        c = comment_of(line)
        if (c == "") next
        if ((file ~ /(^|\/)(reikai|exh)\// || c ~ /\/\/[ \t]*RK([^A-Za-z0-9_]|$)/) && tolower(c) ~ ENVIRON["HIST"])
          print where ": " line
      }')
    if [ -n "$hits" ]; then
      echo "history-words (warning): a comment narrates history; state the fact as it stands and leave"
      echo "how it got there to git and the plan doc. See code-quality.md \"Comments\"."
      printf '%s\n' "$hits"
    fi
    ;;

  subsystem-docs)
    # A docs/dev/subsystems/ page describes a subsystem as it is now, so anything that dates it is
    # rejected (SUBSYSTEM_HISTORY), and so is a page past SUBSYSTEM_MAX_LINES, which has stopped being
    # an overview. With no arguments every file under the folder is checked; absent is not a violation.
    files=("$@")
    if [ "${#files[@]}" -eq 0 ]; then
      if [ ! -d docs/dev/subsystems ]; then
        echo "subsystem-docs: docs/dev/subsystems does not exist here, nothing to check."
        exit 0
      fi
      mapfile -t files < <(find docs/dev/subsystems -type f)
    fi
    fail=0
    for file in "${files[@]}"; do
      [ -f "$file" ] || continue
      if hits=$(grep -niE "$SUBSYSTEM_HISTORY" "$file"); then
        report "$file carries history (a date, a commit SHA, a step, round or phase, an owner ruling, or a Status section); keep it in docs/dev/plans/ and describe what is." "$hits"
        fail=1
      fi
      lines=$(awk 'END { print NR }' "$file")
      if [ "$lines" -gt "$SUBSYSTEM_MAX_LINES" ]; then
        report "$file runs to $lines lines, past the cap of $SUBSYSTEM_MAX_LINES; split it by component."
        fail=1
      fi
    done
    [ "$fail" -eq 0 ] || exit 1
    ;;

  *)
    echo "lint-docs.sh: unknown check '$cmd'" >&2
    exit 2
    ;;
esac
