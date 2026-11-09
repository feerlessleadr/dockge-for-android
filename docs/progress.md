# Progress log

## 2026-11-05
- Registered project, initialised repo, cloned reference fork.
- Pushed initial commit, added pr.yml and release.yml. Protection and secrets pending owner approval.
- Applied branch protection on main (PR + build-and-test, admins enforced).
- Created release keystore and set 4 repo secrets via local script (passwords typed in terminal only).
- Phase 0: three source readings merged into docs/dockge-api.md (PR). Gaps and companion options listed at the top. Waiting for owner approval.
- Owner approved plan. Wrote docs/architecture.md and docs/build-plan.md (M0 to M7). Nothing verified on a real instance yet.

## 2026-11-05, M0 scaffold
- Gradle project (AGP 9.4.1, Kotlin 2.4.21, Compose BOM 2026.09.00, Gradle wrapper 9.8.1), copied the wrapper and version catalog pattern from the owner's other app. Package `com.feerlessleadr.dockge`, minSdk 26, targetSdk 36, compileSdk 37.
- Release signing from `SIGNING_*` env vars; `-PversionName` and `-PversionCode` from the release workflow. A release build without the key fails unless `-PallowDebugSigning` is passed.
- Verified locally (Windows, JBR 25): `assembleDebug testDebugUnitTest` passes; `assembleRelease -PallowDebugSigning -PversionName=0.1.0 -PversionCode=100` succeeds and `output-metadata.json` shows versionName 0.1.0, versionCode 100.
- NOT verified: the app has not been launched on an emulator or phone; CI run on a real Gradle project (this PR is the first); the signed release path (needs a tag).
- `.gitattributes` forces LF on `gradlew` so Linux CI can run it.
- In PowerShell, quote `-P` arguments that contain dots (`'-PversionName=0.1.0'`), otherwise the argument is split.

## 2026-11-05, M1 (branch m1-api-login)
- Added: `BaseUrl` (https-only validation), `DockgeSocket` interface and `SocketIoDockgeSocket` (Socket.IO Java client 2.1.2, websocket transport, no Origin or endpoint header), `AuthApi` and `AuthParser`, `InstanceSession` (token login on every connect, password then 2FA code, token-only storage), `KeystoreTokenVault` (AES-GCM Keystore key, instance id as associated data), a debug-only read-only screen (connect, sign in, list stack names).
- Verified: 31 unit tests pass locally (login state machine incl. 2FA, revoked token, reconnect, rate limit, sign out; parsers; URL validation). Debug and release (R8) builds succeed. The test server answers an unauthenticated Socket.IO handshake (engine.io v4, websocket upgrade offered).
- NOT verified: a real login with and without 2FA; the Socket.IO Java client against the server's protocol on a real connection; Keystore vault on a device; release APK at runtime (R8 rules untested at runtime).
- Emulator note: the Android emulator on this PC cannot resolve the internal test host (host DNS for the internal zone is not used by the emulator, `-dns-server` did not help, the image has no root for a hosts entry). Live checks go on the owner's real phone (WireGuard).
- Deviation from docs/architecture.md: the vault stores ciphertext in SharedPreferences, not DataStore (synchronous reads, nothing else needed in M1).

### M1 live check (2026-11-05, owner's real phone, debug build, isolated test VM instance)
- Verified by the owner: address accepted; password then TOTP login accepted; state reached "connected" and the stack count and names appeared; a wrong password and a wrong code each showed the matching error.
- Still NOT verified: token login after an app restart or reconnect (loginByToken on a live server), a revoked token on a live server, rate-limit message on a live server, Keystore vault behavior after reboot, release (R8) APK at runtime, instances without 2FA.

