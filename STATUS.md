# STATUS (2026-11-20)

**Phase:** M7 (release 0.1.0) is prepared on branch m7-release. M0 to M6 are merged and owner-verified on a device (M6 as PR 10). The first release has not been published: it needs the pinned signing certificate, an R8 runtime test, and the owner's "publish it". 149 unit tests pass.

## Done
- Project registered in WORKSPACE.md; repo, branch protection (PR + `build-and-test`, admins enforced), release keystore and 4 repo secrets in place.
- M0 scaffold, M1 login, M2 saved instances, M3 stacks, M4 actions, M5 updates, M6 alerts (the app watches the instances itself, no ntfy, Gotify or Apprise) plus the static progress screen: merged and owner-verified.
- M7 preparation (this branch):
  - Release workflow checks: tag must be on main, `docs/releases/<tag>.md` must exist, signer certificate SHA-256 must equal the value pinned in `release.yml` (empty now, so a tag would fail on purpose), v2 or v3 signature, package, versionName and versionCode must match the tag. Keystore deleted from the runner afterwards. Release notes plus APK and certificate checksums go on the GitHub Release.
  - PR workflow also builds the R8 release (debug key).
  - A release build signed with the debug key installs as `com.feerlessleadr.dockge.rtest`, next to the debug build, for the R8 test.
  - `docs/releases/v0.1.0.md`, README install (Obtainium, by hand, first start).

## Next action
- Owner: (1) read the signing certificate SHA-256 from the keystore and give it to me (public value, steps below), (2) test the `.rtest` build (R8) on the phone, (3) say "publish it" when ready.
- Then: I pin the fingerprint, merge the M7 PR (owner go-ahead), wait for CI, then tag `v0.1.0` only on "publish it". After the release: install through Obtainium, then a second tag (0.1.1) to prove the update path.

## Owner steps
1. Fingerprint (the password stays with you, never send it): in PowerShell run
   `& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -keystore C:\keystore\dockge-for-android.jks`
   and send me the line "Certificate fingerprint (SHA-256): ..." (the alias line can stay out).
2. R8 test: install the `.rtest` APK (separate app "Dockge" next to the debug one). Try: add an instance and sign in (with and without 2FA if you can), open stacks and a detail screen, run a Restart on `whoami`, the update flow, Alerts on and a test alert (the whoami retag trick), force-close and reopen. Anything that crashes or behaves differently from the debug build is an R8 problem: send the screenshot or what you did. Uninstall the `.rtest` app afterwards.

## Open questions
- TOTP seed storage: not implemented; ask before ever doing it.

## Not yet verified
- The release workflow itself (first real run is the tag), the minified (R8) build at runtime on a phone, the Obtainium install and an Obtainium update over a second tag.
- Token re-login after a server restart, a revoked token, login rate limit (live).
- Linked (federated) servers: update status through the production instance, and whether their crash and health events can be read; rollout-pending ack and connection loss during Update.
- The progress parser against real compose output of other compose versions.

## Owner manual steps
- The two above. Release tags only on "publish it".
