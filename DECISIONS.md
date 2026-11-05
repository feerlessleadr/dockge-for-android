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
