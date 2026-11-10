# STATUS (2026-11-10)

**Phase:** M3 (read-only stacks and containers) is built and owner-verified on a real phone. PR 7 (branch m3-stacks, last commit 0f3d863) is open and NOT merged: waiting for the owner's go-ahead. 81 unit tests pass.

## Done
- Project registered in WORKSPACE.md; repo, branch protection (PR + `build-and-test`, admins enforced), release keystore and 4 repo secrets in place.
- Phase 0 (docs/dockge-api.md), architecture and build plan approved.
- M0 scaffold, M1 login (password + 2FA, token only in Keystore), M2 saved instances: merged.
- M3: stack list per instance, grouped by server for federated instances, stack detail with containers, health, image, uptime, ports. Verified by the owner (2026-11-10, build 0f3d863) on the production federated instance and the isolated test VM: grouped lists correct, no connection error after screen off/on (log showed recovery within about 1 s).
- Debug-only Diagnostics screen (instance card menu) and build id under the title stay in for now; they are useful for M4 to M6 testing.

## Next action
- Owner says "merge PR 7" (and go for M4). Then M4: actions (up, down, start, stop, restart), terminal output sheet, timeouts for unacked calls, confirm dialogs for destructive ones.
- M4 needs a disposable stack in the isolated VM's stacks folder. Owner must create it and give me its name. Actions are never run against production.

## Open questions
- Name of the disposable test stack for M4.
- TOTP seed storage: not implemented; ask before ever doing it.

## Not yet verified
- Token re-login after a server restart, a revoked token, login rate limit (live).
- Release (R8) APK at runtime; release workflow has never run (first real test is M7).
- Stack detail payload shapes beyond what the owner saw; external or unmanaged stacks.

## Owner manual steps
- Later: run Apprise with an `ntfy://` target and enable ImageWatcher / DOCKGE_PUBLIC_URL (M6). Pin the cert SHA-256 in release.yml (M7).