## 2026-11-06, M2 (branch m2-instances)
- Owner reported after M1 that the server URL and credentials were gone after closing the app. Cause: the M1 debug screen did not save the address, and the password is never stored by design (only the session token is). M2 fixes the part that was a gap: instances (name, color, address) are now saved on disk and reconnect with their saved token when the app starts.
- Added: `Instance` and `PrefsInstanceStore` (JSON in SharedPreferences, committed before returning), `InstanceRepository` (validation, add, edit, delete; changing the address deletes the saved token so it is never sent to another server; delete removes the token), `SessionManager` (one session per instance, follows the repository), `AppContainer`/`DockgeApp` (sessions start when the process starts), instance list with status, add/edit screen with color picker and delete confirm, sign-in dialog (password, then code), Retry connection, Sign out. The M1 debug screen is removed.
- Verified: 49 unit tests pass (repository persistence and validation, token handling on edit and delete, session manager with several instances, add, delete, rename, address change, reconnect, sign out). Debug and release (R8) builds succeed.
- NOT verified: the screens on a device; persistence after closing the app and rebooting the phone; reconnect after losing the network; two live instances at once; Keystore token after a reboot.
- Reconnect uses the Socket.IO library's built-in backoff (1 s growing to 30 s); the card shows "Can't connect" with the reason while it retries, and Retry rebuilds the socket.

## 2026-11-09, M3 (branch m3-stacks)
- M2 (PR 6) merged after the owner tested it on a real phone: saved instances, list with status, reconnect after closing the app and rebooting.
- Added (read only): `Stacks.kt` (typed `Stack`, `ServiceStatus`, `ContainerInstance`, `StackDetail`, `StackParser` for the pushed `stackList` and the acks of `serviceStatusList` and `containerInstances`), `StackListStore` (listens before login, keeps the last pushed list), `StackDetailLoader` (the two read-only calls together), `SessionManager.stackLists`, `Uptime`/`PortFormat`/`containerViews()` (join container with its service start time, simplify ports, fall back to the container's own ports), screens: instance card shows "N stacks, M running" and opens the stack list; stack list with search and status; stack detail with state, health, image, uptime and ports per container, refreshed every 10 s and by pull-to-refresh. M1's name-only parser removed.
- No write call exists in the app: nothing sends start, stop, restart, down or update yet (M4).
- Verified: 64 unit tests pass (parsers on samples from the fork's source, formatting, store, loader, manager); debug and release (R8) builds succeed.
- NOT verified: the screens on a device; payload shapes against a live server (parsers are written from the fork's source, so a real capture may show differences, for example in `startedAt` or the container `ports` string); behavior with many stacks; external or unmanaged stacks on a live server.

### M3 fixes after the owner's phone test (2026-11-09)
- Owner added their production instance, which has other Dockge servers linked to it (federation). Bug: the app kept flipping between the stack counts of the linked servers. Cause: every pushed `stackList` was treated as the instance's own list, but a federated instance pushes one list per server, stamped with an `endpoint`. Fix: lists are stored per endpoint (`ServerStacks`), named from the pushed `agentList` and marked offline from `agentStatus`; the instance card shows the total ("N stacks on K servers, M running"); the stack list is grouped by server when there is more than one; detail calls pass the server's endpoint. Tests reproduce the overwrite.
- Owner also reported: after the screen was off and on again the app showed a connection timed out / websocket error until it was closed and reopened. Root cause NOT confirmed (no logs). Likely causes handled: (1) after a reconnect the token login could time out once and the instance then stayed "failed" forever (no retry): it now retries with growing delays (2 s up to 30 s, 6 tries) and a new connect event restarts that; (2) a socket left half dead by the sleep: when the app comes to the foreground (`MainActivity.onStart`) and when a network becomes available, connected instances are checked with a 5 s token login and any that do not answer, and any not connected, get a fresh socket (not if the socket was opened less than 10 s ago, and not instances waiting for a password or code). Needs the owner's re-test; if it still fails the next step is to capture the error text shown on the card.
- Verified: 79 unit tests pass (including 7 new recovery tests and federation tests); debug and release builds succeed. NOT verified on a device: both fixes.
