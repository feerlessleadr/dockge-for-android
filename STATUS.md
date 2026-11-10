# STATUS (2026-11-10)

**Phase:** M4 (actions) is built on branch m4-actions and waiting for the owner's device test. M0 to M3 are merged (M3 as PR 7). 98 unit tests pass; debug and release (R8) builds succeed. Nothing in M4 has been run against a real server yet.

## Done
- Project registered in WORKSPACE.md; repo, branch protection (PR + `build-and-test`, admins enforced), release keystore and 4 repo secrets in place.
- Phase 0 API reference, architecture and build plan approved.
- M0 scaffold, M1 login, M2 saved instances, M3 read-only stacks and containers (owner-verified on production-federated and test instances): merged.
- M4 (this branch): on the stack detail screen, Start, Restart, Stop, Down for the stack, and Start / Restart / Stop for each container. Confirm dialog (names the instance and server) for everything except Start. Bottom sheet with the server's terminal output (ANSI stripped), progress bar, result; can be hidden while it runs. One action at a time app wide. Buttons disabled while not connected, while the linked server is offline, or while an action runs. The list reloads when an action ends.
- Timeouts: stack actions 10 min, container actions 60 s. If no ack comes, or the connection drops, the sheet says the server may still be running it and to check the stack.

## Next action
- Owner installs the M4 debug build and tests ONLY the `whoami` stack on the isolated test VM instance (see "Owner test" below). Then merge PR (owner's go-ahead) and start M5 (updates).

## Owner test (M4), only on the test instance, stack `whoami`
1. Open whoami; tap Stop, confirm; sheet shows output, "Stop finished"; status becomes stopped.
2. Start (no confirm); running again.
3. Restart, then Down (confirm), then Start (brings it back).
4. On a container card: Restart, Stop, Start.
5. While an action runs, leave the screen and come back (the sheet reopens via the button).
6. Optional failure path: Wi-Fi/VPN off during an action; expect "connection was lost" text.

## Open questions
- None blocking. TOTP seed storage: not implemented; ask before ever doing it.

## Not yet verified
- Every M4 action against a real server. Payloads come from the fork's source: ack `{ok,msg}`, terminal name `compose-<endpoint>-<stack>`; terminal name for a linked (federated) server is a guess handled by accepting two names.
- Whether `restartStack` keeps the compose network; `containerInstanceAction` refuses containers sharing a network namespace (server message is shown).
- Token re-login after a server restart, revoked token, login rate limit (live); release APK at runtime; release workflow (first real test is M7).

## Owner manual steps
- M4 test above. Later: Apprise with an `ntfy://` target and ImageWatcher / DOCKGE_PUBLIC_URL (M6); pin the cert SHA-256 in release.yml (M7).
