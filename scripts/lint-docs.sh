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
#   lint-docs.sh manifest-rows <file>            every off-path row still describes reality
#   lint-docs.sh key-files <file>...             every path a plan record's Key files names exists
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

  *)
    echo "lint-docs.sh: unknown check '$cmd'" >&2
    exit 2
    ;;
esac
