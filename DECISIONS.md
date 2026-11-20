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

### 2026-11-10, Federated lists keyed by endpoint; recovery on network return and foreground
- **Context**: Owner tested M3 on a production instance with linked servers. Counts jumped between servers, and after the screen was off the app showed "websocket error" until restarted.
- **Options considered**: Filter lists by `agentList` keys; show only the instance's own list; key lists by the `endpoint` stamped on each pushed `stackList` (what the fork's web UI does).
- **Decision**: Keep one list per endpoint, grouped by server in the UI, never hidden by `agentList`. Rebuild a not-connected socket when the default network becomes available (2 s grace) and when the app returns to the foreground (10 s grace); retry token login with backoff; reconnect delay cap 10 s. Keep a debug-only in-memory diagnostics log (no bodies, tokens or passwords) and the build id in the UI.
- **Rationale**: Owner's device log on build 0f3d863 showed distinct endpoints per push and recovery in about 1 s. The earlier symptoms came from builds missing these fixes.
- **Impact**: StackData.kt, SessionManager.kt, DockgeApp.kt, MainActivity.kt, SocketIoDockgeSocket.kt, DiagnosticsScreen.kt. No cross-project impact.

### 2026-11-10, M4 write actions: one at a time, confirm all but Start, outcome by ack or timeout or lost connection
- **Context**: First write calls in the app. The server acks only after the compose command ended, may never ack (another operation on the same stack), and gives progress only as raw terminal output.
- **Options considered**: Fire and forget plus the pushed stackList as the only feedback; track several actions in parallel; one action at a time with an output sheet.
- **Decision**: One action at a time app wide, held in the ViewModel. Stack actions Start, Restart, Stop, Down and container actions Start, Restart, Stop. Confirm dialog (naming instance and server) for everything except Start. Output from `terminalWrite` of the stack's compose terminal shown in a sheet. Outcome is the first of ack, timeout (10 min stack, 60 s container) or connection loss; the failure text for the last two says the action may still be running. Buttons disabled when not connected or the linked server is offline. No separate "up": it is the same event as start.
- **Rationale**: A phone user cannot judge two overlapping compose runs, and the server rejects overlap on one stack anyway (without an ack). Honest wording matters because a lost ack does not mean the action failed.
- **Impact**: StackActions.kt, TerminalBuffer.kt, InstancesViewModel.kt, StackDetailScreen.kt. M5 reuses the runner for `updateStack` and `serviceAction update`. No cross-project impact.

### 2026-11-10, M5 updates: ask the server, count only trustworthy updates, update the whole stack
- **Context**: The server never pushes update status and has no changelog. Its image watcher is opt-in and can be off, and entries can carry check errors or be ignored.
- **Options considered**: Poll REST `/api/watcher/image/status` (needs an admin JWT in a header, which the app does not hold); use the socket event `watcherImageStatusGet` through the existing logged-in connection; update per service or per image (`serviceAction`, REST `update-now`) versus the whole stack (`updateStack`).
- **Decision**: Use the socket events (also reachable for linked servers through the `agent` envelope). Refresh once the stack list is known and every 5 minutes while connected; a failing server keeps its last result. Count an update only when `hasUpdate`, no `error` and not `ignored`. Ask `watcherImageAutoUpdateGet` whether the watcher is on and say so when it is off. The one-tap action is `updateStack` (whole stack) with a confirm dialog naming instance and server, run through the M4 runner. "Image page" link is a port of the fork's registry-link rules; private registries get no link.
- **Rationale**: No new credentials or transports; honest wording when there is nothing to show; whole-stack update matches what the Dockge UI offers and what the brief asks (one tap, confirm).
- **Impact**: Updates.kt, UpdateData.kt, SessionManager.kt, StackActions.kt, the three stack/instance screens. Per-service update, update-all and rollback stay possible later. M6 can reuse `ServerUpdates` for notification text.

