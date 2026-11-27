# STATUS (2026-11-27)

**Phase:** v0.1.1 is published and the owner has updated to it through Obtainium. Current work (unreleased, branch `dockge-self-update`, not committed): updating the Dockge instance itself through dockge-enhanced's safe self-update. Unit tests pass (161). Nothing has run against a live server yet.

## Done
- M0 to M7 and 0.1.1 (Material 3 design, selectable icons), owner-verified; see docs/progress.md.
- Self-update (2026-11-27, code only): `api/SelfUpdate.kt` (client, parser, policy), `data/SelfUpdateData.kt`, `SessionManager` status refresh and start/watch, `ui/instances/DockgeUpdateCard.kt`, stack detail and Updates screen changes. The Dockge stack no longer gets the plain stack update. Design: DECISIONS.md 2026-11-27. API notes: docs/dockge-api.md (self-update section).

## Next action
- Owner: confirm the production Dockge has a backup provider set up (Restic or Zerobyte, see Settings on the server). Without one the server refuses the update, which is safe.
- "Update all" per server is built too (see DECISIONS.md 2026-11-27); test it live with the self-update.
- Then a live test on the test instance (not production), with the owner's go-ahead: a Dockge update to a newer digest, or a dry check with the status call only. Check: manual start works without `mode: sidecar`; the admin token is accepted on `/api/watcher/self/*`; the card's stages and final state; reconnect after Dockge restarts.
- Then decide whether to commit the branch and release (version bump and `docs/releases/` notes only when the owner says "publish it").

## Open questions
- Should the Dockge stack's Restart, Stop and Down buttons also be blocked or warned? They are still offered on that stack.
- TOTP seed storage: not implemented; ask before ever doing it.

## Not yet verified
- Everything in the self-update feature on a live server (see Next action).
- Obtainium update 0.1.0 to 0.1.1 is confirmed by the owner (2026-11-27). Icon switch on the owner's launcher and 0.1.1 layout on small screens and large fonts are still open.
- Token re-login after a server restart, a revoked token, login rate limit (live). Linked (federated) servers: update status and events.

## Owner manual steps
- Confirm the backup setup on production (see Next action).
- Release tags only on "publish it". A tag that produced a public release is never moved or deleted; a bad release is replaced by the next patch version.
