# STATUS (2026-11-20)

**Phase:** M7 (release 0.1.0) is prepared on branch m7-release (PR 11). M0 to M6 are merged and owner-verified on a device (M6 as PR 10). The signing certificate is pinned and the owner tested the minified (R8) build on a phone. The first release has not been published: it needs the PR 11 merge go-ahead and the owner's "publish it". 149 unit tests pass.

## Done
- Project registered in WORKSPACE.md; repo, branch protection (PR + `build-and-test`, admins enforced), release keystore and 4 repo secrets in place.
- M0 scaffold, M1 login, M2 saved instances, M3 stacks, M4 actions, M5 updates, M6 alerts (the app watches the instances itself, no ntfy, Gotify or Apprise) plus the static progress screen: merged and owner-verified.
- M7 preparation (this branch):
  - Release workflow checks: tag must be on main, `docs/releases/<tag>.md` must exist, signer certificate SHA-256 must equal the value pinned in `release.yml` (empty now, so a tag would fail on purpose), v2 or v3 signature, package, versionName and versionCode must match the tag. Keystore deleted from the runner afterwards. Release notes plus APK and certificate checksums go on the GitHub Release.
  - PR workflow also builds the R8 release (debug key).
  - A release build signed with the debug key installs as `com.feerlessleadr.dockge.rtest`, next to the debug build, for the R8 test.
  - `docs/releases/v0.1.0.md`, README install (Obtainium, by hand, first start).

## Next action
- Owner: go-ahead to merge PR 11 (after CI), then "publish it" for the tag `v0.1.0`. I tag only on "publish it".
- After the release: install through Obtainium, then a second tag (0.1.1) to prove the update path.

## Done by the owner (2026-11-20)
- Signing certificate SHA-256 read from the keystore and pinned in `release.yml` (the first tag run also proves the repo secret holds the same keystore: if not, the workflow fails before publishing).
- R8 (`.rtest`) build on the phone: sign in, Restart, update, alerts and force-close and reopen all work. The owner should uninstall the `.rtest` app.

## Open questions
- TOTP seed storage: not implemented; ask before ever doing it.

## Not yet verified
- The release workflow itself (first real run is the tag), the Obtainium install and an Obtainium update over a second tag.
- Token re-login after a server restart, a revoked token, login rate limit (live).
- Linked (federated) servers: update status through the production instance, and whether their crash and health events can be read; rollout-pending ack and connection loss during Update.
- The progress parser against real compose output of other compose versions.

## Owner manual steps
- Uninstall the `.rtest` test app. Release tags only on "publish it".
