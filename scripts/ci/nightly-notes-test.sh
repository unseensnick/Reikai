#!/usr/bin/env bash
# Checks new_changelog_entries against a small changelog pair, one case per rule it owes a release
# body. Nightly notes are only read once they are published, so a rule that quietly stops holding
# shows up as a messy release rather than a failure; this makes it fail first.
#
# Run it after touching new_changelog_entries: bash scripts/ci/nightly-notes-test.sh
set -u

# shellcheck source=scripts/ci/lib.sh
. "$(dirname "$0")/lib.sh"

work=$(mktemp -d)
pass=0
broke=0

cat > "$work/prev.txt" <<'EOF'
### Highlights

Prose describing the whole release.

### Library

#### Added

- **An old feature.** Its detail.

#### Fixed

- **An old fix.** The first wording of its detail.

### Other

- An old internal change.
EOF

cat > "$work/cur.txt" <<'EOF'
### Highlights

Prose describing the whole release, now longer.

### Library

#### Added

- **An old feature.** Its detail.

#### Changed

#### Fixed

- **An old fix.** A reworded detail.
- **A new fix.** Its detail.

### Reader

#### Added

- **A new reader feature.**

### Other

- An old internal change.
- A new internal change.
EOF

out=$(new_changelog_entries "$work/prev.txt" "$work/cur.txt")

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

has() { printf '%s\n' "$out" | grep -qxF -- "$1"; }

check "a new entry is listed by its headline"         0 has "- A new fix."
check "a new entry without bold keeps its text"       0 has "- A new internal change."
check "an old entry is not listed again"              1 has "- An old feature."
check "a reworded detail does not republish an entry" 1 has "- An old fix."
check "an old entry without bold is not listed again" 1 has "- An old internal change."
check "Highlights prose is left out"                  1 has "### Highlights"
check "a heading with no entries under it is left out" 1 has "#### Changed"
check "a heading above a new entry is kept"           0 has "### Reader"

expected='### Library

#### Fixed

- A new fix.

### Reader

#### Added

- A new reader feature.

### Other

- A new internal change.'
check "headings and entries are separated by blank lines" 0 test "$out" = "$expected"

rm -rf "$work"
echo "$pass passed, $broke broke"
[ "$broke" -eq 0 ]
