#!/usr/bin/env bash
# Applies .github/rulesets/main.json to the repository's default branch.
#
# Branch protection lives in GitHub settings, not in git, so this script keeps it
# reviewable as code. It creates the "main" ruleset, or updates it if it exists.
#
# Requirements: GitHub CLI (https://cli.github.com) logged in as a repository admin:
#   gh auth login
# Usage (from the repository root):
#   scripts/protect-main.sh              # repository taken from the git remote
#   scripts/protect-main.sh owner/repo   # or named explicitly
set -euo pipefail

ruleset_file="$(cd "$(dirname "$0")/.." && pwd)/.github/rulesets/main.json"
command -v gh >/dev/null || { echo "error: GitHub CLI 'gh' is not installed" >&2; exit 2; }
[[ -f "$ruleset_file" ]] || { echo "error: $ruleset_file not found" >&2; exit 2; }

repo="${1:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}"
name="$(sed -n 's/^  "name": "\(.*\)",$/\1/p' "$ruleset_file")"
existing="$(gh api --method GET "repos/$repo/rulesets" --paginate \
  --jq ".[] | select(.name == \"$name\") | .id")"

if [[ -n "$existing" ]]; then
  gh api --method PUT "repos/$repo/rulesets/$existing" --input "$ruleset_file" >/dev/null
  echo "Updated ruleset '$name' (id $existing) on $repo"
else
  gh api --method POST "repos/$repo/rulesets" --input "$ruleset_file" >/dev/null
  echo "Created ruleset '$name' on $repo"
fi
echo "Review it at https://github.com/$repo/settings/rules"
