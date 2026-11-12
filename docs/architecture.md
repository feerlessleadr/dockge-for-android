# Architecture

Written 2026-11-05 from `docs/dockge-api.md` (Phase 0). Update it as the code is built. Nothing here is implemented yet.

## Goal
One phone app that manages several independent dockge-enhanced (Aerya fork) instances. Personal sideload through Obtainium. No Play Store, no FCM.

## Stack and why
| Choice | Why |
|---|---|
| Kotlin + Jetpack Compose, Material 3 | Default for the owner's apps. Gives a foreground service and Android Keystore without bridges. Nothing in the problem argues for another stack. |
| minSdk 26, targetSdk and compileSdk 36 (Android 16) | Same floor as the owner's other app. Target Android 16 as asked. |
| Socket.IO Java client (`io.socket:socket.io-client`, v4 protocol) on OkHttp | The fork's useful API is Socket.IO (Phase 0). The REST `/api/v1` has no per-container, health, image or update data. Server library version still UNVERIFIED, so M1 starts by connecting to a real instance. |
| OkHttp for REST and for the ntfy stream | One HTTP stack, one TLS and proxy configuration. |
| kotlinx.serialization, coroutines and Flow | Typed DTOs and state streams. |
| Plain constructor injection (an `AppContainer`), no DI framework | Small app, fewer build moving parts. Revisit if it hurts. |
| SharedPreferences for the instance list and for encrypted tokens, Android Keystore AES-GCM key for secrets | `EncryptedSharedPreferences` is deprecated, so tokens are encrypted with our own Keystore key. Plain SharedPreferences (committed synchronously) is enough for a small list. Moved from DataStore on 2026-11-06. |

## Layers
Dependencies point down only. Each layer has its own package and tests.

```
ui            Compose screens, ViewModels (state in, intents out)
notify        ntfy client, foreground service, deep-link router, notification posting
data          Repositories: InstanceRepository, StackRepository, UpdateRepository, AuthRepository
api           DockgeSocket (Socket.IO), DockgeRest (/api/*), NtfyStream, DTOs, error mapping
core          Result types, clock, logging (no secrets), Keystore vault
```

- **api** knows wire shapes and nothing about Android UI. `DockgeSocket` is an interface, so tests use a fake. It wraps every call as `emit("agent", "", event, args..., ack)` with a client-side timeout, because the server sometimes never acks (unknown event, or a concurrent operation on one stack).
- **data** turns pushes and calls into `StateFlow`s per instance. One `InstanceSession` per instance owns one socket, one token, and the reconnect policy. State logic (merging `stackList` pushes with per-stack `serviceStatusList` and `containerInstances`) is pure Kotlin so it can be unit tested.
- **ui** never touches sockets. It sends intents and renders state.
- **notify** is separate from `data` so the service can run with the UI closed. It reads the instance list and posts notifications.

## Data model (app side)
- `Instance`: id (UUID), name, color, base URL (HTTPS only in release builds), optional ntfy topic override. Stored in DataStore. Its session token lives in the vault under the instance id.
- `ConnectionState` per instance: `Disconnected`, `Connecting`, `NeedsLogin`, `NeedsTwoFactor(challenge)`, `Connected`, `Failed(reason)`.
- Stack (from `stackList`): name, status 0 to 5 (unknown, draft, created, running, exited, paused), compose file name, managed or external. Stack details load on demand.
- Service and container detail (from `serviceStatusList` and `containerInstances`): state, health, image, ports, started time (uptime). The stack list carries none of this, so the app loads detail when a stack is open or for the instance summary.
- Update status: `{image, stack, localDigest, remoteDigest, hasUpdate, lastChecked, ignored}` from `watcherImageStatusGet`.

## Login and credential storage
Flow (per instance):
1. Connect the socket (no `Origin` header, no `endpoint` header).
2. If a token is in the vault: emit `loginByToken`. Success means done.
3. Else, or on `authInvalidToken`: show the sign-in form. Emit `login({username,password})`.
4. If the reply is `{tokenRequired:true, challenge}`, ask for the 6-digit code and emit `login` again with `token` and `challenge` (challenge lives 5 minutes).
5. On `{ok:true, token}`: encrypt and store the session token only. Drop the password and code from memory.

The session token has no expiry. A password change, a 2FA change or deactivating the user revokes it. There is no server logout, so Sign out deletes the token locally.

Tradeoffs of what could be stored:
| Option | Convenience | Risk | Decision |
|---|---|---|---|
| Session token only | Silent reconnect forever until revoked | A stolen unlocked phone or backup of the vault has admin access until the password is changed | **Chosen (owner approved 2026-11-05).** Keystore-backed key; biometric gate is a later option. |
| Password too | Silent re-login after revocation, but 2FA still needs a code | Password reuse exposure; extra secret on device | Not stored |
| TOTP seed | Fully automatic login | Defeats the second factor: phone then holds both factors | **Not implemented. Needs the owner's explicit approval first.** |
| Federation token or `dge_` automation token | Long-lived key | The federation token is a permanent admin key that skips 2FA; `dge_` tokens work only on REST | Avoided. A read and act "limited mode" with `dge_` tokens is a possible later feature. |

