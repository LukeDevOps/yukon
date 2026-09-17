# Claude Code project configuration

`commands/` holds the two slash commands this project's status log refers
to: `/deep-review` and `/chunked-build`.

`skills/` vendors the skills those commands and the design notes depend
on, so a fresh checkout has them without a user-level install:

| Skill | Source | Licence |
| --- | --- | --- |
| `humanizer` | https://github.com/blader/humanizer (v2.11.2) | MIT, `LICENSE` beside it |
| `grilling`, `domain-modeling`, `grill-me`, `grill-with-docs` | https://github.com/mattpocock/skills | MIT, `LICENSE` beside each |

Only the skill prompts and their format references are copied. Upstream
packaging (plugin manifests, validators, Codex metadata) is left out.