### 2026-11-12, M6 notifications: plain ntfy stream in a foreground service, no WorkManager yet, token in the vault
- **Context**: Alerts must work with no FCM. dockge-enhanced can only reach ntfy through Apprise (plain title and body, no click URL, no stack field). The user must be able to test the app side without Dockge.
- **Options considered**: Poll ntfy on a schedule (WorkManager); keep a streaming connection in a foreground service; also add a WorkManager failsafe that restarts the service; store the ntfy access token in settings, or in the Keystore vault.
- **Decision**: One OkHttp streaming GET of `/<topic>/json?since=<last message id>` (first run: the current time, not the history), read timeout 90 s against ntfy's 45 s keepalive, reconnect with 1 s doubling to 60 s, repeated ids dropped. Foreground service type `specialUse` (not `dataSync`, capped at 6 h a day on Android 15), START_STICKY, restarted by a boot and app-update receiver and whenever the app opens. No WorkManager failsafe in M6: START_STICKY plus the boot receiver plus the app-open start cover the cases we can test, and it would add a dependency we cannot verify. The access token goes in the Keystore vault under a fixed key, never in settings or logs. Lock-screen text is generic per alert kind. Routing is best effort: instance by saved address or host in the text, else the only saved instance; stack only when exactly one known stack name appears as a whole word. A "Send test message" button publishes to the topic.
- **Rationale**: Streaming gives prompt alerts with no polling cost; the cursor makes restarts lossless; honest, generic wording avoids leaking names on a locked phone; guessing a stack only when unambiguous avoids opening the wrong one.
- **Impact**: New package `notify` (Ntfy.kt, NtfyListener.kt, NtfyController.kt, NtfyService.kt, Alerts.kt, AlertNotifier.kt), manifest (permissions, service, receiver, `dockge://` links handled in MainActivity), Alerts screen, README alerts section. If the service gets killed on some phones we revisit the WorkManager failsafe.

### 2026-11-20, M6: Gotify as a second alert source, all fork notifications, one event shown once
- **Context**: The owner runs both ntfy and Gotify and wants alerts to reach whichever services are configured, with every notification dockge-enhanced sends handled and update alerts first. Apprise only sends; there is no Apprise feed a phone could subscribe to, so the app still needs a receiving transport.
- **Options considered**: ntfy only; replace ntfy with Gotify; one `AlertSource` interface with ntfy and Gotify as implementations (each can be on or off); UnifiedPush (needs a distributor app, untestable here); a companion service watching Dockge (deferred).
- **Decision**: `AlertSource` interface; `NtfyClient` (streaming GET) and `GotifyClient` (WebSocket `/stream` with the client token in `X-Gotify-Key`, then `GET /message` after the cursor to catch up; first run starts "now"). Each source has its own settings, token key in the vault, cursor and listener; one foreground service serves both and runs while any is enabled. Dockge sends to every Apprise URL it has, so the same event may arrive twice: the app shows an alert once when title and text repeat within two minutes. Alert kinds cover everything the fork sends: crash loop, unhealthy, image update (incl. auto-updated, mixed, fr/es/zh titles), Dockge self-update, backup, security, cleanup, federation, other. Update alerts: the stack comes from the body's `Stack : **name**` lines (one distinct stack opens it with its server, several open the instance); backup, security, cleanup, federation and self-update open the instance. Gotify has no in-app test button (it would need an application token as well); the Dockge Apprise test button covers it. Gotify message ids carry a `g` prefix and the Gotify cursor only moves forward.
- **Rationale**: A small interface keeps adding a source cheap; each stays independently switchable; the exact titles and body layout come from the fork's source, so routing is accurate where it can be and conservative elsewhere.
- **Impact**: notify package (AlertTypes, AlertListener, Gotify, SourceController, AlertService, Alerts), Alerts screen with two sections, README, manifest service name. Class names `Ntfy*` became `Alert*`/`Source*` where they are shared.