The vault key is a non-exportable Keystore AES-GCM key. Deleting an instance or signing out deletes its ciphertext. `allowBackup` is off so tokens never enter cloud backups.

## Actions and the update flow
- Stack actions: `startStack`, `stopStack`, `restartStack`, `downStack`, `updateStack` (pull, then `up -d --force-recreate`). Container or service actions: `serviceAction` and `containerInstanceAction` (start, stop, restart, update). They ack after completion with `{ok,msg}`. Progress is raw terminal output on terminal `compose--<stack>`, shown in a bottom sheet, with no percent.
- Update available: from the watcher status (opt-in on the server, scan every 6 h or on demand through `POST /api/watcher/image/run`). The app shows image, current and remote digest (short), last checked, and a link. There is no changelog field in the fork, so the link is derived from the image name (Docker Hub or GHCR page, shown as "Image page"). The app never claims it is a changelog.
- One tap on Update, then a confirm dialog that names the stack or container and says it will pull and recreate. Only then emit the call. Down, stop and restart also confirm; start does not.
- Result handling: on ack show success or the message; on timeout show "No answer from the server; check the stack" and refresh. Never assume success.

## Notifications (ntfy, no FCM)
- The app subscribes to the owner's ntfy server with a long-lived streaming connection (`/<topic>/json` over OkHttp, or the WebSocket endpoint) in a foreground service. Not polling. Resume with the `since` parameter so nothing is lost across reconnects and app restarts.
- Foreground service type: `specialUse` (a sideloaded app can declare it). `dataSync` is avoided because Android 15 and newer cap it at six hours a day. A persistent low-priority notification is required by Android.
- Battery: the Alerts screen has a button to the battery settings (no first-run prompt). Restart paths in M6: START_STICKY, a boot and app-update receiver, and starting the service whenever the app opens. A WorkManager failsafe is deferred (see DECISIONS 2026-11-12).
- Server side (v1, owner approved 2026-11-05): dockge-enhanced sends to an Apprise API container, which publishes to ntfy. This gives crash loop, unhealthy, image update and backup events as plain alerts. It does not emit container-stopped, status-change or node-offline events, and it gives no per-stack deep link.
- Deep link: the app registers `dockge://instance/<id>/stack/<name>`. A tap opens the instance, and the stack when the app can find the name. Until a companion exists the app matches the instance by the base URL that image-update messages contain, and parses the stack name from the title as a best effort. If either fails it opens the instance list. This is fragile by design and is called out in the UI as best effort.
- Possible companion (decision deferred): a small relay or poller that turns Dockge state into structured ntfy events with a click URL. Design is open; it is not part of v1 unless the owner asks.
- Lock-screen text stays generic (no stack names until unlocked).

## Security rules
- HTTPS only in release builds, no redirects followed, no cleartext. LAN instances use real certificates through the reverse proxy, so no custom trust store.
- Tokens only in the vault, never logged, never in URLs. The REST fallback uses the `Authorization` header, not `?token=`.
- No secrets in tracked files. Example hosts in docs and tests use `dockge.example.com`.
- Per-instance rate limits are server-wide, not per client (login 20 per minute, 2FA 30 per minute). The app must not retry login in a loop.

## Designed for later (out of scope for v1)
| Feature | Server support seen in Phase 0 | Where it plugs in |
|---|---|---|
| Container logs | `getStack` starts streaming logs to the client | A `LogsRepository` over the existing `DockgeSocket`, a new screen. `getStack` side effect must be handled. |
| Compose and `.env` editor | `getStack` returns `composeYAML`, `composeENV`; `saveStack` and `deployStack` exist | An editor module using `StackRepository.save`. Needs conflict handling. |
| Terminal and exec | `terminalWrite` and `terminalExit` events exist | A `TerminalSession` in `api`, reusing the progress sheet's terminal view |
| Biometric unlock | none needed | Gate the vault key with `setUserAuthenticationRequired` |

The API layer stays generic (`call(event, args)` and `events(name)`) so these add screens, not rewrites.

## Testing
- Unit tests (JVM): `api` DTO parsing from captured payload samples, call wrapper timeout and ack handling with a fake socket, the login state machine (token, 2FA, revoked token), the state reducers, update-flow decisions, ntfy line parsing, deep-link parsing.
- Instrumented tests: Compose UI tests with fake repositories for the critical flows (add instance, sign in with 2FA, open stack, confirm and run an update, tap a notification deep link).
- Live checks against a real instance are done by hand at each milestone and recorded in `docs/progress.md` as verified or not verified. Nothing is called verified without one.
- CI: pull requests build and run unit tests (`pr.yml`). Instrumented tests run locally on an emulator for now.

## Files (planned)
```
app/src/main/java/com/feerlessleadr/dockge/
  api/      DockgeSocket.kt, DockgeRest.kt, NtfyStream.kt, dto/
  core/     Vault.kt, Result.kt
  data/     InstanceSession.kt, StackRepository.kt, UpdateRepository.kt, AuthRepository.kt
  notify/   NtfyService.kt, NotificationPoster.kt, DeepLinks.kt
  ui/       instances/, stacks/, stackdetail/, settings/, theme/
```
Package name is a placeholder until the project is scaffolded; it is not a published identifier yet.
