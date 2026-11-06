# STATUS (2026-11-05)

**Phase:** M2 code done on branch m2-instances (49 unit tests pass); phone check pending. Next: M3 (stacks and containers, read only).

## Done
- Project registered in WORKSPACE.md; CLAUDE.md, STATUS.md, DECISIONS.md created.
- Local git repo initialised with origin set; Aerya fork cloned into `reference/` (ignored).

## In progress (awaiting owner approval)
- Branch protection applied. Release keystore created and the 4 repo secrets set (2026-11-05).

## Next action (after M0 PR merges: start M1)
- Finish steps 1-4, report, then stop. Phase 0 (write `docs/dockge-api.md`) only after the owner approves.

## Open questions
- Phase 0 approval to start (docs/dockge-api.md).
- Any TOTP seed storage: ask before implementing.

## Owner manual steps
- Back up the release keystore offline once generated.
