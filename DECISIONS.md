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
