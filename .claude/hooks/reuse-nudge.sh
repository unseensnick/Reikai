#!/bin/bash
# Advisory PostToolUse hook for Write. When a new Kotlin file lands under reikai/, it tells the agent
# which of the file's top-level names already exist elsewhere by the same name or the same stem (the
# name without manga, novel or entry), so the reuse search in code-quality.md (DRY) happens while the
# helper can still be dropped. It never blocks: the output is additionalContext and the exit is 0.
# The declaration rules live in scripts/kotlin-decls.sh, shared with the commit-msg and pre-commit hooks.

command -v jq >/dev/null 2>&1 || exit 0

INPUT=$(cat)
# Only a Write carries tool_input.content, so an Edit that reaches this hook finds no names and exits.
FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')
# Windows paths arrive with backslashes (as protect-files.sh notes).
FILE_PATH=${FILE_PATH//\\//}
case "$FILE_PATH" in
  */src/test/*|*/src/androidTest/*) exit 0 ;;
  *reikai/*.kt) ;;
  *) exit 0 ;;
esac

ROOT=${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}
ROOT=${ROOT//\\//}
[ -n "$ROOT" ] && cd "$ROOT" 2>/dev/null || exit 0
[ -f scripts/kotlin-decls.sh ] || exit 0
REL=${FILE_PATH#"$ROOT"/}

# New means git does not track it yet. Rewriting a tracked file is not adding a helper.
git ls-files --error-unmatch -- "$REL" >/dev/null 2>&1 && exit 0

NAMES=$(echo "$INPUT" | jq -r '.tool_input.content // empty' | bash scripts/kotlin-decls.sh names | sed 's/^[a-z]* //')
[ -n "$NAMES" ] || exit 0
SIMILAR=$(bash scripts/kotlin-decls.sh similar "$REL" $NAMES)
[ -n "$SIMILAR" ] || exit 0

CONTEXT="Reuse check for new file $REL: these existing declarations share a name or stem with it. Reuse one, or record why not in the commit's Reuse: footer (code-quality.md DRY):
$SIMILAR"
jq -n --arg c "$CONTEXT" '{hookSpecificOutput: {hookEventName: "PostToolUse", additionalContext: $c}}'
exit 0
