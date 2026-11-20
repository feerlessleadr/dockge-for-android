# STATUS (2026-11-20)

**Phase:** v0.1.0 is published (https://github.com/feerlessleadr/dockge-for-android/releases/tag/v0.1.0). M0 to M7 are done. What is left is proving the install and update path on the phone. 149 unit tests pass.

## Done
- Project registered in WORKSPACE.md; repo, branch protection (PR + `build-and-test`, admins enforced), release keystore and 4 repo secrets in place and working (the release workflow used them).
- M0 scaffold, M1 login, M2 saved instances, M3 stacks, M4 actions, M5 updates, M6 alerts (the app watches the instances itself) plus the static progress screen: merged and owner-verified on a device.
- M7: release workflow hardened (tag on main, notes present, pinned signer certificate, package and version checks), PR workflow builds the R8 release, owner tested the minified build, `docs/releases/v0.1.0.md`, README install section. The first run of the workflow failed safely (parser bug), was fixed (PR 12), the unreleased tag was recreated, and the second run published v0.1.0.
- Published: `dockge-for-android-v0.1.0.apk` (SHA-256 0add28609737b1b74a190d0379fafd003576ad33a6cf7b0ee95eff2a44d5b302) and its `.sha256`, signer certificate SHA-256 e412acf41461eed0b8980319980ffce60bf6b41b5a973d7b3c7e595a0da1a0f3.

## Next action
- Owner: install through Obtainium (add `https://github.com/feerlessleadr/dockge-for-android`) and check the app runs, then tell me.
- Then prove the update path: a second tag (0.1.1) that Obtainium installs over 0.1.0. That needs a small real change (or a version-only release), a release notes file, a PR, and the owner's "publish it".

## Open questions
- What should 0.1.1 contain (anything found while using 0.1.0)?
- TOTP seed storage: not implemented; ask before ever doing it.

## Not yet verified
- Installing the signed release APK through Obtainium and running it (only the debug-signed R8 build was run on the phone), and an Obtainium update over a second tag.
- Token re-login after a server restart, a revoked token, login rate limit (live).
- Linked (federated) servers: update status through the production instance, and whether their crash and health events can be read; rollout-pending ack and connection loss during Update.
- The progress parser against real compose output of other compose versions.

## Owner manual steps
- Install 0.1.0 through Obtainium and report. Release tags only on "publish it". A tag that produced a public release is never moved or deleted; a bad release is replaced by the next patch version.
