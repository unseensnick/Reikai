#!/usr/bin/env bash
# Top-level Kotlin declarations, for the reuse checks behind the DRY rule in code-quality.md. The
# commit-msg Reuse: footer check, the pre-commit same-name warning and the reuse-nudge Claude hook all
# read them here, so what counts as a declaration is decided once.
#
# Usage:
#   kotlin-decls.sh names                       Kotlin on stdin -> "kind name", one per declaration
#   kotlin-decls.sh added                       a unified diff on stdin -> the "kind name" its added
#                                               lines declare, minus any its removed lines declared
#                                               (an edited signature or a moved file is not new)
#   kotlin-decls.sh similar <path> <name>...    tracked declarations outside <path> with the same
#                                               name, or the same stem (the name without the words
#                                               manga, novel or entry), as "path:line kind name"
#
# A declaration sits at column 0 and is not private: fun, class, object or interface, after any
# modifiers or same-line annotations. Test sources are never scanned.
set -u

decl_awk='
  function decl(text,    l, kind, head, n, parts, name) {
    l = text
    while (l ~ /^@[A-Za-z_.]+(\([^)]*\))? +/) sub(/^@[A-Za-z_.]+(\([^)]*\))? +/, "", l)
    while (l ~ /^(public|internal|data|sealed|abstract|open|inline|suspend|value|enum|annotation|operator|infix|tailrec) +/) sub(/^[a-z]+ +/, "", l)
    sub(/^fun +interface +/, "interface ", l)
    if (l ~ /^(class|object|interface) +[A-Za-z_]/) {
      kind = l; sub(/ .*/, "", kind); sub(/^[a-z]+ +/, "", l)
      match(l, /^[A-Za-z_][A-Za-z0-9_]*/); name = substr(l, 1, RLENGTH)
    } else if (l ~ /^fun +/) {
      kind = "fun"; sub(/^fun +/, "", l); sub(/^<[^>]*> */, "", l)
      if (!match(l, /^[^(=:]*\(/)) return ""
      head = substr(l, 1, RLENGTH - 1); n = split(head, parts, "."); name = parts[n]
      gsub(/[^A-Za-z0-9_]/, "", name)
    } else return ""
    return name == "" ? "" : kind " " name
  }
  function stem(name,    s) { s = tolower(name); gsub(/manga|novel|entry/, "", s); return s }'

mode="${1:?usage: kotlin-decls.sh names|added|similar}"
shift

case "$mode" in
  names)
    awk "$decl_awk"'{ d = decl($0); if (d != "") print d }' | sort -u
    ;;

  added)
    awk "$decl_awk"'
      /^(\+\+\+|---) / { next }
      /^\+/ { d = decl(substr($0, 2)); if (d != "") add[d] = 1 }
      /^-/  { d = decl(substr($0, 2)); if (d != "") del[d] = 1 }
      END   { for (d in add) if (!(d in del)) print d }' | sort -u
    ;;

  similar)
    self="${1:?path}"; shift
    [ "$#" -gt 0 ] || exit 0
    git grep -nE '^(@[^ ]+ +)*([a-z]+ +)*(fun|class|object|interface) ' -- '*.kt' \
        ':(exclude,glob)**/src/test/**' ':(exclude,glob)**/src/androidTest/**' \
      | awk -v self="$self" -v names="$*" "$decl_awk"'
        BEGIN { n = split(names, w, " "); for (i = 1; i <= n; i++) { want[w[i]] = 1; s = stem(w[i]); if (length(s) >= 4) wantStem[s] = 1 } }
        {
          p = $0; sub(/:.*/, "", p); rest = substr($0, length(p) + 2)
          ln = rest; sub(/:.*/, "", ln); text = substr(rest, length(ln) + 2)
          if (p == self) next
          d = decl(text); if (d == "") next
          name = d; sub(/^[a-z]+ /, "", name)
          if ((name in want) || (stem(name) in wantStem)) print p ":" ln " " d
        }'
    ;;

  *)
    echo "kotlin-decls.sh: unknown mode '$mode'" >&2
    exit 2
    ;;
esac
