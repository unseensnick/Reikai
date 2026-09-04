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

# Plan and roadmap codenames, which rot as the plan moves on. Caught: Phase N, the P<phase> / P5 S5
# shorthand, Y<n> Yokai-era refs, R<n> roadmap refs, Active #N, and in-words "Roadmap N" /
# "Roadmap:" (the bare document name ROADMAP.md stays allowed). Spared below the line: R8 (the code
# shrinker, so R uses [0-79]) and M3 (Material 3, M is not matched).
CODENAME='(Phase[[:space:]]*[0-9]|Active[[:space:]]*#[0-9]|\b[PY][0-9][a-z]?\b|\bR[0-79][a-z]?\b|\b[Ss]tep[[:space:]]*[0-9]|[Rr]oadmap[[:space:]]*(#?[0-9]|:))'

# A colon-led algorithm step ("Step 1:") is fine; a plan-style "Step 3" is not. The step match is
# case-insensitive because a lower-case "step 2" reached main once. Two Kotlin range shapes quoted
# in comments are spared too: "2..20 step 6" and a fractional "step 0.5".
CODENAME_SPARED='(\b[Ss]tep[[:space:]]*[0-9]:|[0-9]\.\.[0-9]+[[:space:]]+step[[:space:]]|[Ss]tep[[:space:]]*[0-9]+\.[0-9])'

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
      report "a code comment carries a plan/roadmap codename marker (Phase N, P5 S5, Y3, R3, Active #N, Roadmap N, plan Step N); state the durable fact instead. See code-quality.md." "$hits"
      exit 1
    fi
    ;;

  manifest-rows)
    # The manifest only works if every row is real and no listed file comes back. Both failures are
    # otherwise silent: a resurrected file gives two implementations of one surface, and a row
    # pointing nowhere protects nothing.
    file="${1:?file}"
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

  *)
    echo "lint-docs.sh: unknown check '$cmd'" >&2
    exit 2
    ;;
esac
