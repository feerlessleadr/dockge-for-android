# STATUS (2026-11-05)

**Phase:** repository setup. No app code yet.

## Done
- Project registered in WORKSPACE.md; CLAUDE.md, STATUS.md, DECISIONS.md created.
- Local git repo initialised with origin set; Aerya fork cloned into `reference/` (ignored).

## In progress (awaiting owner approval)
- Initial commit and CI workflows (pr.yml, release.yml) pushed. Branch protection NOT yet applied (needs owner OK). Keystore and repo secrets NOT yet created (needs owner path and passwords).

## Next action
- Finish steps 1-4, report, then stop. Phase 0 (write `docs/dockge-api.md`) only after the owner approves.

## Open questions
- Keystore path and passwords (asked interactively at secret-setup time).
- Any TOTP seed storage: ask before implementing.

## Owner manual steps
- Back up the release keystore offline once generated.
