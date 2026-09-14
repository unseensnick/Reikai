#!/usr/bin/env bash
# Checks release_notes against a small changelog pair, one case per rule it owes a release body, in
# both of its modes. Release notes are only read once they are published, so a rule that quietly
# stops holding shows up as a messy release rather than a failure; this makes it fail first.
#
# Run it after touching release_notes: bash scripts/ci/release-notes-test.sh
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

Prose describing the whole release,
wrapped over two lines.

A second paragraph.

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

nightly=$(release_notes "$work/cur.txt" "$work/prev.txt")
stable=$(release_notes "$work/cur.txt")

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

in_nightly() { printf '%s\n' "$nightly" | grep -qxF -- "$1"; }
in_stable() { printf '%s\n' "$stable" | grep -qxF -- "$1"; }

echo "nightly"
check "a new entry is listed by its headline"          0 in_nightly "- A new fix."
check "a new entry without bold keeps its text"        0 in_nightly "- A new internal change."
check "an old entry is not listed again"               1 in_nightly "- An old feature."
check "a reworded detail does not republish an entry"  1 in_nightly "- An old fix."
check "an old entry without bold is not listed again"  1 in_nightly "- An old internal change."
check "Highlights is left out"                         1 in_nightly "### Highlights"
check "a heading with no entries under it is left out" 1 in_nightly "#### Changed"

expected_nightly='### Library

#### Fixed

- A new fix.

### Reader

#### Added

- A new reader feature.

### Other

- A new internal change.'
check "blocks are separated by one blank line"         0 test "$nightly" = "$expected_nightly"

echo "stable"
check "every entry is listed by its headline"          0 in_stable "- An old feature."
check "a heading with no entries under it is left out" 1 in_stable "#### Changed"
check "a wrapped paragraph is joined onto one line"    0 in_stable "Prose describing the whole release, wrapped over two lines."

expected_stable='### Highlights

Prose describing the whole release, wrapped over two lines.

A second paragraph.

### Library

#### Added

- An old feature.

#### Fixed

- An old fix.
- A new fix.

### Reader

#### Added

- A new reader feature.

### Other

- An old internal change.
- A new internal change.'
check "blocks are separated by one blank line"         0 test "$stable" = "$expected_stable"

rm -rf "$work"
echo "$pass passed, $broke broke"
[ "$broke" -eq 0 ]
