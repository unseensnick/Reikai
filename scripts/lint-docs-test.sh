#!/usr/bin/env bash
# Checks that scripts/lint-docs.sh actually rejects what it claims to reject.
#
# The rules it enforces are regexes, and a regex that silently stops matching looks exactly like a
# clean tree. Every case below therefore asserts a failure as well as a pass, which is how the two
# gaps this file was written for were found: the whole-tree check used to miss "Roadmap 9" in a .kt
# comment and never looked at .sqm files at all.
#
# Run it after touching lint-docs.sh: bash scripts/lint-docs-test.sh
set -u

lint="$(dirname "$0")/lint-docs.sh"
work=$(mktemp -d)
pass=0
broke=0

# check <name> <expected exit> <command...>
check() {
  local name="$1" want="$2"
  shift 2
  local got=0
  "$@" > /dev/null 2>&1 || got=1
  if [ "$got" = "$want" ]; then
    echo "  ok    $name"
    pass=$((pass + 1))
  else
    echo "  BROKE $name (wanted exit $want, got $got)"
    broke=$((broke + 1))
  fi
}

fixture() {
  printf '%b' "$2" > "$work/$1"
  echo "$work/$1"
}

clean=$(fixture clean.md 'Nothing to see here.\n')

echo "source-names"
check "catches a content source"       1 bash "$lint" source-names "$(fixture names.md 'A line about nhentai.\n')" DOC
check "passes clean prose"             0 bash "$lint" source-names "$clean" DOC

echo "changelog-entries"
check "catches a missing headline"     1 bash "$lint" changelog-entries "$(fixture bad.md '## [Unreleased]\n\n### Fixes\n\n- No bold headline here.\n')"
check "passes a self-contained one"    0 bash "$lint" changelog-entries "$(fixture good.md '## [Unreleased]\n\n### Fixes\n\n- **A real headline.** Detail.\n')"

echo "em-dash"
check "catches an em dash"             1 bash "$lint" em-dash "$(fixture em.md 'text with an em dash \xe2\x80\x94 here\n')" DOC
check "passes clean punctuation"       0 bash "$lint" em-dash "$clean" DOC

echo "issue-refs"
check "catches a bare hash ref"        1 bash "$lint" issue-refs "$(fixture ref.md 'see #123 for detail\n')" DOC hint
check "allows the owner/repo form"     0 bash "$lint" issue-refs "$(fixture ref2.md 'see mihonapp/mihon#123\n')" DOC hint

echo "codenames"
codename() { printf '%b' "$1" | bash "$lint" codenames --stdin; }
check "catches a phase marker"         1 codename '+// Phase 3 of the port\n'
check "catches a roadmap number"       1 codename '// see Roadmap 9\n'
check "catches one in a .sqm comment"  1 codename '-- overlay (P6). detail\n'
check "spares R8, the code shrinker"   0 codename '// R8 strips the signature\n'
check "spares a colon-led step"        0 codename '// Step 1: read the file\n'
check "passes an ordinary comment"     0 codename '// nothing to flag here\n'

echo "manifest-rows"
check "catches a resurrected file"     1 bash "$lint" manifest-rows "$(fixture man.md '| app/src/main/java/eu/kanade/tachiyomi/App.kt | mihon | nowhere/Absent.kt |\n')"
check "catches a manifest with no rows" 1 bash "$lint" manifest-rows "$(fixture man2.md 'no rows at all\n')"

for f in "$work"/*.md; do rm -f "$f"; done
rmdir "$work" 2> /dev/null || true

echo
echo "passed=$pass broken=$broke"
[ "$broke" -eq 0 ]
