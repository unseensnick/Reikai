#!/usr/bin/env bash
# Checks that .githooks/commit-msg enforces the Reuse: footer on a commit already made, which is how CI
# runs it: a fresh checkout stages nothing, so a check reading only the index passes every commit.
#
# a02376e77 added `fun mergeTrackerGenres` under reikai/ with no Reuse: footer, so its own diff owes one.
# Needs full history (CI checks out with fetch-depth 0). Run it: bash scripts/commit-msg-test.sh
set -u
cd "$(dirname "$0")/.."

hook=.githooks/commit-msg
commit=a02376e77
work=$(mktemp -d)
pass=0
broke=0

# check <name> <expected exit> <message>
check() {
  local got=0
  printf '%b' "$3" > "$work/msg"
  REIKAI_COMMIT="$commit" bash "$hook" "$work/msg" > /dev/null 2>&1 || got=1
  if [ "$got" = "$2" ]; then
    echo "  ok    $1"
    pass=$((pass + 1))
  else
    echo "  BROKE $1 (wanted exit $2, got $got)"
    broke=$((broke + 1))
  fi
}

echo "reuse footer on a made commit"
check "rejects a new declaration with no Reuse footer" 1 'fix(test): add a helper\n'
check "accepts it once the footer names a search"      0 'fix(test): add a helper\n\nReuse: none (searched: genre merge)\n'

rm -r "$work"
echo
echo "RESULT: $pass ok, $broke broke"
[ "$broke" -eq 0 ]
