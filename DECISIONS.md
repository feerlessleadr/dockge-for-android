# DECISIONS

### 2026-11-05, Kotlin + Jetpack Compose
- **Context**: Need a phone app for dockge-enhanced instances.
- **Options considered**: Kotlin/Compose, Flutter, React Native, PWA.
- **Decision**: Kotlin + Jetpack Compose, native Android.
- **Rationale**: Matches the owner's other apps (Embidextrous, nhl-scores), gives a foreground service for the ntfy connection and Android Keystore access without bridges.
- **Impact**: Android toolchain as in WORKSPACE.md section 2.

### 2026-11-05, ntfy for push, no FCM
- **Context**: Notifications for update/state events; sideloaded app.
- **Options considered**: FCM, ntfy, polling.
- **Decision**: Subscribe to the owner's existing ntfy server from a foreground/persistent connection. No FCM, no polling.
- **Rationale**: No Google dependency, works with Obtainium sideloading, server already exists.
- **Impact**: Needs a way to publish events to ntfy from the server side; gaps are assessed in Phase 0.

### 2026-11-05, Stable release keystore
- **Context**: Obtainium updates require the same signing key forever.
- **Decision**: One release keystore generated locally outside the repo, stored base64 in GitHub repo secrets, backed up offline by the owner. CI signs release APKs only on `vX.Y.Z` tags. versionCode increases monotonically.
- **Rationale**: A lost or changed key forces a reinstall on every phone.
- **Impact**: Secrets: keystore, store password, key alias, key password. Never in files.

### 2026-11-05, Public repository
- **Context**: Obtainium reads GitHub Releases; the owner's other projects are public.
- **Decision**: Public repo feerlessleadr/dockge-for-android, already created. Branch protection is supported.
- **Rationale**: Free Actions, Obtainium access without tokens.
- **Impact**: No private hostnames, IPs, or credentials in any tracked file.

### 2026-11-05, Standalone project
- **Decision**: No workspace connections. dockge-enhanced and ntfy are external dependencies.

### 2026-11-05, Branch protection on main
- **Context**: Owner asked for PRs and passing checks on main.
- **Decision**: Classic branch protection: PR required (0 approvals, solo project), required check `build-and-test`, enforced for admins, no force pushes or deletions.
- **Rationale**: Keeps main releasable; 0 approvals because there is one maintainer.
- **Impact**: All changes go through PRs from now on. `pr.yml` job name must stay `build-and-test`.

### 2026-11-05, Keystore created
- **Context**: First release needs the stable signing key.
- **Decision**: PKCS12 keystore, RSA 4096, alias `dockge`, valid about 100 years, stored outside the repo in a folder the owner backs up. One password serves as store and key password. Repo secrets: SIGNING_KEYSTORE_BASE64, SIGNING_STORE_PASSWORD, SIGNING_KEY_PASSWORD, SIGNING_KEY_ALIAS.
- **Cert SHA-256**: E4:12:AC:F4:14:61:EE:D0:B8:98:03:19:98:0F:FC:E6:0B:F6:B4:1B:5A:97:3D:7B:3C:7E:59:5A:0D:A1:A0:F3 (public; to be pinned in release.yml once an app exists).
- **Impact**: Never regenerate. Losing it breaks Obtainium updates.

### 2026-11-05, Phase 0 findings shape the design
- **Context**: Source reading of dockge-enhanced 1.5.4 (docs/dockge-api.md).
- **Decision (proposed, not yet approved)**: Use the Socket.IO API as the primary channel (it alone has per-container health, image, uptime, update status). Store only the session JWT (no expiry) on the device, not the password or TOTP seed. Push via Apprise to ntfy first; decide on a companion poller/relay for deep links afterwards.
- **Impact**: Pending owner approval before app code.

### 2026-11-05, Plan approved: ntfy via Apprise first, session token only
- **Context**: Owner reviewed Phase 0 and approved both open proposals.
- **Decision**: (1) v1 push = dockge-enhanced to an Apprise API container to ntfy; a companion relay or poller for deep links and extra events is deferred until M6 shows the limits. (2) The device stores only the session JWT (Keystore-backed key). No password, no TOTP seed. Any TOTP seed storage needs separate explicit approval.
- **Rationale**: Zero server code for v1; the session token has no expiry and is revocable, so a second secret adds risk but little benefit.
- **Impact**: docs/architecture.md and docs/build-plan.md written. Foreground service type specialUse (dataSync is capped at 6 h per day on Android 15+). Socket.IO Java client to be confirmed in M1.

### 2026-11-05, M0 build setup mirrors the owner's other app
- **Context**: Needed a Gradle scaffold quickly with known-good versions on this PC.
- **Decision**: Reuse the toolchain pattern (AGP 9.4.1, Kotlin 2.4.21, Compose BOM, Gradle wrapper 9.8.1, compileSdk 37 with targetSdk 36 because platform 36 is not installed here). `allowBackup=false` plus empty data extraction rules so tokens never leave the phone. Release builds refuse debug signing unless `-PallowDebugSigning`.
- **Rationale**: Proven on this machine; fewer unknowns before the real work (M1).
- **Impact**: build-plan M0; the PR workflow now runs a real build.

### 2026-11-05, Socket.IO Java client confirmed (M1)
- **Context**: Open item from the architecture: does the Java client work against the fork's server?
- **Decision**: Keep `io.socket:socket.io-client` 2.1.2 (websocket transport only, `org.json` excluded because Android ships it). It connected, logged in with 2FA and received `stackList` on a real phone against dockge-enhanced 1.5.x.
- **Rationale**: Works as designed; no need to hand-write the protocol.
- **Impact**: Closes the "Socket.IO Java client against the fork's server version" open item in docs/build-plan.md. The emulator cannot reach the internal test host, so live checks use the real phone.