### 2026-11-20, M6 pivot: watch the instances directly, drop ntfy and Gotify (reverses the 2026-11-05 ntfy design and the two M6 entries of 2026-11-12 and 2026-11-20)
- **Context**: The owner asked why notifications go through ntfy or Gotify when Dockge already has Apprise, and whether the app can be notified directly from Dockge. Apprise only sends, and a phone cannot be pushed to without FCM or a persistent connection. The app already holds a Socket.IO connection to every instance and already keeps the update status. The fork's source shows `GET /api/monitoring/overview` (backup, security findings, 10 newest crash and health events), which accepts the same session JWT as an admin Bearer token.
- **Options considered**: Keep ntfy and Gotify listeners; add direct watching next to them; direct watching only.
- **Decision**: Direct watching only (the owner's choice). The ntfy and Gotify listeners and their settings were removed from the app; they stay in git history (4d01e32, aac29c4). `AlertWatcher` polls the monitoring overview every 60 s for connected instances and listens to the update status; `AlertEngine` turns changes into alerts and remembers them (first look silent, per instance, 500 keys). Kinds: image update, crash loop, unhealthy, failed backup (successes are not alerts), security finding. Cleanup, federation and Dockge self-update reports are not watchable (Apprise or Discord only). Per-kind switches and a master switch.
- **Rationale**: No server to run, exact instance and stack links, no `DOCKGE_PUBLIC_URL`, no message parsing, and it reuses the login the app already has. The cost is background connections and the missing report types.
- **Impact**: notify package rewritten (Alerts, AlertEngine, AlertWatcher, Monitoring, AlertNotifier, AlertService), Alerts screen, README, architecture. Revisit ntfy or a companion only if the owner wants the Apprise-only reports. Unverified: the feed with the app's token on a real server, trusted-proxy and auth-disabled servers, and whether linked servers' events can be read.

### 2026-11-20, M7 release hardening: pinned certificate, tag only from main, R8 on every PR, separate id for debug-signed release builds
- **Context**: The release workflow has never run and the owner's device tests so far used debug builds only. A bad tag, a wrongly signed APK or an R8 problem that only shows at runtime would be published under a version number that can never be reused cleanly (Obtainium and Android update by version code and signature).
- **Options considered**: Run the workflow as it was and fix forward; add checks inside the workflow; add a signing-key pin that needs the owner to read the fingerprint from the keystore; test the minified build on a phone before the first tag.
- **Decision**: The release workflow now (1) requires the tag commit to be on main and `docs/releases/<tag>.md` to exist, (2) builds with the real key and deletes the keystore from the runner, (3) verifies with apksigner that the APK has a v2 or v3 signature and that the signer certificate SHA-256 equals the value pinned in `release.yml` (fails when it is empty or different), and with aapt2 that package, versionName and versionCode match the tag, (4) publishes the notes file plus the APK and certificate checksums. The pull request workflow also builds the R8 release (debug key) so minification problems show up on the PR. A release build signed with the debug key gets the application id suffix `.rtest` and version suffix `-rtest`, so it installs next to the debug build for the owner's R8 test and can never be mistaken for, or block, the real release. The certificate fingerprint is public; the owner reads it from the keystore locally (the password stays with the owner) and it is pinned in the workflow.
- **Rationale**: Each check turns a silent bad release into a failed workflow before anything is public. The first tag is the riskiest step, so everything that can be checked earlier is.
- **Impact**: `.github/workflows/release.yml`, `.github/workflows/pr.yml`, `app/build.gradle.kts`, `docs/releases/v0.1.0.md`, README install section. The tag `v0.1.0` is created only when the owner says "publish it".

### 2026-11-20, M7: v0.1.0 published; the failed tag was deleted and recreated
- **Context**: The first run of the release workflow on tag v0.1.0 stopped at the signature check (parser bug, see progress.md) before anything was public. The tag then pointed at a commit that could never release.
- **Options considered**: Publish the same version from a recreated tag; skip to v0.1.1.
- **Decision**: With the owner's go-ahead, deleted the unreleased tag v0.1.0 (remote and local) and recreated it on the fixed main commit 07d2a72. Only possible because no GitHub Release existed for it. Rule for the future: a tag that produced a public release is never moved or deleted; a bad release is superseded by the next patch version.
- **Rationale**: Keeps version 0.1.0 matching its notes and README, and nothing external had consumed the tag.
- **Impact**: v0.1.0 release published (asset dockge-for-android-v0.1.0.apk, SHA-256 0add28609737b1b74a190d0379fafd003576ad33a6cf7b0ee95eff2a44d5b302, signer certificate e412acf4...a0f3 as pinned).
