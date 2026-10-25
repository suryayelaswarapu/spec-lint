## What & why

<!-- One or two sentences. Link the issue: Fixes #123 -->

## Checklist

- [ ] `./gradlew check` passes locally (all three implementations, coverage >= 80%)
- [ ] Behavior changes are applied to **Python, Java and Go**, and `testdata/conformance` is regenerated (`python python/scripts/update_golden.py`)
- [ ] New or changed rules are documented in `docs/rules.md`
- [ ] No secrets, tokens, internal hostnames or personal data in code, fixtures or docs
