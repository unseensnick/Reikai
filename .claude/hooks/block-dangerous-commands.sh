#!/usr/bin/env bash
# Blocks dangerous shell commands: push to protected branches, force push,
# destructive operations. PreToolUse hook for Bash and PowerShell operations.
#
# Both tools must be matched in settings.json. The PowerShell tool is a
# separate tool from Bash, so a matcher of "Bash" alone leaves every guard
# here bypassable by rewriting the command in PowerShell. Both tools carry the
# command in .tool_input.command, so the parsing below is shared.
#
# Exit 2 = block. Exit 0 = allow.
#
# Configurable via env:
#   CLAUDE_PROTECTED_BRANCHES  comma list (default: derived from git + main,master)
#   CLAUDE_UNPROTECTED_REPOS   comma list of path substrings whose main branch is NOT protected
#                              (default: reikai-claude-memories). Force-push stays blocked there.

set -uo pipefail

emit_deny() {
  # Emit a JSON deny decision and exit 2.
  local reason="${1//\"/\\\"}"
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"%s"}}\n' "$reason"
  exit 2
}

if ! command -v jq >/dev/null 2>&1; then
  emit_deny "jq is required for command protection hooks but is not installed."
fi

INPUT=$(cat)
COMMAND=$(printf '%s' "$INPUT" | jq -r '.tool_input.command // empty' 2>/dev/null || true)
[ -z "$COMMAND" ] && exit 0

# ── Protected branch list ────────────────────────────────────────────────
DEFAULT_BRANCHES="main,master"
if GIT_DEFAULT=$(git config --get init.defaultBranch 2>/dev/null) && [ -n "$GIT_DEFAULT" ]; then
  DEFAULT_BRANCHES="$DEFAULT_BRANCHES,$GIT_DEFAULT"
fi
PROTECTED_BRANCHES="${CLAUDE_PROTECTED_BRANCHES:-$DEFAULT_BRANCHES}"

contains_cmd() { printf '%s' "$COMMAND" | grep -qE "$1"; }
contains_icmd() { printf '%s' "$COMMAND" | grep -qiE "$1"; }

# ── One normalised reading of the command ───────────────────────────────
# The structural rules (push, merge, deletes, secret reads, destructive git) read the command as the
# shell and PowerShell would split it, not as one spelling: quotes and backticks dropped, backslashes
# turned into slashes, lower-cased, and cut into one command per line at ; & | ( ) { } and newlines.
# A rule matching the raw text knew one spelling each and missed the rest (-fr, --recursive,
# -Recurse:$true, C:/, .\.env, GC, +main, git -C, a merge inside a script block).
NORM=$(printf '%s' "$COMMAND" | tr -d "'\"\`" | tr '\\' '/' | tr '[:upper:]' '[:lower:]' | sed -E 's/[;&|(){}]+/\n/g')

