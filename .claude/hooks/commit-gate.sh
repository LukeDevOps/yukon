#!/bin/sh
# PreToolUse gate for Bash: force `git commit` to run as a standalone
# command. The independent pre-commit review (agent hook in
# .claude/settings.json) matches `if: "Bash(git commit*)"`, which is a
# prefix match — compound commands like `git add -A && git commit` would
# silently bypass the review. This gate denies any Bash command that
# buries a git commit inside a larger command line, so every commit is
# either reviewed or rejected with instructions.
cmd=$(jq -r '.tool_input.command // ""')

# Standalone commit: starts with "git commit", so the review hook's
# prefix match is guaranteed to fire. Allow.
case "$cmd" in
"git commit"*) exit 0 ;;
esac

# Anything else containing a git commit invocation (after &&, ;, |, a
# newline, or with intervening git args like -C <dir>) is a bypass:
# deny when a `git` word is followed by a `commit` word within the same
# shell segment. Deny-biased: a rare false positive (e.g. `git log
# --grep commit`) just needs rewording; a false negative skips review.
if printf '%s' "$cmd" | grep -qE '(^|[;&|[:space:]])git([[:space:]][^;&|]*)?[[:space:]]commit([^-A-Za-z0-9_]|$)'; then
	cat <<'JSON'
{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"Commits in this repo must run as a standalone command starting with `git commit` so the independent pre-commit review fires. Stage changes with a separate command first, then run `git commit ...` on its own."}}
JSON
fi
exit 0
