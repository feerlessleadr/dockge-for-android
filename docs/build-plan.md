# Build plan

Written 2026-11-05. Milestone based. Update the state column as work is done. No app code is written until the owner says to start M0.

## Milestones
| # | What | Done when | State |
|---|---|---|---|
| P0 | Repo, docs, CI workflows, branch protection, keystore and signing secrets | Merged to main, secrets set | done except PR 3 merge |
| P1 | Phase 0 investigation (`docs/dockge-api.md`) and plan approval | Owner approved plan and the two decisions (ntfy via Apprise first, session-token-only storage) | approved 2026-11-05 |
| M0 | **Scaffold**: Gradle project (Kotlin 2.x, AGP for API 36, Compose, Material 3), version and signing wired to `-PversionName`, `-PversionCode` and `SIGNING_*` env vars, the PR build now has a real Gradle project, empty themed app | `./gradlew assembleDebug testDebugUnitTest` passes in CI | built locally, PR open |
| M1 | **API client and login**: `DockgeSocket` and fakes, DTOs from captured payloads, login state machine incl. 2FA, token vault. A throw-away debug screen that connects to one real instance | Unit tests green. Signed in to a real instance with and without 2FA (checked by hand) | not started |
| M2 | **Instances**: add, edit, delete instances (name, color, base URL, HTTPS-only validation), instance list with live connection status, one session per instance, reconnect with backoff | Unit tests for session and reconnect; two instances connected by hand | not started |
| M3 | **Stacks and containers (read)**: stack list per instance with status, stack detail with services and containers (state, health, image, uptime), pull to refresh, live from `stackList` pushes | State reducers tested; matches the web UI on a real instance | not started |
| M4 | **Actions**: up, down, start, stop, restart for stacks and containers, confirm dialogs for destructive ones, terminal output sheet, timeouts for unacked calls | Each action run against a throw-away stack on a real instance; timeout path tested | not started |
| M5 | **Updates**: update status list per instance, badge counts, "Image page" link, one tap then confirm then apply (`updateStack`, `serviceAction update`), result handling | Update applied to a disposable stack on a real instance; ImageWatcher enabled there | not started |
| M6 | **Notifications**: ntfy foreground service, resume with `since`, channels, generic lock-screen text, deep links (instance, best-effort stack), battery hint, settings for the ntfy server and topic. Apprise to ntfy server setup documented in the README | A crash-loop or image-update alert from a real instance arrives and the tap opens the right place; survives a reboot and app kill (checked by hand) | not started |
| M7 | **Release 0.1.0**: pin the signing cert SHA-256 in `release.yml`, release notes in `docs/releases/v0.1.0.md`, tag, Obtainium install from the GitHub Release on a real phone | Signed APK installs through Obtainium, a second tagged version updates over it | not started |
| later | Container logs; compose and env editor; terminal and exec; biometric unlock; companion service for structured ntfy events and per-stack deep links; limited mode with `dge_` tokens | Designed for in `docs/architecture.md`, not scheduled | ideas |

## Rules for every milestone
- Small commits, one PR per milestone or per coherent slice. `build-and-test` must pass before merge.
- Update `STATUS.md`, `docs/progress.md` and `docs/architecture.md` in the same PR as the code. Log decisions in `DECISIONS.md`.
- Say in `docs/progress.md` what was and was not verified, and on what (emulator, real phone, which instance).
- Test only against a disposable stack for actions and updates. Never run actions on production stacks while developing.

## Release mechanics (from M7)
- Version tags are semver `vX.Y.Z`. `versionCode = major*10000 + minor*100 + patch`, so it always increases while minor and patch stay below 100.
- Write `docs/releases/vX.Y.Z.md`, commit, wait for CI green, then tag. Only when the owner says "publish it".
- The same signing key signs every release. The keystore is at `C:\keystore\dockge-for-android.jks` (owner confirmed it is backed up).
- The release workflow has not run yet. M7 is its first real test.

## Owner steps the assistant cannot do
- Enable ImageWatcher and set `DOCKGE_PUBLIC_URL` on the instances to be monitored.
- Run an Apprise API container and give dockge-enhanced its URL, with an `ntfy://` target (details in the README at M6).
- Provide a disposable stack on a real instance for action and update testing.
- Install the app on a real phone and report what happens.

## Open questions
- Companion service for deep links and extra events: decide after M6 shows how far plain Apprise gets.
- Socket.IO Java client against the fork's server version: confirmed in M1.
- Whether restricted (scoped) users should be supported: not planned for v1.
- Any TOTP seed storage: not allowed without the owner's explicit approval.
