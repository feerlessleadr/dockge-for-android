# STATUS (2026-11-12)

**Phase:** M5 (updates) is built on branch m5-updates and owner-verified on the test instance (2026-11-12); PR 9 awaits the merge go-ahead. M0 to M4 are merged (M4 as PR 8, after the owner's test of every action on `whoami`). 110 unit tests pass. Found in the test: update status was not refreshed on resume or pull-to-refresh; fixed (not re-verified on a device).

## Done
- Project registered in WORKSPACE.md; repo, branch protection (PR + `build-and-test`, admins enforced), release keystore and 4 repo secrets in place.
- Phase 0 API reference, architecture and build plan approved.
- M0 scaffold, M1 login, M2 saved instances, M3 read-only stacks (owner-verified), M4 stack and container actions (owner-verified on `whoami`, with a reconnect fix): merged.
- M5 (this branch), all read-only except the update itself:
  - Asks each online server for the image watcher result (`watcherImageStatusGet`) and whether the watcher is on (`watcherImageAutoUpdateGet`) once the stack list is known, then every 5 min while connected. A server that fails to answer keeps its last result.
  - Instance card: "N stacks have updates". Stack list: top line, per-server header count, "Update available" on the card. Stack detail: images with an update (old and new digest, shortened), an "Image page" link (port of the fork's registry-link rules; none for private registries), and "Update stack" behind a confirm dialog. A watcher that is off shows a hint instead of "up to date".
  - Update = server `updateStack` (pull, then recreate all services), run through the M4 runner with terminal output sheet. A "rolling update pending" ack is shown as "continues on the server". Refreshes the update status when it ends.
  - There is no changelog on the server; the image page and digests are what we show.

## Next action
- Merge PR 9, then M6 (ntfy notifications).

## Owner test (M5), test instance only (done 2026-11-12, passed)
1. In the test Dockge enable the image watcher (Settings > Image updates or similar) and run a check. Without it the app shows "Update checks are off".
2. To get a real update on `whoami`: pin it to an older tag of a small public image (for example an older `traefik/whoami` version) and Start it, then change the tag in the compose file to `latest` WITHOUT pulling, or use a tag the watcher reports as outdated. Tell me if you want help picking a safe setup.
3. Open the instance: card shows "1 stack has an update"; open the stack: update section with digests and "Image page" (opens the browser).
4. Tap Update stack, confirm; the sheet streams the pull and recreate; afterwards the badge is gone and the containers run the new image.
5. Optional: while Update runs, switch Wi-Fi off briefly; expect the "connection was lost" text, not a false success.

## Open questions
- Which image/tag the owner wants for the update test (see step 2).
- TOTP seed storage: not implemented; ask before ever doing it.

## Not yet verified
- Every M5 call against a real server. `ImageStatus` and the two acks come from the fork's source: `{ok, data:[{image, stack, localDigest, remoteDigest, hasUpdate, lastChecked, ignored?, ignoredDigest?, error?}]}` and `{ok, data:{enabled,...}}`. `updateStack` refuses with "Another Docker update is active" when the server is busy (shown as the failure text).
- Whether linked (federated) servers answer the watcher calls through the instance.
- Token re-login after a server restart, revoked token, login rate limit (live); release APK at runtime; release workflow (first real test is M7).

## Owner manual steps
- M5 test above. Later: Apprise with an `ntfy://` target and `DOCKGE_PUBLIC_URL` (M6); pin the cert SHA-256 in release.yml (M7).
