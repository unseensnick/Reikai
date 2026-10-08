#!/usr/bin/env bash
# Checks release_notes against a small changelog pair, one case per rule it owes a release body, in
# both of its modes. Release notes are only read once they are published, so a rule that quietly
# stops holding shows up as a messy release rather than a failure; this makes it fail first.
#
# Run it after touching lib.sh: bash scripts/ci/release-notes-test.sh (needs parse-changelog on PATH,
# which both workflows install before running it)
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

# Reader carries one entry over the per-list cap in Added and in Fixed, Before you upgrade one bullet over it.
{
  cat <<'EOF'
### Highlights

Prose describing the whole release,
wrapped over two lines.

A second paragraph.

### Before you upgrade

Back up first,
since this release migrates your library.

EOF
  for i in $(seq 1 11); do echo "- **Caution $i.** Kept whole."; done
  cat <<'EOF'

### Library

#### Added

- **An old feature.** Its detail.

#### Changed

#### Fixed

- **An old fix.** A reworded detail.
- **A new fix.** Its detail.

### Reader

#### Added

EOF
  for i in $(seq 1 6); do echo "- **Reader feature $i.**"; done
  cat <<'EOF'

#### Fixed

EOF
  for i in $(seq 1 6); do echo "- **Reader fix $i.** Its detail."; done
  cat <<'EOF'

### Other

- An old internal change.
- A new internal change.
EOF
} > "$work/cur.txt"

nightly=$(release_notes nightly "$work/cur.txt" "$work/prev.txt")
stable=$(release_notes stable "$work/cur.txt" https://example.com/0.4.0)

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
stable_count() { [ "$(printf '%s\n' "$stable" | grep -cF -- "$1")" = "$2" ]; }

echo "nightly"
check "a new entry is listed by its headline"          0 in_nightly "- A new fix."
check "a new entry without bold keeps its text"        0 in_nightly "- A new internal change."
check "an old entry is not listed again"               1 in_nightly "- An old feature."
check "a reworded detail does not republish an entry"  1 in_nightly "- An old fix."
check "an old entry without bold is not listed again"  1 in_nightly "- An old internal change."
check "Highlights is left out"                         1 in_nightly "### Highlights"
check "Before you upgrade is left out"                 1 in_nightly "### Before you upgrade"
check "a heading with no entries under it is left out" 1 in_nightly "#### Changed"
check "the per-list cap does not apply"                0 in_nightly "- Reader fix 6."
check "no more line is added"                          1 in_nightly "+2 more in the [full changelog](https://example.com/0.4.0)."

expected_nightly="### Library

#### Fixed

- A new fix.

### Reader

#### Added

$(for i in $(seq 1 6); do echo "- Reader feature $i."; done)

#### Fixed

$(for i in $(seq 1 6); do echo "- Reader fix $i."; done)

### Other

- A new internal change."
check "blocks are separated by one blank line"         0 test "$nightly" = "$expected_nightly"

echo "stable"
check "an entry under the cap is listed by its headline" 0 in_stable "- An old feature."
check "a heading with no entries under it is left out" 1 in_stable "#### Changed"
check "a wrapped paragraph is joined onto one line"    0 in_stable "Prose describing the whole release, wrapped over two lines."
check "Other is left out"                              1 in_stable "### Other"
check "an Other entry is left out"                     1 in_stable "- A new internal change."
check "a list shows its first five entries"            0 in_stable "- Reader fix 5."
check "the sixth entry of a list is not listed"        1 in_stable "- Reader fix 6."
check "a full Added list leaves Fixed its own five"    1 in_stable "- Reader feature 6."
check "a capped area says how many it left out"        0 in_stable "+2 more in the [full changelog](https://example.com/0.4.0)."
check "an area under the cap gets no more line"        0 stable_count "more in the [full changelog]" 1
check "Before you upgrade prose is passed through"     0 in_stable "Back up first, since this release migrates your library."
check "Before you upgrade bullets are kept whole"      0 in_stable "- **Caution 1.** Kept whole."
check "Before you upgrade is not capped"               0 in_stable "- **Caution 11.** Kept whole."

expected_stable="### Highlights

Prose describing the whole release, wrapped over two lines.

A second paragraph.

### Before you upgrade

Back up first, since this release migrates your library.

$(for i in $(seq 1 11); do echo "- **Caution $i.** Kept whole."; done)

### Library

#### Added

- An old feature.

#### Fixed

- An old fix.
- A new fix.

### Reader

#### Added

$(for i in $(seq 1 5); do echo "- Reader feature $i."; done)

#### Fixed

$(for i in $(seq 1 5); do echo "- Reader fix $i."; done)

+2 more in the [full changelog](https://example.com/0.4.0)."
check "blocks are separated by one blank line"         0 test "$stable" = "$expected_stable"

echo "full changelog link"
mkdir "$work/rel"
{ printf '# Changelog\n\n## [Unreleased]\n\n## [0.4.0]\n\n'; cat "$work/cur.txt"; } > "$work/rel/CHANGELOG.md"
script="$(cd "$(dirname "$0")" && pwd)/release-notes.sh"
body=$(cd "$work/rel" && VERSION_TAG=v0.4.0 REPO_URL=https://github.com/o/r GITHUB_ENV=/dev/stdout bash "$script")
in_body() { printf '%s\n' "$body" | grep -qxF -- "$1"; }
check "the body links the release's page on the site"  0 in_body "**Full changelog:** https://reikai.app/changelogs/0.4.0"
check "the more line links the same page"              0 in_body "+2 more in the [full changelog](https://reikai.app/changelogs/0.4.0)."

echo "nightly after a changelog rewrite"
# The previous nightly, a code commit adding an entry, a Markdown-only rewrite rewording the oldest
# entry, then a code commit adding another. Only the two code commits' entries are news.
repo="$work/repo"
git init -q "$repo"
commit() {
  git -C "$repo" -c core.autocrlf=false add -A
  git -C "$repo" -c user.name=test -c user.email=test@example.com commit -q -m "$1"
  git -C "$repo" rev-parse HEAD
}
printf '## [Unreleased]\n\n### Library\n\n#### Added\n\n- **Old wording.** Detail.\n' > "$repo/CHANGELOG.md"
base=$(commit base)
mkdir -p "$repo/src"
echo a > "$repo/src/a.kt"
printf -- '- **Added in code.** Detail.\n' >> "$repo/CHANGELOG.md"
commit code > /dev/null
sed -i 's/Old wording/New wording/' "$repo/CHANGELOG.md"
echo notes > "$repo/README.md"
commit rewrite > /dev/null
echo b > "$repo/src/b.kt"
printf -- '- **Added after the rewrite.** Detail.\n' >> "$repo/CHANGELOG.md"
head=$(commit code2)
git -C "$repo" show "$base:CHANGELOG.md" > "$work/base.md"
parse-changelog "$work/base.md" Unreleased > "$work/base.txt"
parse-changelog "$repo/CHANGELOG.md" Unreleased > "$work/head.txt"
(cd "$repo" && reworded_entries "$base" "$head") >> "$work/base.txt"
after_rewrite=$(release_notes nightly "$work/head.txt" "$work/base.txt")
in_after() { printf '%s\n' "$after_rewrite" | grep -qxF -- "$1"; }
check "a reworded entry is not listed as new"          1 in_after "- New wording."
check "an entry added before the rewrite is listed"    0 in_after "- Added in code."
check "an entry added after the rewrite is listed"     0 in_after "- Added after the rewrite."

rm -rf "$work"
echo "$pass passed, $broke broke"
[ "$broke" -eq 0 ]