# Each normalised command is read token by token. A finding is printed as one line: its code, then an
# argument. Command-position rules look at the verb only (after sudo/env-style prefixes), since several
# verbs are also English words that a commit message may carry; delete and destructive-git rules look
# anywhere in a command, as the raw-text rules before them did. A message holding ; & | ( ) or { } is
# still split there, deliberately: the same split is what finds a read inside `bash -c "...; cat .env"`.
FINDINGS=$(printf '%s\n' "$NORM" | awk -v protected="$(printf '%s' "$PROTECTED_BRANCHES" | tr '[:upper:]' '[:lower:]')" '
BEGIN {
  n = split(protected, p, ",")
  for (i = 1; i <= n; i++) if (p[i] != "") PROT[p[i]] = 1
  READERS = "^(cat|head|tail|sed|awk|grep|rg|less|more|strings|xxd|od|base64|cp|mv|scp|curl|get-content|gc|type|select-string|sls|copy-item|copy|cpi|move-item|move|mi|invoke-item|ii)$"
  DELETES = "^(rm|rmdir|rd|del|erase|ri|remove-item|remove-itemproperty)$"
  PS_ONLY = "^(rmdir|rd|del|erase|ri|remove-item|remove-itemproperty)$"
  split("force path literalpath include exclude filter confirm whatif credential stream erroraction verbose", PSP, " ")
  WRITERS = "^(tee|tee-object|set-content|sc|add-content|ac|out-file|new-item|ni)$"
  COPIERS = "^(cp|copy|cpi|copy-item|mv|move|mi|move-item|install)$"
  KNOWN = "^(git|gh|perl|tee|tee-object|set-content|sc|add-content|ac|out-file|new-item|ni|install|" substr(READERS, 3)
}
function base(x) { sub(/^.*\//, "", x); return x }
# A command name as the rules compare it: no directory, no .exe.
function vname(x) { x = base(x); sub(/\.exe$/, "", x); return x }
# The one place a command verb is found. NORM drops quotes before splitting, so a quoted path with a
# space ("C:\Program Files\Git\cmd\git.exe", or the Git Bash spelling Program\ Files) arrives as
# fragments, and the first ("c:/program") is no verb. A path fragment that is not a known verb looks
# ahead to the next fragments of the same path, each still holding a slash, for the one that is.
function resolve(i,    j) {
  if (t[i] !~ /\// || vname(t[i]) ~ KNOWN) return i
  for (j = i + 1; j <= nt && j <= i + 4 && t[j] ~ /\//; j++) if (vname(t[j]) ~ KNOWN) return j
  return i
}
# First token after gh pr and its --repo / -R option, which gh reads before the subcommand as well as after.
function gh_sub(k,    x) {
  k++
  while (k <= nt) {
    x = t[k]
    if (x == "-r" || x == "--repo") { k += 2; continue }
    if (x ~ /^--repo=/ || x ~ /^-r[^-]/) { k++; continue }
    break
  }
  return k
}
# First token after git and its global options, recording a -C directory.
function git_sub(j,    x) {
  j++
  while (j <= nt) {
    x = t[j]
    if (x == "-c") { if (t[j + 1] !~ /=/) print "GITC " t[j + 1]; j += 2; continue }
    if (x ~ /^--(git-dir|work-tree|namespace|super-prefix)$/) { j += 2; continue }
    if (x ~ /^--(git-dir|work-tree|namespace|exec-path|super-prefix)=/ || x ~ /^--(no-pager|paginate|bare|no-replace-objects|literal-pathspecs)$/ || x == "-p") { j++; continue }
    break
  }
  return j
}
function check_push(k,    m, x, force, nonflag, refs, probe, r, d) {
  force = 0; nonflag = 0; refs = 0; probe = 0
  for (m = k + 1; m <= nt; m++) {
    x = t[m]
    if (x == "") continue
    if (x == "--force") force = 1
    else if (x ~ /^--force-(with-lease|if-includes)/) continue
    else if (x == "--all" || x == "--mirror") print "PUSH_PROTECTED every branch"
    else if (x ~ /^--(repo|push-option|receive-pack|exec)$/) m++
    else if (x ~ /^--/) continue
    else if (x ~ /^-[a-z]+$/) { if (x ~ /f/) force = 1; if (x == "-o") m++ }
    else {
      nonflag++
      if (nonflag == 1) continue
      refs++; r = x
      if (substr(r, 1, 1) == "+") { force = 1; r = substr(r, 2) }
      d = r; sub(/^.*:/, "", d); sub(/^refs\/heads\//, "", d)
      if (d == "head" || d == "") probe = 1
      else if (d in PROT) print "PUSH_PROTECTED " d
    }
  }
  if (force) print "FORCE"
  if (refs == 0 || probe) print "PUSH_PROBE"
}
function is_ps_param(y,    i) {
  if (length(y) < 2) return 0
  for (i in PSP) if (index(PSP[i], y) == 1) return 1
  return 0
}
function check_delete(j,    m, x, y, recursive, ps, danger, sys) {
  recursive = 0; ps = (base(t[j]) ~ PS_ONLY); danger = ""; sys = ""
  for (m = j + 1; m <= nt; m++) {
    x = t[m]
    if (x == "" || x == "--") continue
    if (x == "--recursive") { recursive = 1; continue }
    if (x ~ /^-/) {
      y = substr(x, 2)
      if (y ~ /:\$?false$/) continue
      sub(/:\$?true$/, "", y)
      if (index("recurse", y) == 1) { recursive = 1; if (length(y) > 1) ps = 1 }
      else if (is_ps_param(y)) ps = 1
      else if (y ~ /^[a-z]+$/ && y ~ /r/) recursive = 1
      continue
    }
    if (x ~ /^\/\*?$/ || x ~ /^~(\/|$)/ || x ~ /^\$/ || x ~ /^\.\.\/\.\./ || x ~ /^[a-z]:\/?\*?$/ || x ~ /^\/[a-z]\/?\*?$/) danger = x
    if (x ~ /^\/(usr|etc|var|bin|sbin|lib|opt|root|boot)(\/|$)/) sys = "posix"
    if (x ~ /^([a-z]:|\/[a-z])\/(windows|users|programdata)(\/|$)/ || x ~ /^([a-z]:|\/[a-z])\/program$/) sys = "windows"
  }
  if (sys == "windows") print "DELETE_SYSDIR ps"
  else if (recursive && sys == "posix") print "DELETE_SYSDIR posix"
  if (recursive && danger != "") print "DELETE_ROOT " (ps ? "ps" : "posix")
}
function has_secret(j,    m, x) {
  for (m = j + 1; m <= nt; m++) {
    x = t[m]
    if (x ~ /keystore\.properties|google-services\.json/ || x ~ /\.(jks|keystore)$/) return 1
    if (x ~ /(^|[=\/])\.env([.\/]|$)/) return 1
  }
  return 0
}
# Where a write verb writes: every operand of a content writer, the last operand (or -destination / -t)
# of a copy or move. sed and perl count only with -i. Each target is printed as WRITE, and the shell
# half checks it against protected-paths.tsv. Text matching, so an interpreter script still gets past.
function write_targets(i,    v, m, x, n, last, copier) {
  v = vname(t[i])
  if (v ~ /^(sed|perl)$/) {
    for (m = i + 1; m <= nt; m++) if (t[m] ~ /^-[a-z]*i/ || t[m] ~ /^--in-place/) break
    if (m > nt) return
  }
  copier = (v ~ COPIERS); n = 0; last = ""
  for (m = i + 1; m <= nt; m++) {
    x = t[m]
    if (x == "" || x ~ />/) continue
    if (x ~ /^--target-directory=/) { sub(/^[^=]*=/, "", x); print "WRITE " x; continue }
    if (x ~ /^-/) {
      if (x ~ /^-(destination|t|-target-directory)$/) { m++; print "WRITE " t[m]; continue }
      if (x ~ /^-(path|literalpath|lp|pspath|filepath)$/) { m++; if (!copier) print "WRITE " t[m]; continue }
      if (x ~ /^-(value|encoding|itemtype|type|name|inputobject|e|expression|delimiter|stream|width)$/) m++
      continue
    }
    n++; last = x
    if (!copier) print "WRITE " x
  }
  if (copier && n >= 2) print "WRITE " last
}
# A redirect target, attached (>file, 2>>file) or the next token (> file). NORM leaves > unsplit.
function redirect_targets(    m, x) {
  for (m = 1; m <= nt; m++) {
    x = t[m]
    if (index(x, ">") == 0) continue
    sub(/^[^>]*>+/, "", x)
    if (x == "" && m < nt) x = t[m + 1]
    if (x != "" && x !~ /^\/dev\//) print "WRITE " x
  }
}
{
  nt = split($0, t, /[ \t]+/)
  redirect_targets()
  i = 1
  while (i <= nt && (t[i] == "" || t[i] ~ /^(sudo|command|exec|nohup|time|env)$/ || t[i] ~ /^[a-z_][a-z0-9_]*=/)) i++
  if (i > nt) next
  i = resolve(i)
  verb = vname(t[i])
  if (verb == "git") { k = git_sub(i); if (t[k] == "push") check_push(k) }
  if (verb == "gh" && t[i + 1] == "pr" && t[gh_sub(i + 1)] == "merge") print "MERGE"
  if (verb == "gh" && t[i + 1] == "api") { print "GH_API"; if ($0 ~ /pulls\/[0-9]+\/merge/) print "MERGE_API" }
  if (verb ~ READERS && has_secret(i)) print "SECRET"
  if (verb ~ WRITERS || verb ~ COPIERS || verb ~ /^(sed|perl)$/) write_targets(i)
  for (j = i; j <= nt; j++) {
    if (base(t[j]) ~ DELETES) check_delete(j)
    if (base(t[j]) ~ /^git(\.exe)?$/) {
      k = git_sub(j)
      if (t[k] == "clean") for (m = k + 1; m <= nt; m++) if (t[m] == "--force" || t[m] ~ /^-[a-z]*f[a-z]*$/) { print "CLEAN"; break }
      if (t[k] == "reset") for (m = k + 1; m <= nt; m++) if (t[m] == "--hard") { print "RESET"; break }
    }
  }
}')

found() { printf '%s\n' "$FINDINGS" | grep -q "^$1\( \|$\)"; }
found_arg() { printf '%s\n' "$FINDINGS" | grep "^$1 " | head -1 | cut -d' ' -f2-; }

# ── Git push protections ────────────────────────────────────────────────
# Repos whose main IS the working branch (the memories store), so protecting it only produces
# a prompt the operator always answers yes to. Matched on the command text, because the hook's
# own cwd is always the project dir and cannot see a `Set-Location` or `git -C` elsewhere: the
# branch probe below would otherwise read THIS repo's branch and gate a push to a different one.
UNPROTECTED_REPOS="${CLAUDE_UNPROTECTED_REPOS:-reikai-claude-memories}"
# The session's cwd, which is where the command actually runs. The hook's own cwd is always the
# project dir, so it can see neither a worktree nor a session working in a sibling repo.
SESSION_CWD=$(printf '%s' "$INPUT" | jq -r '.cwd // empty' 2>/dev/null || true)
targets_unprotected_repo() {
  local repo
  # `|| [ -n "$repo" ]` because the last entry has no trailing newline and read would drop it.
  while IFS= read -r repo || [ -n "$repo" ]; do
    [ -z "$repo" ] && continue
    printf '%s' "$COMMAND" | grep -qiF -- "$repo" && return 0
    # Also when the session is already sitting in that repo, so a bare `git push` there still passes.
    [ -n "$SESSION_CWD" ] && printf '%s' "$SESSION_CWD" | grep -qiF -- "$repo" && return 0
  done < <(printf '%s' "$UNPROTECTED_REPOS" | tr ',' '\n')
  return 1
}

if ! targets_unprotected_repo; then
  if found PUSH_PROTECTED; then
    emit_deny "Blocked: push to protected branch '$(found_arg PUSH_PROTECTED)'. Use a feature branch and open a PR."
  fi
  # A push naming no branch pushes the current one. Probe the session's cwd (and any git -C
  # directory), not the hook's, so a push from a worktree is judged against its own branch.
  if found PUSH_PROBE; then
    GIT_C=$(found_arg GITC)
    CURRENT=$(git -C "${SESSION_CWD:-.}" ${GIT_C:+-C "$GIT_C"} branch --show-current 2>/dev/null || true)
    if [ -n "$CURRENT" ] && printf '%s' ",$PROTECTED_BRANCHES," | grep -q ",$CURRENT,"; then
      emit_deny "Blocked: you are on '$CURRENT' (a protected branch). Switch to a feature branch."
    fi
  fi
fi

# Force push is blocked everywhere, including the unprotected repos above: the reason to exempt
# them is that main is their working branch, not that overwriting their history is fine. A `+`
# refspec is a force push, and an explicit --force counts even beside --force-with-lease.
if found FORCE; then
  emit_deny "Blocked: force push is not allowed. Use --force-with-lease if you must overwrite remote."
fi

# ── Merging is never the agent's call ───────────────────────────────────
# Rulesets cannot cover this one: to GitHub a PR merge is legitimate, so this matcher is the only guard.
if found MERGE; then
  emit_deny "Blocked: merging a PR is the owner's call. Open the PR and stop."
fi
if found MERGE_API; then
  emit_deny "Blocked: merging a PR through the API is the owner's call. Open the PR and stop."
fi
# The GraphQL mutations are read off the raw text, since NORM splits at the query's braces and so parts
# the mutation name from the gh api call. Gated on a real gh api command, so prose naming them passes.
if found GH_API && contains_icmd 'mergepullrequest|enablepullrequestautomerge'; then
  emit_deny "Blocked: merging a PR through the API is the owner's call. Open the PR and stop."
fi

# ── Destructive filesystem operations ───────────────────────────────────
# A recursive delete (rm, or any PowerShell delete verb or alias, however the recurse switch is
# spelled) of /, a drive root, home, an unresolved $variable or ../.., and any delete inside a
# system directory. `rm` is PowerShell's alias for Remove-Item, so a PowerShell switch on it counts.
if found "DELETE_ROOT ps"; then
  emit_deny "Blocked: recursive PowerShell delete on a drive root, home, or unresolved \$variable. Specify a concrete safe target."
fi
if found DELETE_ROOT; then
  emit_deny "Blocked: recursive force-delete on /, ~, \$HOME, an unresolved \$VAR, or .../.. Path. Specify a concrete safe target."
fi
if found "DELETE_SYSDIR ps"; then
  emit_deny "Blocked: PowerShell delete targeting a system directory."
fi
if found DELETE_SYSDIR; then
  emit_deny "Blocked: recursive delete targeting a system directory."
fi

# Disk and volume destruction.
if contains_icmd '(^|[;&|(){}[:space:]])(Format-Volume|Clear-Disk|Remove-Partition|Initialize-Disk|Set-Partition)([[:space:]]|$)'; then
  emit_deny "Blocked: PowerShell disk or volume operation. Irreversible data loss."
fi

# Download piped into execution, the iwr | iex form of curl | bash.
if contains_icmd '(Invoke-WebRequest|Invoke-RestMethod|iwr|irm|curl|wget)[^|]*\|[[:space:]]*(Invoke-Expression|iex)([[:space:]]|$)'; then
  emit_deny "Blocked: piping downloaded content into Invoke-Expression is dangerous."
fi

# Security settings and machine state.
if contains_icmd '(^|[;&|(){}[:space:]])Set-ExecutionPolicy([[:space:]]|$)'; then
  emit_deny "Blocked: changing the PowerShell execution policy is a security settings change."
fi
if contains_icmd '(^|[;&|(){}[:space:]])(Stop-Computer|Restart-Computer)([[:space:]]|$)'; then
  emit_deny "Blocked: shutting down or restarting the machine."
fi
# Registry writes under HKLM, which is machine-wide configuration.
if contains_icmd '(Remove-Item|Set-ItemProperty|New-ItemProperty|Remove-ItemProperty)[^|;]*HKLM:'; then
  emit_deny "Blocked: writing to HKLM is a machine-wide system settings change."
fi

# ── Secret files are never read through the shell ───────────────────────
# The permissions deny list is tool-scoped (Read/Write/Edit only), and auto mode routes
# file reads through Bash instead, so `cat keystore.properties` walks straight past it.
# Only the hook sees the command text, which makes this the sole place the rule can hold.
# The reader must be the command's verb, never a word inside it, since several reader
# names are also English words and a commit message may carry them.
if found SECRET; then
  emit_deny "Blocked: that reads a secret file (signing keystore, google-services.json, or a .env). Open it yourself if you need its contents."
fi

# ── Protected paths are never written through the shell ────────────────
# The same argument as the secret reads: protect-files.sh guards only the Edit and Write tools, so a
# sed -i or a redirect walked past it. Both read one table, so a path protected for one is protected
# for the other. A deny stops here; an ask waits until every deny rule below has had its say.
PENDING_ASK=""
TABLE="$(dirname "${BASH_SOURCE[0]}")/protected-paths.tsv"
if found WRITE; then
  [ -f "$TABLE" ] || emit_deny "Blocked: protected-paths.tsv is missing, so a shell write cannot be checked."
  while IFS= read -r target; do
    target=${target#./}
    while IFS=$'\t' read -r decision scope glob reason; do
      case "$decision" in ''|\#*) continue ;; esac
      [ "$scope" = edit ] && continue
      # Unquoted on purpose: the table's globs are patterns.
      case "$target" in
        $glob|*/$glob)
          [ "$decision" = deny ] && emit_deny "Blocked: a shell write to $target. $reason"
          [ -z "$PENDING_ASK" ] && PENDING_ASK="A shell write to $target. $reason"
          break ;;
      esac
    done < "$TABLE"
  done < <(printf '%s\n' "$FINDINGS" | sed -n 's/^WRITE //p')
fi

# ── Dangerous database operations ───────────────────────────────────────
# DROP TABLE|DATABASE|SCHEMA
if contains_icmd 'DROP[[:space:]]+(TABLE|DATABASE|SCHEMA)[[:space:]]+'; then
  emit_deny "Blocked: DROP TABLE/DATABASE/SCHEMA detected. Run manually if intended."
fi
# DELETE FROM without a WHERE on the SAME statement.
# Split on ';' so multi-statement inputs are analysed per-statement.
if printf '%s\n' "$COMMAND" | awk '
  BEGIN { IGNORECASE=1; RS=";" }
  /DELETE[[:space:]]+FROM[[:space:]]+[A-Za-z_][A-Za-z0-9_.]*/ {
    if ($0 !~ /WHERE/) { print "BAD"; exit }
  }
' | grep -q BAD; then
  emit_deny "Blocked: DELETE FROM without a WHERE clause. Add a WHERE or run manually."
fi
if contains_icmd 'TRUNCATE[[:space:]]+TABLE'; then
  emit_deny "Blocked: TRUNCATE TABLE detected. Run manually if intended."
fi

# ── Dangerous system commands ───────────────────────────────────────────
# chmod: any world-writable/universal mode (0?777 or a+rwx)
if contains_cmd 'chmod([[:space:]]+-[a-zA-Z]+)*[[:space:]]+0?777([[:space:]]|$)' \
  || contains_cmd 'chmod([[:space:]]+-[a-zA-Z]+)*[[:space:]]+a\+rwx([[:space:]]|$)'; then
  emit_deny "Blocked: chmod 777 / a+rwx grants everyone full access. Use restrictive perms."
fi

# curl/wget piped to a shell
if contains_cmd '(curl|wget)[[:space:]].*\|[[:space:]]*(sudo[[:space:]]+)?(bash|sh|zsh|ksh|fish|dash|csh)([[:space:]]|$)'; then
  emit_deny "Blocked: piping downloaded content directly to a shell is dangerous."
fi

# Disk / partition. Note: only REDIRECTIONS to /dev/ are destructive. `2>/dev/null` is not.
# Pattern matches: `>[ ]*/dev/<something>` but NOT `2>/dev/null` or `&>/dev/null` style for fd-null.
# Strategy: delete the harmless redirects first, then match `>` optionally with whitespace followed by
# /dev/<name> on what is left. Deleting them beats excluding them on the whole command, which failed
# twice over: `>/dev/null;` was read as unsafe because the exclusion demanded whitespace or end of line
# after it, and one safe redirect anywhere cleared a dangerous one later in the same command.
CMD_SANS_SAFE=$(printf '%s' "$COMMAND" | sed -E 's#>[[:space:]]*/dev/(null|stdout|stderr|tty|zero|random|urandom)##g')
if printf '%s' "$CMD_SANS_SAFE" | grep -qE '(^|[^0-9&])>[[:space:]]*/dev/[a-zA-Z][a-zA-Z0-9]*' ; then
  emit_deny "Blocked: redirection into a raw device file can destroy data."
fi
if contains_cmd '(^|[;&|[:space:]])(mkfs|mkfs\.[a-z0-9]+)([[:space:]]|$)' \
  || contains_cmd '(^|[;&|[:space:]])dd[[:space:]]+[^|]*(if|of)=/dev/[a-zA-Z]' ; then
  emit_deny "Blocked: mkfs/dd against a device node. Irreversible data loss."
fi

# ── Destructive git ─────────────────────────────────────────────────────
if found RESET; then
  emit_deny "Blocked: git reset --hard discards uncommitted changes permanently."
fi
if found CLEAN; then
  emit_deny "Blocked: git clean -f permanently deletes untracked files."
fi

# ── Accidental package publishing ───────────────────────────────────────
# Allow --dry-run variants (npm publish --dry-run is safe and common in CI).
publish_patterns=(
  '(npm|yarn|pnpm|bun)[[:space:]]+publish'
  'cargo[[:space:]]+publish'
  'gem[[:space:]]+push'
  'twine[[:space:]]+upload'
)
for pat in "${publish_patterns[@]}"; do
  if contains_cmd "$pat" && ! contains_cmd '(^|[[:space:]])(--dry-run|-n)([[:space:]=]|$)'; then
    emit_deny "Blocked: publishing packages should run in CI or manually, not via Claude."
  fi
done

# A shell write to a path the table asks about, and nothing above denied the command.
if [ -n "$PENDING_ASK" ]; then
  reason="${PENDING_ASK//\"/\\\"}"
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"ask","permissionDecisionReason":"%s"}}\n' "$reason"
fi

exit 0
