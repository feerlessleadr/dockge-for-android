# Dockge-enhanced API reference (Phase 0)

Source studied: Aerya/dockge-enhanced 1.5.4 (read-only; no code run). Parts A to C were produced by separate source readings, cite `file:line` in the fork, and mark unconfirmed items UNVERIFIED. Re-check against the fork before depending on any UNVERIFIED item. The `reference/` clone is gitignored.

## Summary for the app design

1. **Two APIs exist.** The browser-style Socket.IO API (full detail: per-container health, image, uptime, progress, update status) and a small REST automation API `/api/v1` with `dge_` tokens (stacks list, start/stop/restart/update only; no per-container, health, image or update-availability data).
2. **Socket.IO is the primary channel.** Authenticate by event after connect (`login`, with a 2FA challenge step; or `loginByToken`). Every stack call is `emit("agent", "", eventName, ...args, ack)`. Do not send an `endpoint` header; omit the `Origin` header.
3. **Session token is a JWT with no expiry** (confirmed `backend/models/user.ts:38-44`). It is revoked by password change, 2FA change or user deactivation. There is no server logout and no refresh. So the phone can store only this token (Keystore-backed) and never the password or TOTP seed; re-login is needed only after revocation.
4. **Update detection exists** (ImageWatcher: registry digest vs local digest, opt-in, 6 h scan or on demand), pull-only, with no changelog or release-notes field and no push. The app can show "update available" plus image name and digests, and link to the image registry page, but not a real changelog.
5. **Progress** of actions is raw terminal output only (no structured progress). Actions ack on completion. Unknown events never ack, and a concurrent op on one stack may never ack, so the client needs timeouts.
6. **Notifications are server-limited:** only Discord webhooks and an Apprise server (ntfy via Apprise `ntfy://`). No direct ntfy, no generic webhook, no per-stack deep link.

## Gaps that need a companion service (for approval)

| Need | Server today | Smallest fix |
|---|---|---|
| Push to ntfy | Apprise or Discord only | Zero-code: an Apprise API container with an `ntfy://` URL. Gets basic alerts, no click action |
| Tap-to-open a specific stack or container | Only the instance root URL, only in image-update messages; titles are localized | A small companion relay between Apprise (`json://`) and ntfy that sets Click/Actions with a custom-scheme deep link and parses stack from the message (fragile), OR a poller that uses an admin session token to diff stack state and publish structured ntfy events |
| "Container stopped", "stack status changed", "node offline" events | Not emitted | The poller option above (Socket.IO `stackList` pushes every 10 s, or `/api/monitoring/*`) |
| Update-available push | Pull-only status | Poller on `/api/watcher/image/status` |
| Changelog link | No field | App-side link to the image's registry or source page; no server change |
| Per-container info over REST | `/api/v1` lacks it | Not needed; the app uses Socket.IO |

Recommendation: start with the Apprise-to-ntfy path (zero code) for v1 alerts and decide on the poller/relay companion after seeing how well deep links work. This needs the owner's decision.

## Open items to verify before coding
- Socket.IO server library version vs `socket.io-client` Android (Java) compatibility (protocol v4 expected).
- Federation token vs session JWT use on a phone (the former is a permanent admin key; avoid).
- Behavior of scoped (restricted) users in the app.
- OIDC login from a native app (callback goes to the web UI; likely unsupported).

---

# Part A: Authentication
## dockge-enhanced 1.5.4: authentication reference for an Android client

Source root: `C:\dev\dockge-for-android\reference\dockge-enhanced` (paths below are relative to it). All claims were read from code; nothing was executed. Line numbers refer to the files as read.

### 0. Key takeaways

1. There is NO server "logout" handler. The web UI emits `logout` (frontend/src/mixins/socket.ts:483) but the only backend mention is the allow-list in `backend/user-access.ts:39`. Logout = delete the stored token locally. The server cannot revoke one session.
2. Session JWT is HS256 (jsonwebtoken default), signed with a DB-stored secret, with NO `exp`/`iat` option (`backend/models/user.ts:37-43`). It never expires by time. It is revoked only by: password change, 2FA enable/disable/regenerate, user deactivation/deletion, or jwtSecret regeneration.
3. Socket.IO is authenticated by an EVENT after connect (`login` / `loginByToken` / `loginByOidcCode`), not by handshake `auth`. Until then every event except `login, loginByToken, loginByOidcCode, setup, needSetup` is refused with `{ok:false,msg:"access.denied",msgi18n:true}` (`backend/user-access.ts:38-65`).
4. 2FA is a two-step `login`: first call returns `{tokenRequired:true, challenge}`; second call resends username+password+`token`(TOTP or recovery code)+`challenge`.
5. A long-lived credential that does NOT need the password or TOTP seed exists: the "federation token" from `createAgentFederationToken`, usable with `loginByToken` and as HTTP Bearer. It survives password change and 2FA change, never expires, and bypasses 2FA at use time. Powerful (full admin). See section 5.
6. Automation API tokens (`dge_...`) only work on `/api/v1/*` (REST, stack read/actions/history). They cannot log into Socket.IO.
7. Non-browser clients are fine: no `Origin` header means the origin check is skipped (`backend/socket-origin.ts:8-10`). Socket.IO path is the default `/socket.io/` (no custom `path` found in server or client).

### 1. Socket.IO connection

- Server: `new socketIO.Server(httpServer, { cors, allowRequest })` at `backend/dockge-server.ts:339-386`. No `path`, `transports`, `pingInterval`, `pingTimeout` options set, so Socket.IO defaults apply (path `/socket.io/`, websocket + polling). UNVERIFIED: Socket.IO major version (check `package.json` for socket.io / socket.io-client version; client must match protocol v4 = Socket.IO 3.x/4.x).
- Web client: `io(url, { transports: ["websocket","polling"], tryAllTransports: true })` (frontend/src/socket-connection.ts:4-8), url = page origin. No `auth` payload sent in the handshake.
- CORS: only enabled in dev (`origin: "*"`, dockge-server.ts:331-336). Irrelevant to a native client.
- Origin check (`allowRequest`, dockge-server.ts:341-385): bypassed if `isDev` or env `UPTIME_KUMA_WS_ORIGIN_CHECK=bypass`. Otherwise if an `Origin` header is present, it must match the request `Host` header authority (same hostname and port; scheme only used to derive the default port) (`backend/socket-origin.ts:5-27`). If `Origin` is absent it is allowed (logs "Origin is not set"). **Android client: do not send an Origin header (OkHttp/socket.io-client-java sends none by default). If you do send one, it must equal the Host the proxy forwards.** Behind a reverse proxy that rewrites `Host`, a browser Origin would be rejected; without Origin there is no issue.
- Federation-related header: if the request carries an `endpoint` header (string), the server treats it as an agent link, rate-guards by `remoteAddress|endpoint`, and sets `socket.endpoint` (dockge-server.ts:364-382, 403-407). **A mobile client must NOT send an `endpoint` header**: federated sockets are subject to concurrency limits and restricted users are denied on them (`afterLogin`, dockge-server.ts:529-531).
- On connect the server emits `info` (dockge-server.ts:419, `sendInfo(socket, true)` with hideVersion) before any login. Payload (dockge-server.ts:718-733):
  ```ts
  { version?: string, latestVersion?: string, isContainer?: boolean,   // undefined before login (hideVersion)
    primaryHostname: unknown,
    authMode: "local" | "disabled" | "trusted-proxy",
    authModeManaged: boolean,
    oidc: { buttonLabel: string, localLoginDisabled: boolean } | null,
    turnstileEnabled: boolean, turnstileSiteKey: string,
    schedulingTimezone: string }
  ```
  After login `info` is re-sent with version fields (afterLogin -> sendInfo, dockge-server.ts:551).
- Post-connect, also in dockge-server.ts:441-464:
  - `authMode === "trusted-proxy"`: server reads identity header and auto-logs in; emits `autoLogin` with username, or `authProxyError` with message.
  - `needSetup` true: server emits `setup` (no args).
  - `authMode === "disabled"`: auto-login as first user, emits `autoLogin`.
  - else: client must authenticate.
- Reverse proxy: needs WebSocket upgrade passthrough (web UI shows "reverse proxy guide" on connect_error, socket.ts:259). `getClientIP` uses `X-Forwarded-For` / `X-Real-IP` only if setting `trustProxy` is on (dockge-server.ts:741-758); otherwise IP is the proxy. In trusted-proxy auth mode, identity comes from header `x-forwarded-user` by default (env `DOCKGE_AUTH_PROXY_HEADER`) and the socket's remote address must be inside `DOCKGE_AUTH_PROXY_TRUSTED_NETWORKS` (backend/auth.ts:138-156). In that mode a phone's own password login is not used at all (`login` throws authIncorrectCreds because `getAuthMode() !== "local"`, main-socket-handler.ts:230) - the client would need whatever the proxy (e.g. Authelia/Authentik) requires on the WS upgrade request. UNVERIFIED how a native client can satisfy a forward-auth proxy (outside server code).

### 2. Socket events (authentication)

All in `backend/socket-handlers/main-socket-handler.ts` unless noted.

#### 2.1 `needSetup` (line 156)
`emit("needSetup", (needSetup: boolean) => void)`. Public.

#### 2.2 `setup` (line 106)
`emit("setup", username: string, password: string, cb)`; cb gets `{ok:true,msg:"successAdded",msgi18n:true}` or `{ok:false,msg:string}`. Only when authMode local, `needSetup`, and not already initialised. Password strength "Too weak" rejected (<6 chars, needs alpha+digit mix per message at line 117). Not something a client normally needs.

#### 2.3 `login` (line 225)
Request (argument 1, object; `data.username` and `data.password` must be strings or the call fails):
```ts
interface LoginRequest {
  username: string;
  password: string;
  token?: string;          // TOTP "123456" or recovery code "xxxxxxxx-xxxxxxxx-xxxxxxxx-xxxxxxxx" (see 3)
  challenge?: string;      // id returned by first step when 2FA is on
  turnstileToken?: string; // only if server turnstileEnabled and no valid challenge
}
```
Callback (argument 2 is required; if not a function the handler returns silently):
```ts
// success
{ ok: true, token: string /* JWT */ }
// 2FA required (after correct username+password, no `token` supplied)
{ tokenRequired: true, challenge: string }          // NOTE: no `ok` field
// failure
{ ok: false, msg: "authIncorrectCreds" | "authTurnstileFailed" | "oidc.localLoginDisabled"
                 | "twoFactor.restartLogin" | "twoFactor.<other>" | "twoFactor.unavailable", msgi18n: true }
// rate limited (loginRateLimiter / twoFaRateLimiter)
{ ok: false, msg: "Too frequently, try again later." }   // NO msgi18n
```
Flow details:
- Order: arg check -> `isLocalLoginDisabled()` -> `loginRateLimiter.pass` (line 236) -> challenge validation -> Turnstile (skipped if a valid challenge accompanies) -> password verify (bcrypt, `login()` at 509-531, matches `username` exactly and `active = 1`) -> 2FA branch.
- Any unexpected error maps to `msg: "twoFactor.unavailable"` (lines 276-280), i.e. unknown-cause failures look like 2FA errors.
- If `data.challenge` is sent but invalid/expired (>5 min, wrong password) -> `twoFactor.restartLogin` (line 240-242).
- On success: `finishLogin` -> `registerAccountSession` -> `server.afterLogin` (sets `socket.userID`, emits `userAccess`, `info`, then stack list, agent list, connects agents). The JWT is returned after those complete.

#### 2.4 `loginByToken` (line 163)
`emit("loginByToken", token: string, cb)`
```ts
cb({ ok: true })                                         // no new token is issued
cb({ ok: false, msg: "authUserInactiveOrDeleted", msgi18n: true })   // user missing/inactive
cb({ ok: false, msg: "authInvalidToken", msgi18n: true })           // bad signature, revoked (pw/2FA changed), or "Local authentication is disabled"
```
- NOT rate limited (no limiter call in this handler). Requires `getAuthMode() === "local"`.
- Verification: `jwt.verify(token, jwtSecret)`; user looked up by `username` (or by `userId` for federation tokens); for non-federation tokens `decoded.h` must equal `passwordVersionFingerprint(user.password, jwtSecret)` and `decoded.twoFaVersion` must equal `user.twofa_version` (lines 181-185). Mismatch -> `authInvalidToken`.
- If the callback never fires for an unknown non-Error throw (line 207-210 `return`), client should use a timeout. Minor.
- Reconnects: the server keeps no session across a socket reconnect. `socket.userID` is per-socket, so **the client must re-emit `loginByToken` on every `connect` event** (the web UI does exactly this, socket.ts:195-213).

#### 2.5 `loginByOidcCode` (line 286)
`emit("loginByOidcCode", code: string, cb)` -> `{ok:true, token}` or `{ok:false, msg:"oidc.disabled"|"oidc.expired"|"oidc.accountDisabled"|"oidc.failed"|"twoFactor.*", msgi18n:true}`; rate-limited by `loginRateLimiter`. See section 6.

#### 2.6 Other account events (all require an authenticated socket)
- `changePassword` (line 319): `emit("changePassword", { currentPassword: string, newPassword: string }, cb)`. cb `{ok:true,msg:"Password has been updated successfully."}` or `{ok:false,msg}`. Disconnects all OTHER sockets of that user (emits `refresh` then disconnect); the current socket stays but its stored JWT is now invalid (no new token returned from this handler, unlike `scopedPassword`). The client must then re-login with the new password to get a fresh JWT. (UNVERIFIED whether the web UI re-logs in; irrelevant to server behaviour.)
- `disconnectOtherSocketClients` (line 473): no ack.
- `getSettings`/`setSettings`: `setSettings` with `data.disableAuth` requires `currentPassword` (line 416-422).
- Server also pushes `refresh` (no args) before force-disconnecting a socket (dockge-server.ts:965); treat as "re-auth/reload".

#### 2.7 `logout`
No server handler exists (see key takeaway 1).

### 3. Two-factor authentication

Files: `backend/two-factor-auth.ts`, `backend/two-factor-socket.ts`, migration `2026-10-18-0001-two-factor-authentication.ts`.

- TOTP params: SHA1, 6 digits, period 30 s, issuer "Dockge-Enhanced", label = username (`totpFor`, two-factor-auth.ts:35-42). Validation window = 1 step either side (`window: 1`, line 49), i.e. +/-30 s clock skew accepted. Phone clock must be accurate.
- Replay protection: each accepted TOTP counter is stored in `twofa_last_counter`; a code can only be used if its counter is greater than the last used (line 246). Consequence: a code can be used once; a second login within the same 30 s window with the same code fails (`twoFactor.invalidCode`) and you must wait for the next code. A mobile app auto-reconnecting must use `loginByToken` (JWT), not re-submit `login` with TOTP.
- Code format accepted by the server for the `token` field:
  - TOTP: `/^\d{6}$/` string (line 44).
  - Recovery code: `/^[a-f0-9]{8}(?:-[a-f0-9]{8}){3}$/i` (line 53), 10 codes generated at enable time, each single-use, stored as SHA-256 hashes, shown to the user once (returned as `recoveryCodes: string[]` from `twoFactor:enable` / `twoFactor:regenerate`).
- Login flow (main-socket-handler.ts:250-269):
  1. Client sends `{username, password}` (no `token`).
  2. Server verifies password. If `user.twofa_status` and no `data.token`: server stores a per-socket pending challenge (`pendingLogin`, 5 min TTL = `expires: now+300_000`, two-factor-auth.ts:290-294, HMAC of username+password with jwtSecret) and replies `{ tokenRequired: true, challenge: <base64url 256-bit> }`.
  3. Client re-emits `login` with `{username, password, token: "<6-digit or recovery>", challenge}`. The server requires password again plus the same challenge id; pending challenge is held in closure state of THAT socket, so the second call must be on the same socket connection (cleared on `disconnect`, line 96-99).
  4. Server checks `twoFaRateLimiter`, then `factors.verify(...)` (atomic compare-and-swap; wrong code -> `twoFactor.invalidCode`). On success returns `{ok:true, token}`.
  - If the client sends `token` WITHOUT a `challenge` it works too (the `challenged` flag only matters for consistency checks), i.e. a client that already knows 2FA is on can send everything in one `login` call. The one-call variant passes `data.token` truthy so no challenge is created (line 251) and goes straight to verify (the `pendingAccount` check at 260 only applies if `challenged`). Turnstile (if enabled) is still required in that one-call variant since `challenged` is falsy.
- Error messages for 2FA: `twoFactor.invalidCode`, `twoFactor.restartLogin`, `twoFactor.keyUnavailable` (server `two-factor.key` file missing/unreadable; all surface as given only if message starts with `twoFactor.`, else `twoFactor.unavailable`), `twoFactor.unavailable`.
- Management events (`registerTwoFactorHandlers`, two-factor-socket.ts:12-71): `twoFactor:status | prepare | enable | disable | regenerate | cancel`. Request `{ password?: string, code?: string }`, callback `{ok:true, ...}`:
  - `status`: `{ok:true, enabled:boolean, recoveryRemaining:number}` (no password needed).
  - `prepare`: needs `password`; returns `{ok:true, uri: "otpauth://totp/Dockge-Enhanced:<user>?secret=<BASE32>&issuer=...&algorithm=SHA1&digits=6&period=30"}` (secret 20 bytes, pending 10 min, bound to socket id).
  - `enable`: needs `password` + `code` (TOTP); returns `{ok:true, enabled, recoveryRemaining, recoveryCodes:string[10], token: <new JWT>}`.
  - `disable`/`regenerate`: need `password` + `code` (TOTP or recovery); `regenerate` returns new `recoveryCodes`.
  - After enable/disable/regenerate: all OTHER user sockets are disconnected (`disconnectAllSocketClients(userId, socket.id, true)`; sockets authenticated with a federation token are preserved) and a fresh `token` JWT is returned for the current socket. Client MUST replace its stored JWT with this one (the old one is invalid because `twofa_version` incremented).
  - Error cb: `{ok:false, msg:"twoFactor.*"|"twoFactor.changeFailed", msgi18n:true}`; rate-limited with `twoFaRateLimiter` (msg "Too frequently, try again later.").
- TOTP secret is stored server-side AES-256-GCM encrypted with a key in `<dataDir>/two-factor.key` (two-factor-auth.ts:89-159). If that file is lost, every 2FA login fails with `twoFactor.keyUnavailable`.
- Setup of 2FA does not exist for `restricted` users? They are allowed the `twoFactor:*` events (`LIMITED_EVENTS`, user-access.ts:39-40). 2FA is unavailable (throws `twoFactor.unavailable`) unless authMode is local.
- 2FA is not requested at `loginByToken`/HTTP Bearer: possession of a valid JWT is sufficient (2FA only checked at `login`).

### 4. Session token details

JWT (interactive), `backend/models/user.ts:37-43`:
```ts
jwt.sign({ username: string,
           h: string,          // 32 hex chars: HMAC-SHA256(jwtSecret, bcryptHash).slice(0,32)   (password-hash.ts:37-45)
           twoFaVersion: number }, jwtSecret)   // default HS256, no exp, no iat option (jsonwebtoken adds iat automatically)
```
(`iat` is added automatically by jsonwebtoken; there is no `exp`, so the server never expires it.) Decoded type `JWTDecoded` at `backend/util-server.ts:11-17`:
```ts
{ twoFaVersion?: number; username: string; h?: string; userId?: number; scope?: "federation" }
```
Federation JWT: `{ username, userId, scope: "federation" }` (user.ts:51-57) - no `h`, no `twoFaVersion`, no exp.

- Secret: random `jwtSecret` stored in the `setting` table key `jwtSecret` (dockge-server.ts:589-601). Regenerated only if the DB row is missing. Changing/clearing it invalidates every token.
- Revocation triggers (interactive JWT): bcrypt hash change (`changePassword`, `scopedPassword`, `usersSave` with password, admin `User.resetPassword`/CLI reset), `twofa_version` change (2FA enable/disable/regenerate, admin `reset2fa`), `active = 0`, user deleted, username renamed (lookup by username). Also OIDC role change disconnects sockets (`disconnectAllSocketClients`) but the JWT stays valid; role is reloaded at each login.
- Refresh: none. No refresh token, no sliding expiry. The same JWT is reused until revoked.
- "Remember me": purely client-side. Web UI stores the JWT in `localStorage` if `remember` (default on, `localStorage.remember !== "0"`) else `sessionStorage` (socket.ts:31, 361-363). The server has no remember-me concept. The web UI decodes the JWT payload client-side for `username` (jwt-decode).
- No cookies for sessions. The only cookie is `dockge_oidc_state` for the OIDC redirect (HttpOnly, SameSite=Lax, path `/api/auth/oidc`).
- Socket authentication is per socket: `socket.userID` set in `afterLogin` (dockge-server.ts:541/549). On every reconnect do `loginByToken` again.
- `userAccess` event after login: `emit("userAccess", { role: "admin"|"restricted", grants: StackGrant[] })` (dockge-server.ts:531, common/user-access.ts). Read it to know your role.
- A user password hash is bcrypt cost 10 (`password-hash.ts:3`).

### 5. HTTP/REST authentication and long-lived credentials

#### 5.1 Session-JWT-authenticated REST (`requireHttpAuth`, backend/auth.ts:343-408)
- Accepts `Authorization: Bearer <JWT>` OR query `?token=<JWT>` (auth.ts:362-365). Query token leaks into logs; prefer the header.
- Accepts either the interactive JWT (same `h` + `twoFaVersion` checks, auth.ts:375-378) or a federation JWT (`scope:"federation"`, only user active check).
- Role must be admin (`user.role === undefined || "admin"`); `restricted` -> 401 `{ok:false,message:"access.denied"}` (auth.ts:379-381).
- Failure -> HTTP 401 `{ ok: false, message: "Token invalide ou expiré" | "Authentification requise" }` (auth.ts:385, 367, 402-403). Note REST error key is `message`, whereas the automation API uses `error`.
- authMode `disabled`: no auth (identity "auth-disabled"); `trusted-proxy`: header identity (auth.ts:348-360).
- Routers using it (list of path prefixes not all confirmed): audit-log, integrations (plugnpin), docker-resources (images/volumes/containers/networks/prune), monitoring (`/monitoring/*`), stack-tools (`/api/stack-tools/:stack/git/*`), system-stats (`/stats`, `/stack-stats`), watcher (image updates, trivy, backup, zerobyte...), automation admin. UNVERIFIED: exact mount prefixes for each router other than `/api/automation`, `/api/stack-tools`, `/api/v1`, `/api/webhooks`, `/api/auth/oidc`, `/api/transfer/http/:id` (see each router's `mount.use(...)` / `Router.create`).
- Global HTTP limit: express-rate-limit on `/api`: 600 requests/min per client IP (dockge-server.ts:294-300), draft-8 `RateLimit-*` headers, body `{ok:false,message:"Trop de requêtes, réessayez dans quelques instants."}`.

#### 5.2 Automation API tokens (`backend/automation/automation-manager.ts`, `backend/routers/automation-router.ts`, migration `2026-07-26-0001-automation-api.ts`)
Create/list/revoke via admin REST (session JWT Bearer required; admin only):
- `GET /api/automation/stacks` -> stack list; `GET /api/automation/tokens`; `POST /api/automation/tokens`; `DELETE /api/automation/tokens/:id` (revoke); webhooks: `GET|POST /api/automation/webhooks`, `POST /api/automation/webhooks/:id/rotate`, `PATCH /api/automation/webhooks/:id {enabled:boolean}`.
- `POST /api/automation/tokens` body (automation-manager.ts:172-213):
  ```ts
  { name: string /* 1-120 */, permissions: AutomationPermission[] /* >=1 */,
    stacks: string[] /* >=1; each /^[a-z0-9_-]+$/ or "*" */, expiresAt?: string /* ISO date, must be in the future; omitted = never expires */ }
  ```
  Response 201: `{ ok:true, data: { token: "dge_<12hex>_<43 base64url>", item: { id, name, prefix, permissions, stacks, createdAt, expiresAt, lastUsedAt, revokedAt } } }`. The full token is shown only once; only its SHA-256 is stored.
- Permissions (scopes): `stack:read`, `stack:start`, `stack:stop`, `stack:restart`, `stack:update`, `stack:recreate`, `stack:pull-recreate`, `stack:build-recreate`, `stack:backup`, `history:read` (automation-manager.ts:13-30).
- Using the token: `Authorization: Bearer dge_...` OR header `x-api-key: dge_...` (extractApiToken, line 133-139). Token must match `/^dge_[a-f0-9]{12}_[A-Za-z0-9_-]+$/`.
- Endpoints (router, mounted at `/api/v1`):
  - `GET /api/v1/stacks` (needs `stack:read`; filtered to allowed stacks) -> `{ok:true,data:[...stack.toSimpleJSON("")]}`.
  - `GET /api/v1/stacks/:name` (`stack:read`).
  - `POST /api/v1/stacks/:name/actions/:action` where action is one of `start|stop|restart|update|recreate|pull-recreate|build-recreate|backup` (permission `stack:<action>`); returns `{ok:true,data:{stack,action,success,durationMs,output}}` after the docker compose command completes (synchronous, may take long).
  - `GET /api/v1/history?stack=&limit=&offset=` (`history:read`; stack filter mandatory for non-`*` tokens).
  - Webhooks (no header auth): `POST /api/webhooks/:token/:action`, token `dwh_<id>_<secret>`, tied to one stack and a set of actions.
- Errors: `{ok:false,error:string}` with HTTP 401 invalid/expired/revoked token, 403 missing permission/stack, 429 rate limited (+ `Retry-After: 60`), 400 otherwise.
- Automation rate limit: 60 req/min per token (in-memory, `API_RATE_LIMIT_PER_MINUTE`, line 83); webhooks 1-60/min configurable.
- What they CANNOT do: no Socket.IO login, no deploy/edit compose, no logs/terminal, no docker resources, no real-time updates, no user/settings management. They give a restricted, revocable, optionally expiring credential for list/status/start-stop-restart-update actions only. Backups action is available.
- 2FA does not apply to automation tokens.

#### 5.3 Federation token (long-lived full credential)
- `emit("createAgentFederationToken", null, cb)` (backend/socket-handlers/manage-agent-socket-handler.ts:91-105) returns `{ok:true, token: <federation JWT>}` for the logged-in admin (restricted users are blocked by the access guard because the event is not in `LIMITED_EVENTS`).
- Properties (auth.ts:372-378; main-socket-handler.ts:176-185; models/user.ts:45-57): no expiry; survives password and 2FA changes; revoked only by disabling/deleting the user or changing jwtSecret; no 2FA check; accepted by `loginByToken` AND HTTP Bearer; the resulting socket is flagged `federationAuthenticated` and is NOT disconnected on 2FA changes (`disconnectAllSocketClients(..., preserveFederation=true)`); restricted users cannot use it (afterLogin throws `access.denied`, dockge-server.ts:529-531). callback shape from `callbackResult` / `callbackError` (util-server.ts); error `{ok:false,msg,msgi18n:true}`.
- This is an intended-for-instance-linking credential, used by agents (reverse-agent.ts:235 does `loginByToken` with it). Using it for a phone is a design choice with tradeoffs (see section 8). The server identifies the account by `userId`, so it is not affected by renames either.

### 6. OIDC (backend/oidc.ts, backend/routers/oidc-router.ts)

- Enabled only by environment: `DOCKGE_OIDC_ISSUER`, `DOCKGE_OIDC_CLIENT_ID`, `DOCKGE_OIDC_REDIRECT_URI` (must end with `/api/auth/oidc/callback`), optional `DOCKGE_OIDC_CLIENT_SECRET[_FILE]`, `..._SCOPES` (default `openid profile email`), `..._USERNAME_CLAIM` (default `preferred_username`), `..._GROUPS_CLAIM` (default `groups`), `..._ADMIN_USERS`, `..._ADMIN_GROUPS`, `..._LINK_EXISTING`, `..._DISABLE_LOCAL_LOGIN`, `..._BUTTON_LABEL`, `..._ALLOW_HTTP` (oidc.ts:94-143). Only active in authMode local.
- Client discoverability: the `info` event's `oidc: {buttonLabel, localLoginDisabled} | null`.
- Flow: browser opens `GET /api/auth/oidc/login` -> 302 to the provider (PKCE S256, state, nonce; state cookie `dockge_oidc_state`, 10 min) -> provider -> `GET /api/auth/oidc/callback` -> server redirects to **`/#oidc=<one-time-code>`** (or `/#oidcError=<key>`), a fragment on the Dockge origin (oidc-router.ts:47-49, 187). The one-time code (TTL 60 s, `LOGIN_CODE_TTL_MS`) is exchanged by the web UI with `loginByOidcCode`. The redirect target is hard-coded to the web UI; there is NO custom-scheme/app redirect. An Android app would have to open a browser/Custom Tab, then capture the code from the final `/#oidc=` URL (e.g. via a WebView intercepting the URL, since a Custom Tab will not hand the fragment to the app) - UNVERIFIED feasibility, and the state cookie must stay in the same browser context.
- Rate limits: `/api/auth/oidc/*` 30 req/min; `loginByOidcCode` shares `loginRateLimiter`.
- Provider already did MFA, so local TOTP is not requested for OIDC logins (`loginByOidcCode` comment, main-socket-handler.ts:284-285). Resulting JWT is the normal interactive JWT (`User.createJWT`), so the same revocation rules apply. OIDC-created users get a random internal password (resolveOidcAccount; UNVERIFIED details beyond the first 165 lines of oidc.ts - lines 166-373 not read in full).
- If `DISABLE_LOCAL_LOGIN` is on, `login` returns `oidc.localLoginDisabled`; `loginByToken` with existing JWTs still works.

### 7. Roles, scoped (restricted) users

Files: `backend/user-access.ts`, `common/user-access.ts`, `backend/scoped-users.ts`, `backend/scoped-account.ts`, migration `2026-10-24-0001-scoped-users.ts`.

- Roles: `admin` (legacy rows with no role also treated as admin) or `restricted` (`accessFromUser`, user-access.ts:5-28).
- Types (common/user-access.ts):
  ```ts
  type UserPermission = "view"|"logs"|"start"|"stop"|"restart"|"update"|"recreate";
  interface StackGrant { endpoint: string /* "" = this instance, else agent endpoint */; stack: string; service?: string; permissions: UserPermission[] /* must include "view" */ }
  interface UserAccess { role: "admin"|"restricted"; grants: StackGrant[] }
  ```
  Standalone (non-Compose) containers use stack name `@container:<name>`, endpoint `""`, permissions limited to view/logs/start/stop/restart.
- How it appears to a client: after login, `userAccess` event `UserAccess`. For `restricted` users, the server drops ALL emits except `userAccess`, `info`, `refresh` (user-access.ts:67-73): no stack list, no agent list, no terminals, no live status. And only the events in `LIMITED_EVENTS` are accepted: `logout, scopedList, scopedRequest, scopedPassword, twoFactor:status|prepare|enable|disable|regenerate|cancel` (user-access.ts:39-40). Everything else gets `{ok:false,msg:"access.denied",msgi18n:true}` (if an ack function is the last arg; otherwise silently dropped).
- Restricted-user events:
  - `scopedList` -> `cb({ok:true, data: StackGrant[]})`.
  - `scopedRequest` payload `{endpoint: string, stack: string, service?: string, permission: UserPermission}` -> `cb({ok:true,data})` where data is one of `{name,status:number,displayName,retirementPending?,serviceState?}` for `view`, `{logs:string}` (<=1 MiB, last 200 lines) for `logs`, `{applied:true,retirementPending?}` for actions; errors `{ok:false,msg:"access.operationFailed"|..., msgi18n:true}` (generic by design). One in-flight request per user+endpoint+stack. Actions are audit-logged (`stack.delegated.<permission>`).
  - `scopedPassword` `{currentPassword, newPassword, code?}` -> `{ok:true, token: <new JWT>}` or `{ok:false,msg:"access.accountFailed"}`; `code` (TOTP/recovery) required if user has 2FA on; `twoFaRateLimiter`.
- Admin management events: `usersList` -> `{ok:true,data:[{id,username,active,oidc,role,grants}]}`, `usersSave` `{id?,username,password?,active,grants}` (username `/^[a-zA-Z0-9_.-]{1,64}$/`), `usersAccountAction` `{id,action:"delete"|"reset2fa",password,code?,confirm:true}`, `scopedContainerCandidates`. All admin-only and require local auth mode.
- Restricted users can NOT use HTTP APIs (401 `access.denied`) nor federation tokens. Restricted users need a different UI mode from admin.
- Login for restricted users uses the same `login`/`loginByToken`; their socket is not joined to the user room and receives no stack list.

### 8. Rate limits and lockouts a mobile client must handle

| Where | Limit | Behaviour on exceed | Source |
|---|---|---|---|
| `login`, `loginByOidcCode` | 20 per minute, ONE GLOBAL token bucket for the whole server (module-level singleton, not per IP, not per user) | cb `{ok:false,msg:"Too frequently, try again later."}` (no `msgi18n`) | rate-limiter.ts:62-67, main-socket-handler.ts:236 |
| `twoFaRateLimiter` (second step of 2FA login, `twoFactor:*` mutations, `scopedPassword`, `usersAccountAction`) | 30 per minute global | same message shape | rate-limiter.ts:76-81 |
| `apiRateLimiter` | 60/min | defined, UNVERIFIED where used | rate-limiter.ts:69-74 |
| `loginByToken` | none | n/a | main-socket-handler.ts:163-222 |
| HTTP `/api/*` | 600/min per IP | 429 JSON (see 5.1) | dockge-server.ts:294-300 |
| Automation API token | 60/min per token | 429 + `Retry-After: 60` | automation-manager.ts:83,119-131 |
| Webhook | 1-60/min per webhook | 429 | automation-manager.ts:360 |
| OIDC routes | 30/min | redirect `/#oidcError=oidc.busy` | oidc-router.ts:61-67 |
| Federation ingress guard | per `remoteAddress|endpoint` handshakes | handshake rejected `"Federation reconnect rate limited"` (only when client sends an `endpoint` header) | dockge-server.ts:364-382 |

- There is NO per-account lockout, NO exponential backoff, NO CAPTCHA by default. Brute force protection is only the global 20/min bucket (note: this means a bad actor or a misbehaving client can lock out everyone else's logins; and a retrying app can starve the bucket). The client should back off on that exact message and avoid auto-retrying `login` in a loop; auto-reconnect should use `loginByToken`, which is unlimited.
- Optional Cloudflare Turnstile (`turnstileEnabled` in `info`): if enabled, `login` also needs `turnstileToken` (obtained from a Turnstile widget with `turnstileSiteKey`) unless a valid 2FA `challenge` accompanies it. A native client cannot easily satisfy Turnstile without a WebView. Error `authTurnstileFailed`. When on, a federation token via `loginByToken` is the only practical path, apart from WebView hosting the widget. (`verifyTurnstile` main-socket-handler.ts:37-62.)
- Concurrency: security changes serialize per account with `reserveAccountSecurity`; a login that collides with an in-progress 2FA/password change gets `twoFactor.restartLogin` (account-security.ts:5-11). Retry after a short delay.
- `finishLogin` re-validates the password/2FA version just before registering; any change during login -> `twoFactor.restartLogin`.

### 9. Security tradeoffs for a phone (derived from the server's capabilities)

Options for what the app persists (in Android Keystore-backed EncryptedSharedPreferences/DataStore):

1. **Store the interactive session JWT only (recommended default).**
   - Obtained once via `login` (+TOTP once). JWT has no expiry; `loginByToken` on every connect; no TOTP or password needed again. 2FA is only asked at the first interactive `login`.
   - Revoked automatically if password/2FA changes (user must re-login, app should detect `authInvalidToken` and prompt).
   - A stolen JWT = full account access indefinitely (no way to revoke a single token; the remedy is changing the password, which kills every JWT of that user including the web UI's). Keep it in hardware-backed storage and optionally require biometric to unseal it.
   - Do not store the password nor the TOTP seed: not needed.
2. **Store password (+ TOTP seed to auto-generate codes): not recommended.** Server supports it (the app would compute 6-digit SHA1 TOTP using the seed from `twoFactor:prepare` URI) but it defeats 2FA, and replay protection plus the global 20/min limiter make auto re-login brittle. The TOTP seed is only visible when enabling 2FA (`prepare` returns the otpauth URI); the server never re-displays it.
3. **Federation token** (`createAgentFederationToken`): long-lived, survives password/2FA changes, no 2FA at use. Strictly more powerful and harder to revoke than the session JWT (only by disabling the user or rotating jwtSecret). Not recommended for a phone unless you want to avoid the 2FA re-prompt after a password change; call it out as "equivalent to a permanent admin key".
4. **Automation API token (`dge_`)**: the only revocable (`DELETE /api/automation/tokens/:id`), expirable, scope-limited credential. Ideal for a restricted "widget / quick actions / status" mode over REST (`/api/v1/*`). It cannot power the full Socket.IO UI (no logs, compose editing, terminals, push of live stack list). Requires an admin to create it (via the web UI or an admin session). Not subject to 2FA.
5. **Restricted user + `scopedRequest`**: server-enforced per-stack/service least privilege (view/logs/start/stop/restart/update/recreate) with an ordinary username/password (+ optional 2FA). Good for a low-risk phone account, but the UI is limited to `scopedList`/`scopedRequest` and has no live stack list or terminals.
- Transport: server can run plain HTTP (`dockge-server.ts:303-313`); TLS usually terminated by a reverse proxy. The JWT, password and TOTP travel in Socket.IO payloads, so require HTTPS/WSS (and consider certificate pinning for private CAs).
- The query-string `?token=` HTTP variant puts the JWT in URLs/logs: always use the `Authorization` header.
- `info.authMode` tells the client up-front whether password login applies (`local`), whether the instance is open (`disabled`: socket auto-logs-in and emits `autoLogin`, no credentials), or proxy-authenticated (`trusted-proxy`).

### 10. Quick reference: suggested Android handshake

```
connect (no Origin header, no 'endpoint' header, transports websocket)
  <- info { authMode, oidc, turnstileEnabled, ... }
  if authMode == "disabled": <- autoLogin   (done)
  if authMode == "trusted-proxy": <- autoLogin(username) | authProxyError(msg)
  else:
    if stored JWT: emit loginByToken(jwt) -> {ok:true}
         {ok:false, msg:"authInvalidToken"|"authUserInactiveOrDeleted"} => discard token, prompt login
    else emit login({username,password}) ->
         {ok:true, token} => store token
         {tokenRequired:true, challenge} => emit login({username,password,token:"123456",challenge}) -> {ok:true, token}
  <- userAccess { role, grants }        (role=="restricted" -> use scopedList / scopedRequest only)
  <- stackList / agentList etc. (admin only)
on 'refresh' event: reconnect + loginByToken
on every reconnect: loginByToken again
after twoFactor:enable/disable/regenerate: replace stored JWT with the returned `token`
after changePassword: stored JWT is invalid -> re-run `login`
```

### 11. UNVERIFIED / not read

- Socket.IO server/client library versions (check `package.json`).
- `backend/oidc.ts` lines 166-373 (account resolution, role mapping) only skimmed via grep.
- Exact mount prefixes of routers other than those listed; request/response schema of non-auth REST endpoints (out of scope).
- Whether the `agent` proxy event (`emit("agent", endpoint, event, ...args)`) enforces per-endpoint auth beyond the main `userID` check (not part of this review).
- Behaviour of `reverse-agent.ts` token issuance (`issueReverseAgent`) beyond noting it is a separate credential for agent linking.
- How a native client satisfies a forward-auth reverse proxy (outside server code).
- Whether `apiRateLimiter` is used anywhere.
- Test files (`*.test.ts`) were not read; they might document extra edge cases.

---

# Part B: Stacks, containers and updates
## dockge-enhanced 1.5.4: stack / container / update API reference (for an Android client)

Source: `C:\dev\dockge-for-android\reference\dockge-enhanced` (read-only). All paths below are relative to that root. `file:line` citations refer to the files as read. Anything not confirmed is marked UNVERIFIED.

Version: `package.json:3` = 1.5.4.

---------------------------------------------------------------------------------------------------

### 0. Transport, envelope, login (needed to use anything else)

#### 0.1 Socket.IO connection
- Server is a plain `socket.io` Server on the same HTTP(S) port as the web UI, default path `/socket.io` (`backend/dockge-server.ts:343-386`; no custom `path` is set).
- Origin check: a request WITHOUT an `Origin` header is accepted (non-browser clients) (`backend/socket-origin.ts:7-10`, `backend/dockge-server.ts:352-367`). So the Android client must NOT send an `Origin` header (OkHttp/socket.io-client-java does not by default).
- Optional connect header `endpoint: <string>` marks a federation (server-to-server) client. A phone client must NOT send it: with no header `socket.endpoint = ""` (`backend/dockge-server.ts:403-407`). If it is sent, the socket is treated as a federated agent link, is rate-limited, and restricted users are denied (`backend/dockge-server.ts:409-411, 485-518, 529-531`).
- Right after connect the server emits `info` (version hidden before login) (`backend/dockge-server.ts:419, 702-733`). Then depending on auth mode it may emit `setup`, `autoLogin` (auth disabled or trusted-proxy), or `authProxyError` (`backend/dockge-server.ts:441-464`).

#### 0.2 The "agent" envelope (how every stack/container event is addressed)
Client -> server (ALL stack/container events below):
```
socket.emit("agent", <endpoint:string>, <eventName:string>, ...args, <ackCallback>)
```
- `backend/socket-handlers/agent-proxy-socket-handler.ts:14-47`.
- `endpoint === ""` (or equal to `socket.endpoint`) -> run locally: `agentSocket.call(eventName, ...args)` (`:30-33`). This is what the Android app uses for a directly connected instance.
- `endpoint === "##ALL_DOCKGE_ENDPOINTS##"` (`common/util-common.ts:47`) -> fan-out to every federated agent, with NO ack (`:26-29`, `backend/agent-manager.ts:715-724`).
- any other string -> proxied through the instance's own federation links to that remote agent (`:34-37`, `backend/agent-manager.ts:644-697`).
- Event name and endpoint must be strings else the ack is called with `{ok:false,msg:...}` (`:19-24, 38-45`).
- PITFALL: `AgentSocket.call` silently does nothing if `eventName` is unknown (`common/agent-socket.ts:9-14`): the ack is NEVER called. Use client-side timeouts.
- The ack callback must be the LAST argument; handlers read it positionally.
- Authentication gate: every handler calls `checkLogin(socket)` -> throws `Error("You are not logged in.")` if `socket.userID` is unset (`backend/util-server.ts:50-54`). Also a default-deny middleware: unauthenticated sockets may only emit `login`, `loginByToken`, `loginByOidcCode`, `setup`, `needSetup`; users with role != admin ("restricted") may only emit `logout`, `scopedList`, `scopedRequest`, `scopedPassword`, `twoFactor:*` (`backend/user-access.ts:40-66`). The `agent` event is therefore admin-only; a restricted user would use `scopedRequest` instead (see 0.5, UNVERIFIED in detail).

Server -> client for agent-routed events:
```
socket.on("agent", (eventName:string, ...args))
```
- `dockgeSocket.emitAgent(event, ...args)` does `socket.emit("agent", event, ...args)` and, if `args[0]` is an object, mutates it by setting `args[0].endpoint = socket.endpoint` (`""` for direct clients) (`backend/dockge-server.ts:394-401`).
- Events delivered this way: `stackList`, `terminalWrite`, `terminalExit` (the only `emitAgent` callers: `backend/dockge-server.ts:862`, `backend/terminal.ts:140`, `backend/terminal.ts:166`).

#### 0.3 Ack/response shapes
- Success: whatever the handler passes, almost always `{ ok: true, ... }`; action handlers also add `msg` and `msgi18n: true`, e.g. `{ok:true, msg:"Started", msgi18n:true}` (`backend/agent-socket-handlers/docker-socket-handler.ts:785-789`).
- Error: `{ ok: false, msg: <error.message>, msgi18n: true }` (`backend/util-server.ts:62-84`). `msg` is often an i18n key OR a plain English message (e.g. `"Failed to start, please check the terminal output for more information."`, `backend/stack.ts:1456`). The `ValidationError` branch (`type: ERROR_TYPE_VALIDATION`=1) is UNREACHABLE because `ValidationError extends Error` and the `instanceof Error` check comes first (`backend/util-server.ts:56-60, 68-80`), so `type` is never sent.
- PITFALL: if a handler throws a non-Error (e.g. a bare string), `callbackError` only logs and NEVER calls the ack. This happens when another operation holds the same compose terminal: `Terminal.exec` does `reject("Another operation is already running, please try again later.")` (a string) (`backend/terminal.ts:239-242`). The client then hangs: use a timeout.
- Other known error messages: `"Another operation is active on this stack; retry when it finishes"` and `"Rolling update pending: waiting for connections or automatic recovery"` (`backend/stack-mutation.ts:44, 47`); `"access.denied"` (scoped/restricted) (`backend/user-access.ts:60-62`).

#### 0.4 Login events (client -> server, NOT wrapped in "agent")
- `login` : `emit("login", {username, password, token?:string(2FA code), challenge?:string, turnstileToken?}, cb)` (`backend/socket-handlers/main-socket-handler.ts:225-282`).
  - success: `{ ok:true, token:<JWT> }` (`:273-274`)
  - 2FA required: `{ tokenRequired:true, challenge:<id> }` (`:256-258`); resend `login` with `token` and `challenge`.
  - failure: `{ ok:false, msg: "authIncorrectCreds" | "authTurnstileFailed" | "oidc.localLoginDisabled" | "twoFactor.*" | "twoFactor.unavailable", msgi18n:true }` (`:276-280`).
  - Rate limited via `loginRateLimiter` (`:236`). Turnstile may be required if enabled (`info.turnstileEnabled`, `backend/dockge-server.ts:729`); UNVERIFIED how `verifyTurnstile` behaves when disabled (assumed to pass).
- `loginByToken` : `emit("loginByToken", <jwt>, cb)` -> `{ok:true}` or `{ok:false,msg:"authInvalidToken"|"authUserInactiveOrDeleted",msgi18n:true}` (`:163-222`). Only works when auth mode is `local` (`:169`).
- After a successful login the server (a) emits `userAccess` (`{role:"admin"|"restricted", grants:[...]}`, `common/user-access.ts:10-14`, `backend/dockge-server.ts:527-533`), (b) `info` with full version, (c) pushes `stackList` and `agentList` (`backend/dockge-server.ts:549-556`).
- `needSetup` (`:156`), `setup` (`:106`) exist for first-run; `logout` is allowed in LIMITED_EVENTS (handler location not read; UNVERIFIED).
- Auth modes (`backend/auth.ts:17`): `"local" | "disabled" | "trusted-proxy"`. `info.authMode` tells which (`backend/dockge-server.ts:723`). In `disabled`/`trusted-proxy` the server auto-logs the socket in and emits `autoLogin`.

#### 0.5 Restricted (scoped) users
`scopedList` -> `{ok:true,data:StackGrant[]}`; `scopedRequest` with `{endpoint, stack, service?, permission}` where permission is one of `view|logs|start|stop|restart|update|recreate` (`backend/scoped-users.ts:225-295`, `common/user-access.ts:1-9`). Errors are collapsed to `{ok:false,msg:"access.operationFailed"|"access.denied",msgi18n:true}`. Response shape of a successful `scopedRequest` is built by `scopedResponse(...)`, not read: UNVERIFIED.

---------------------------------------------------------------------------------------------------

### 1. Socket.IO events: stacks and containers (all sent as `emit("agent", "", <event>, ...args, cb)`)

Handler file for all of these: `backend/agent-socket-handlers/docker-socket-handler.ts` (abbreviated DSH). All take `stackName: string` = the stack directory name (`^[a-z0-9_-]+$`, `backend/stack.ts:511`). Non-string -> `ValidationError("Stack name must be a string")` -> `{ok:false,msg:...}`.

Every mutating action: (1) runs `docker compose ...` inside a server-side pty "terminal" that streams output to the calling socket (see section 1.4), (2) waits for the process to end, (3) writes an audit entry, (4) calls `server.sendStackList()` (which pushes a fresh `stackList` to all logged-in sockets), (5) acks. So the ack arrives AFTER completion; progress arrives meanwhile as `terminalWrite`.

#### 1.1 Stack list / state

| Event | Args | Ack | Cite |
|---|---|---|---|
| `requestStackList` | `(cb)` | `{ok:true,msg:"Updated",msgi18n:true}`; triggers `sendStackList()` broadcast | DSH:615-627 |
| `getStack` | `(stackName, cb)` | `{ok:true, stack: <full stack object, 2.1>}`; SIDE EFFECT: if `isManagedByDockge`, joins the socket to the stack's combined-logs terminal and starts streaming logs (`terminalWrite` on `combined--<stack>`) | DSH:573-594, `backend/stack.ts:370-414, 1947-1982` |
| `serviceStatusList` | `(stackName, cb)` | `{ok:true, serviceStatusList:{<service>:{state:string, ports:string[], startedAt:string\|null}}, lastUpdated:string\|null, lastStartedAt:string\|null, lowPowerMode:boolean}` | DSH:1204-1224, `backend/stack.ts:2136-2234` |
| `containerInstances` | `(stackName, cb)` | `{ok:true, instances: ContainerInstance[]}` (2.3) | DSH:1151-1165, `backend/container-instances.ts:3-13` |
| `containerInstanceLogs` | `(stackName, containerId, cb)` | `{ok:true, logs:string}` last 200 lines w/ timestamps, stdout+stderr, truncated to last 512 KiB | DSH:1187-1201, `backend/stack.ts:2123-2134` |
| `dockerStats` | `(cb)` | `{ok:true, dockerStats:{<containerName>:<raw docker stats JSON object>}}` (`docker stats --format json --no-stream`; fields are docker CLI's: `Name`, `CPUPerc`, `MemUsage`, `MemPerc`, `NetIO`, `BlockIO`, `PIDs`, `ID`, `Container`); also triggers `sendStackList()` | DSH:1227-1240, `backend/dockge-server.ts:891-918` |
| `stackStatsGet` | `(cb)` | `{ok:true, enabled, data:{<stack>:{cpu:number,memUsed:number}}, containerData:{<name>:{cpu,memUsed}}, lowPowerMode, available?, sampledAt?}`; `enabled:false` => empty data unless admin turned on `stackStatsEnabled` setting | DSH:256-264, `backend/routers/system-stats-router.ts:304-312, 374-395`, `backend/stack-resource-stats.ts:1-16` |
| `stackResourceHistoryGet` | `(name, preset, cb)` | `{ok:true, enabled, data}` (history series; shape of `data` NOT read: UNVERIFIED) | DSH:266-276 |
| `instanceContainerCountsGet` | `(cb)` | `{ok:true, data:{total,running,stopped,paused}}` (all containers on host via `docker ps -a`) | DSH:246-254, `backend/container-counts.ts:5-31` |
| `instanceSystemStatsGet` | `(cb)` | `{ok:true, data:{cpu, ram:{used,total,percent}, disk:{mount,used,total,percent}, disks:[...], diskDisplayMode, host, hostNavbarDisplay}, lowPowerMode, powerWatch:{...}}` | DSH:38-46, 204-214, `backend/routers/system-stats-router.ts:397-447` |
| `instanceHomeSummaryGet` | `(cb)` | `{ok:true, data:{images:{enabled,count}, backup:{enabled,timestamp,intervalHours}, containers:{enabled,count}, trivy:{enabled,count}}}`. `images.count` = number of `imageStatusStore` entries with `hasUpdate && !error` = the "updates available" badge count | DSH:216-244 |
| `getDockerNetworkList` | `(cb)` | `{ok:true, dockerNetworkList:string[]}` | DSH:1243-1254 |
| `getStackVolumeUsage` | `(stackName, cb)` | `{ok:true, data:[{id,name,service,state,mounts:[{type,name,source,destination,size:number\|null,error?}]}]}` | DSH:596-612, `backend/stack.ts:2016-2070` |
| `stackActionCommandPreview` | `(stackName, cb)` | `{ok:true, ...preview}` of the compose commands each action would run | DSH:1411-1425, `backend/stack.ts:~1430-1447` |
| `stackPinsGet` / `stackPinSet` | `(cb)` / `(name, pinned:boolean, cb)` | `{ok:true, pinnedStacks:string[]}` | DSH:346-368 |

NOTE: there is NO per-container "stats stream" event. Per-container resource data is `stackStatsGet.containerData` / `.instances` (via REST `GET /api/system/stack-stats` with `forHistory` only internal) and `dockerStats`. Both are polled (pull) by the client.

#### 1.2 Stack actions (mutating)

| Event | Args | Does (compose command) | Ack on success | Cite |
|---|---|---|---|---|
| `deployStack` | `(name, composeYAML, composeENV, isAdd:boolean, composeOverrideYAML?:string, cb)` (old clients may omit override; handler detects function in that slot) | save files, then `docker compose up -d --remove-orphans` | `{ok:true,msg:"Deployed",msgi18n:true}`; server then joins socket to combined logs | DSH:370-392, `backend/stack.ts:1019-1043` |
| `saveStack` | same args | write compose.yaml / .env / compose.override.yaml only (validates YAML) | `{ok:true,msg:"Saved",msgi18n:true}` | DSH:394-414, 1428-1455 |
| `startStack` | `(stackName, cb)` | `up -d --remove-orphans` (start-guard + network repair first) | `{ok:true,msg:"Started"}` | DSH:774-797, `stack.ts:1450-1460` |
| `stopStack` | `(stackName, cb)` | `stop` | `{ok:true,msg:"Stopped"}` | DSH:800-823, `stack.ts:1463-1470` |
| `restartStack` | `(stackName, cb)` | `stack.restart` (impl `stack.ts:1569`, not read: UNVERIFIED exact command) | `{ok:true,msg:"Restarted"}` | DSH:856-876 |
| `recreateStack` | `(stackName, cb)` | `up -d --force-recreate --remove-orphans` | `{ok:true,msg:"Recreated"}` | DSH:879-899, `stack.ts:1723-1732` |
| `updateStack` | `(stackName, cb)` | alias of `pullAndRecreate` (see 4.4) | `{ok:true,msg:"Updated"}` or `msg:"serviceRollout.pending"` if a rolling update is pending; also clears that stack's update badges from `imageStatusStore` | DSH:902-928, `stack.ts:1759-1764` |
| `pullAndRecreateStack` | `(stackName, cb)` | `pull` then `up -d --force-recreate --remove-orphans` | `{ok:true,msg:"PulledAndRecreated"}` or `"serviceRollout.pending"` | DSH:931-957, `stack.ts:1802-1830` |
| `buildAndRecreateStack` | `(stackName, cb)` | `build --pull <svc>` then `up -d --remove-orphans`; error if no `build:` service | `{ok:true,msg:"BuiltAndRecreated"}` | DSH:959-987, `stack.ts:1907-1945` |
| `downStack` | `(stackName, cb)` | `down` | `{ok:true,msg:"Downed"}` | DSH:1106-1127, `stack.ts:1750-1757` |
| `setStackPaused` | `(stackName, paused:boolean, cb)` | `pause`/`unpause` | `{ok:true,msg:"Paused"\|"Resumed"}` | DSH:826-853, `stack.ts:1473-1481` |
| `deleteStack` | `(name, options?:{removeFiles?,force?,confirmExternalSourceDelete?,confirmExternalSourcePath?}, cb)` | `down --remove-orphans`, then delete the dir (unless `removeFiles:false`); old clients: 2nd arg may be the cb | `{ok:true,msg:"Deleted"}` | DSH:416-453, `stack.ts:1055-1098` |
| `saveStackNote` / `saveStackDisplayName` / `saveStackReadme` | `(stackName, value, cb)` | metadata only | (not fully read; UNVERIFIED ack shape) | DSH:989-1066 |
| `getStackStartGuardStatus` / `saveStackStartGuard` | `(stackName, startGuard, cb)` | start guard (mount/systemd preconditions) | UNVERIFIED | DSH:1069-1103 |

#### 1.3 Service-level and container-level actions

- `serviceAction` : `(stackName, serviceName, action, cb)`; `action` in `"start"|"stop"|"restart"|"update"|"recreate"|"pull-recreate"` else `ValidationError("Unsupported service action")`; `serviceName` must match `^[a-zA-Z0-9_.-]+$`. Ack: `{ok:true, msg: "serviceRollout.pending" | "Service action completed", msgi18n: <bool, true only for pending>, affectedServices:string[]}` (DSH:1129-1149, `stack.ts:1667-1720`).
  - start: `up -d <svc>`; stop: `stop <svc(+network-namespace dependants)>`; restart: restarts the service (plus dependants sharing a network namespace); update / pull-recreate: `pull <svc>` then targeted `up -d --force-recreate --no-deps` (or the rolling-update path if the service has label `dockge.rollout.enabled`); recreate: targeted force-recreate without pull.
  - `affectedServices` can contain MORE than the requested service (services sharing `network_mode: service:x`).
  - Streams via the stack's compose terminal (same `compose--<stack>` terminal).
- `containerInstanceAction` : `(stackName, containerId, action, cb)`, `action` in `"start"|"stop"|"restart"` only. Runs plain `docker <action> <id>` (not compose; 30 s timeout; NO terminal streaming). Ack `{ok:true}`. Errors if the container shares a network namespace with others: "use the service action instead" (DSH:1167-1185, `stack.ts:2103-2121`). `containerId` must match `^[a-f0-9]{12,64}$` and belong to the stack (`container-instances.ts:71-80`).
- `containerInstances` / `containerInstanceLogs`: see 1.1.
- Logs terminal control (in `backend/agent-socket-handlers/terminal-socket-handler.ts`):
  - `joinStackLogsTerminal(stackName, serviceName|""|undefined, timestamps:boolean, since?:string, cb)` -> `{ok:true}`; starts `docker compose logs -f [--tail 100 | --since X] [--timestamps] [service]` streamed as `terminalWrite` to terminal name `combined--<stack>` (+`-service-<svc>`, + `-since-<since>`, + `_ts` suffix when timestamps) (`terminal-socket-handler.ts:172-201`, `stack.ts:1951-1982`, `common/util-common.ts:290-295`). `since` allowed: `24h|72h|168h|336h` or strict ISO-8601 `...Z` timestamp (`stack.ts:1998-2014`).
  - `leaveStackLogsTerminal(stackName, serviceName, timestamps, since?, cb)`, `leaveCombinedTerminal(stackName, cb)` (`terminal-socket-handler.ts:150-233`).
  - `terminalJoin(terminalName, cb)` -> `{ok:true, buffer:string}` replays the buffered output so far (`:122-147`). Use this for late attach to an in-progress action.
  - `interactiveTerminal(stackName, serviceName, shell, cb)` (docker compose exec; then `terminalInput(name, data, cb)` and `terminalResize(name, rows, cols)`); `mainTerminal`, `checkMainTerminal` (host console, only if server started with enableConsole) (`:12-120, 235-269`). Container exec terminal name: `container-exec-<endpoint>-<stack>-<service>-<index>` (`common/util-common.ts:301-303`).

#### 1.4 Progress / terminal output streaming (the only progress channel)
- Server -> client: `on("agent", "terminalWrite", terminalName:string, data:string)` and `on("agent","terminalExit", terminalName:string, exitCode:number)` (`backend/terminal.ts:134-142, 163-167`). `data` is raw pty output INCLUDING ANSI escape sequences and CR (compose progress bars). 105 cols x 18 rows pty (`common/util-common.ts:108-111`, `terminal.ts:245`). No structured percentages; parse text or just render it.
- Terminal name for stack actions: `"compose-" + endpoint + "-" + stack` = `compose--<stack>` for a direct connection (`common/util-common.ts:282-284`; `endpoint` is `""`). Logs: `combined--<stack>`.
- The calling socket is joined automatically to the action's terminal (`Terminal.exec`, `terminal.ts:253-255`). Only sockets that joined get output. Other connected clients do NOT see it unless they call `terminalJoin`/log joins (and even then `terminalJoin` only returns the buffer; it does not add the socket to the live list: `terminal-socket-handler.ts:134-143` does not call `.join(socket)`; the live-streaming join happens only for the initiating socket and the logs-terminal handlers). So a second client cannot live-follow someone else's update; it can poll `terminalJoin` for the buffer. (Inference from code; UNVERIFIED by runtime.)
- Only one exec per terminal name at a time: second attempt rejects with a string (see 0.3 pitfall).
- Terminal has `terminalExit` when the process ends; then the ack of the action fires (the exec promise resolves on exit, `terminal.ts:257-259`). Exit code != 0 -> action throws -> `{ok:false,msg:"Failed to ...; check the terminal output..."}`. Exception: `deployStack` returns the last ~16 KiB of ANSI-stripped output as `msg` on failure (`stack.ts:1033-1038`).
- Output buffer: `getBuffer()` joins an internal `LimitQueue` (size not read: UNVERIFIED).

#### 1.5 Server -> client push events (complete list found by grep of `.emit(`)
| Event | Payload | Cite |
|---|---|---|
| `info` | `{version, latestVersion, isContainer, primaryHostname, authMode, authModeManaged, oidc:{buttonLabel,localLoginDisabled}\|null, turnstileEnabled, turnstileSiteKey, schedulingTimezone}`; before login `version/latestVersion/isContainer` are undefined | `backend/dockge-server.ts:702-733` |
| `agent` -> `stackList` | `{ok:true, stackList:{<name>:<simple stack>}, endpoint:""}` (2.1). Pushed: on login, after every action, and EVERY 10 s via cron (`sendStackList(true)` = cached managed list + fresh `docker compose ls` status) to every logged-in non-restricted socket | `backend/dockge-server.ts:549-556, 635-642, 839-868` |
| `agent` -> `terminalWrite` / `terminalExit` | see 1.4 | |
| `agentList` | `{ok:true, agentList:{"":{url:"",username:"",endpoint:"",displayName,tags},<endpoint>:{url,reverse,username,endpoint,displayName,suppressOfflineAlerts,tags}}}` | `backend/agent-manager.ts:726-752`, `backend/models/agent.ts:21-30` |
| `agentStatus` | `{endpoint, status:"connecting"\|"online"\|"offline", msg?}` | `backend/agent-manager.ts:209-212, 249-260` |
| `userAccess` | `{role,grants}` | `backend/dockge-server.ts:533` |
| `autoLogin`, `setup`, `authProxyError`, `refresh`, `schedulingTimezone`, `stackGroups:changed` | misc | `dockge-server.ts:449-461, 965, 982`; `main-socket-handler.ts:437`; `stack-groups-socket-handler.ts:42` |

There is NO push event for "image update available", for container state change, or for update progress/result other than terminal output. Everything else is pull.

---------------------------------------------------------------------------------------------------

### 2. Data model

#### 2.1 Stack object (in `stackList` and `getStack`)
`Stack.toSimpleJSON(endpoint)` (`backend/stack.ts:416-447`):
```
{
  name: string,                 // dir name; id used in every event
  displayName: string,          // from .dockge-meta.json ("" if unset)
  status: number,               // see 2.2
  tags: [],                     // always [] here
  isManagedByDockge: boolean,   // true if a dir exists under the stacks dir; false = discovered compose project (not manageable until adopted)
  composeFileName: string,      // e.g. "compose.yaml"
  supportsServiceRollout: true,
  supportsPostUpdateCleanup: true,
  rollingUpdates: [{service:string, phase:Phase, attention:boolean}],  // includes finished
  buildServices: string[],      // services with a build: key
  createdAt: string|null, createdAtEstimated: boolean,
  lastUpdated: string|null,     // ISO; last pull/deploy (from .dockge-meta.json)
  endpoint: string,             // "" for a direct connection
  isExternal: boolean,
  externalPath?: string, externalProject?: string,
  externalConfigFiles?: string[], externalEnvFiles?: string[]
}
```
`getStack` -> `stack.toJSON(endpoint)` = the above PLUS (`backend/stack.ts:400-413`):
```
composeYAML: string,            // compose.yaml text
composeENV: string,             // .env text
composeOverrideYAML: string,    // compose.override.yaml text ("" if none)
note: string, readme: string, readmeUnavailable: boolean,
startGuard: StartGuard,         // {enabled, conditions[], watch, onFailure:"none"|"stop", onRecovery:"none"|"start", failureDelaySeconds, recoveryDelaySeconds} (stack.ts:114-129)
primaryHostname: string, configuredPrimaryHostname: string,
portLinkAddressMode: "ip"|"hostname", portLinkIp: string
```
`lastStartedAt` is NOT in the stack object; it comes from `serviceStatusList` response. The stack list does NOT contain per-service data, image, or health: those need `serviceStatusList` and/or `containerInstances`, or parsing `composeYAML` (`image:` keys) from `getStack`.

Files: compose is `compose.yaml` / `docker-compose.yaml|yml` / `compose.yml` (`common/util-common.ts:118-123`); `.env` is `<stackdir>/.env` (`stack.ts:546-549`); override is `compose.override.yaml` (`stack.ts:282`). For external stacks, `composeYAML` is read from the registered config file (`stack.ts:538`).

#### 2.2 Status codes (`common/util-common.ts:50-55`)
```
0 UNKNOWN        (not running / not in `docker compose ls`; also unmanaged-but-unreadable)
1 CREATED_FILE   ("draft": compose file exists, never deployed)
2 CREATED_STACK  (compose project created but containers only "created")
3 RUNNING        (at least one service running)
4 EXITED
5 PAUSED
```
Derivation (`backend/stack.ts:1288-1303`, from the text of `docker compose ls --all --format json`): contains "paused" -> 5; "running" -> 3 (even if some exited); "exited" -> 4; "created" -> 2; else 0. Edge: when any service is flagged to ignore (label `LABEL_STATUS_IGNORE`) or the string mixes "running/paused" with "exited", it inspects containers and applies `resolveMixedComposeStatus` (non-zero exit -> EXITED; clean exits ignored) (`stack.ts:64-92, 1305-1346`). Names for UI: `statusName` = `draft|created_stack|running|exited|paused|unknown` (`util-common.ts:57-72`).

#### 2.3 Per-container / per-service data
Two sources, both pull:

(a) `serviceStatusList` (per SERVICE) (`backend/stack.ts:2136-2234`): from `docker compose ps --format json`:
- `state` = Docker Compose `Health` if non-empty (`"healthy"|"unhealthy"|"starting"`) ELSE `State` (`"running"|"exited"|"paused"|"restarting"|"created"|"dead"...`) (`stack.ts:2181`). Health and state are therefore merged into ONE string; you cannot tell "running+healthy" from "running" with no healthcheck here beyond the value `healthy`.
- `ports: string[]` = entries from compose's `Ports` containing `->` (e.g. `"0.0.0.0:8080->80/tcp"`) (`stack.ts:2180`).
- `startedAt` = Docker `State.StartedAt` ISO (nullable; skipped if `0001-01-01T00:00:00Z`) -> uptime = now - startedAt (`stack.ts:2190-2215`). Only for running-or-not containers that have ever started.
- Cached for a short TTL (`intervals().serviceStatusTtl`, low-power mode dependent) and invalidated on actions (`stack.ts:2155-2158`). Returns empty if `!isManagedByDockge` (`stack.ts:2147-2152`).
- Only includes containers `docker compose ps` returns by default (running + recently stopped; NOT `--all`), so fully stopped services may be absent. `containerInstances` uses `--all`.

(b) `containerInstances` (per CONTAINER; `docker compose ps --all --format json`) (`backend/container-instances.ts:3-13, 16-69`):
```
ContainerInstance {
  id: string,          // hex, 12..64 chars
  name: string,        // container name, no leading "/"
  service: string,     // compose service
  state: string,       // "running","exited",...
  health: string,      // "healthy"|"unhealthy"|"starting"|"" (separate field here!)
  image: string,       // image reference as run, e.g. "nginx:latest"
  ports: string,       // raw compose "Ports" string (comma separated)
  createdAt: string,   // docker format "2026-01-02 03:04:05 +0000 UTC"
  runningFor: string   // docker human string e.g. "3 hours ago"
}
```
This is the only place that gives the running image per container and health separately, and `runningFor`. There is no numeric started-at here; combine with `serviceStatusList.startedAt` by `service`.

(c) Whole-host container list REST: `GET /api/docker/containers` -> `{ok, containers:[{id,name,image,state,status,createdSince,stackName,service}]}` (`backend/routers/docker-resources-router.ts:333-350`). `status` is docker's human string e.g. "Up 3 hours (healthy)".

(d) Resource usage: `dockerStats` (CLI raw) and `stackStatsGet` (`cpu` percent number, `memUsed` bytes number per stack and per container name) (1.1).

#### 2.4 Rolling update states
`rollingUpdates: [{service, phase, attention}]` in each stack (`backend/rollout-recovery.ts:74-83`); `phase` enum is `Phase` type (not read: UNVERIFIED values). Opt-in per service by label `dockge.rollout.enabled` (`common/service-rollout.ts:4`). When pending, update acks return `msg:"serviceRollout.pending"`.

---------------------------------------------------------------------------------------------------

### 3. Multi-instance / agents / federation

- Model: each Dockge-Enhanced instance is fully independent; one "primary" instance can link others as "agents" (federation). The primary opens an outbound Socket.IO client per agent (shared process-wide, `backend/agent-manager.ts:59-61, 447-614`), logs in with stored `username/password` or with a federation JWT using `loginByToken` when username equals `__dockge_federation_token__` (`:25, 27-41`), and connects with HTTP header `endpoint: <host[:port] of the agent URL>` (`:497-499`). Endpoint id = `new URL(url).host` (`:453`).
- Addressing for browsers/clients connected to the primary: the first arg of `emit("agent", endpoint, event, ...)`. `""` = the instance you are connected to; any other string = proxy to that agent; `##ALL_DOCKGE_ENDPOINTS##` = broadcast without ack (`agent-proxy-socket-handler.ts:26-37`). Proxied events are forwarded as `client.emit("agent", endpoint, eventName, ...args)` on the agent link (`agent-manager.ts:696`) and replies come back as `"agent"` events relayed to all subscribed browser sockets, with `args[0].endpoint` set to the agent's endpoint (via the agent's own `emitAgent` + header) (`agent-manager.ts:592-596`).
- Reverse agents (`url` starting `reverse://`): agent connects to the primary instead; the primary sends `reverse.emit("command", eventName, args, ack)` with a 30 min timeout (`agent-manager.ts:644-664`).
- Instance discovery from a primary: server pushes `agentList` (all linked agents with `displayName`, `url`, `reverse`, tags) and `agentStatus` per endpoint (`online|offline|connecting`) (`agent-manager.ts:201-214, 726-752`). The local instance is key `""` (`:735`).
- Mgmt events (admin): `addAgent`, `removeAgent`, `renameAgent`, `reauthenticateAgent`, `setAgentTags`, `setAgentOfflineAlertSuppressed`, `createAgentFederationToken`, `createReverseAgent`, `applyAgentMesh`, `repairAgentMesh`, `validateAgentMeshPeer` (`backend/socket-handlers/manage-agent-socket-handler.ts:57-339`). Payload shapes not read: UNVERIFIED.
- RECOMMENDATION for the Android app (inference): since the app talks to several independent instances, connect directly to each instance (endpoint `""` always), do not rely on federation. If a user DOES want federation, one socket to a primary lets you address agents by `endpoint` and receive `stackList` per endpoint; note that the primary only pushes `stackList` for stacks of the endpoint stamped in `args[0].endpoint`, and every `stackList` event must be keyed by `endpoint` on the client. Remote agents' stackLists are forwarded and stamped with their endpoint (`backend/agent-manager.ts:91-102` for reverse links; `agent-manager.ts:592-596` for outbound links).
- A client that sends the `endpoint` header is treated as an agent: at most `FEDERATION_MAX_CONCURRENT_INBOUND` concurrent sockets per endpoint (value not read), inbound reconnect-storm guard, and restricted users denied (`backend/dockge-server.ts:359-375, 485-518`). Do not send the header from the Android app.
- Minimum compatible peer version `1.4.0` (`agent-manager.ts:603`).

---------------------------------------------------------------------------------------------------

### 4. Image update detection ("update available")

#### 4.1 Yes, the fork detects updates (ImageWatcher), per image, by registry digest comparison
- Implementation: `backend/watchers/image-watcher.ts` (3156 lines). Docs: `docs/image-update-verification.md` (not read).
- Opt-in: watcher is only active when its setting `enabled` is true (`WatcherSettings.enabled` default false, `image-watcher.ts:1336`; `startIfEnabled` at `backend/dockge-server.ts:663`; `start()` `image-watcher.ts:1679-1708`). Setting an image's auto-update mode to anything but `ignored` auto-enables it (`DSH:525-527`, `watcher-router.ts:285-287`). If it is disabled, `imageStatusStore` is empty/stale. UNVERIFIED whether a REST `POST /api/watcher/image/run` works while disabled (the handler calls `runCheck()` directly without an enabled check, `watcher-router.ts:220-225`, so it likely does).

#### 4.2 How it detects
- Per stack, per image: the key is `"<stackName>::<imageReference>"` (e.g. `"media::lscr.io/linuxserver/sonarr:latest"`) (`image-watcher.ts:1937`, `DSH:493`). Images come from the resolved compose model (`extractImagesFromCompose`, `:1930`), so `.env` interpolation is honored.
- Registry check: Registry v2 manifest HEAD/GET (no layer download), with full 401 WWW-Authenticate bearer-token flow, stored per-registry credentials, platform-aware (index vs platform digest) (`image-watcher.ts:813-920`, `checkOneImage` `:2852-2967`). Digest pinned images (`@sha256:`) never report updates (`:829-832`).
- Comparison: local RepoDigests/digest vs remote platform digest or index digest. `hasUpdate = missingLocalTag || (localDigests exist && !match) || runningDrift` where `runningDrift` = a running container uses an older image ID than the current local tag (`:2945-2961`).
- Schedule: full scan on a cron `0 */<intervalHours> * * *` (default `intervalHours: 6`, `:1337, 1681-1687`) in the configured scheduling timezone, plus an initial scan at start (`:1706-1708`), plus a targeted poll of "immediate"-policy images every `immediateCheckMinutes` (default 5, 0 disables; range 5-1440) (`:216-224, 1689-1694`). On demand: `POST /api/watcher/image/run` (REST) or setting an image to `immediate` (triggers a targeted check; `watcher.runImmediateCheck(key)`, `DSH:529-531`). Registry rate limit (429) cooldowns are honored (`:2879-2899`).
- Skipped digests ("skip this release"): `ignoredDigests[key]` suppresses `hasUpdate` and sets `ignoredDigest` (`:1960-1964, 2500-2520`).

#### 4.3 Where the result is stored and exposed (pull only; nothing is pushed over Socket.IO)
In-memory `imageStatusStore: Map<string, ImageStatus>` (`image-watcher.ts:347`). Shape (`:289-299`):
```
ImageStatus {
  image: string,            // "nginx:latest" (reference as in compose)
  stack: string,
  localDigest: string,      // "sha256:..." of the local image (or "" )
  remoteDigest: string,     // "sha256:..." registry platform digest (or index digest)
  hasUpdate: boolean,
  lastChecked: string,      // ISO date
  ignored?: boolean,        // auto-update mode "ignored" for this key
  ignoredDigest?: string,   // remote digest the user chose to skip
  error?: string            // check failure; human text, may be French
}
```
There is NO changelog / release-notes / new-version-tag field per image. A search of `backend/` and `common/` for release notes / changelog finds only the Dockge-Enhanced SELF-update (`backend/self-update/manager.ts:867`, `backend/watchers/self-update-checker.ts:163-167`), not per-image. The only per-image "link" helper is `imageRegistryUrl(image)` in `common/util-common.ts:137-...` (maps an image ref to its GitHub/Docker Hub/Quay page) and is in `common/` so it is shareable logic to re-implement in Kotlin.
Access:
- Socket (via `agent` envelope): `watcherImageStatusGet(cb)` -> `{ok:true, data:ImageStatus[]}` (all stacks) (DSH:455-465).
- REST: `GET /api/watcher/image/status` -> `{ok:true,data:ImageStatus[]}` (`watcher-router.ts:130-134`).
- Count only: `instanceHomeSummaryGet.data.images.count` (DSH:230-231).
- Auto-update config: socket `watcherImageAutoUpdateGet(cb)` / REST `GET /api/watcher/image/auto-update` -> `{ok:true,data:{enabled, defaultScheduledTime:"HH:MM", autoUpdateConfig:{<key>:{mode:"immediate"|"scheduled"|"ignored", time?:"HH:MM", pause?:{enabled:boolean,until:string|null}}}, pendingAutoUpdates:string[], updatingImages:string[] /* keys currently being updated */, globalUpdatePause:{enabled,until}}}` (`image-watcher.ts:1456-1465`; REST adds `globalMaintenanceWindow`, `watcher-router.ts:231-239`).
- `watcherImageAutoUpdateSet({key, mode:"off"|"immediate"|"scheduled"|"ignored", time?, pause?}, cb)` -> `{ok:true,data:{...autoUpdateState, immediateCheckStarted:boolean}}` (DSH:479-544). Key MUST contain `"::"` else `ValidationError`.
- `watcherImageAutoUpdateBulkPreview(payload)` / `...BulkApply(payload)` with `{stack, mode, time?, preserveExisting:boolean, previewToken?(64 hex chars, required for apply)}` (DSH:546-571, `watchers/stack-auto-update.ts:10-16, 53`).
- Update history: REST `GET /api/watcher/image/update-history` -> `{ok:true,data:UpdateHistoryEntry[]}` (max 100) where `UpdateHistoryEntry={timestamp, stack, image, oldDigest, newDigest, mode:"immediate"|"scheduled"|"manual", success, verification?:"verified"|"unverified", retirementDeferred?, error?}` (`image-watcher.ts:318-330, 94`; `watcher-router.ts:205-208`). This is the best "result of an update" record, esp. for auto/scheduled updates the phone did not start.
- Rollback: `GET /api/watcher/image/rollback` -> `RollbackEntry[]` (24 h window, `expiresAt`); `POST /api/watcher/image/rollback {key}`; `DELETE /api/watcher/image/rollback/:key` (`watcher-router.ts:170-203`, `image-watcher.ts:301-316`).
- Notifications (server side, relevant to ntfy): when updates are found the watcher notifies Discord webhooks and/or an Apprise server (`notify`, `image-watcher.ts:2969-2988`); there is NO native ntfy. An Apprise `ntfy://` URL is the way to push (`appriseServerUrl`, `appriseUrls`, `:282-283`). Alternative for the Android app: poll `GET /api/watcher/image/status` or `instanceHomeSummaryGet`.

#### 4.4 How an update is applied
Four mechanisms, all pull-based from the client's view:
1. Whole stack, from the client: socket `updateStack` (or `pullAndRecreateStack`, identical): `docker compose pull` (streamed to `compose--<stack>` terminal) then `docker compose up -d --force-recreate --remove-orphans` (`stack.ts:1759-1830`). Guarded by a global Docker-update reservation: rejects with `"Another Docker update is active; retry later"`, `"Docker cleanup is active; retry later"`, `"Self-update is active; retry later"` (`stack.ts:1766-1783`). A post-update snapshot/cleanup queues old-image removal (`capturePostUpdateSnapshot`, `retainUpdateImages`). If any service has `dockge.rollout.enabled` the rolling-update path is used instead (`tryRollingUpdate`, `stack.ts:1833-1905`) and ack `msg` is `"serviceRollout.pending"`. After success the handler deletes that stack's `imageStatusStore` entries, so the badge disappears immediately (DSH:913-918) until the next scan re-adds an entry.
2. One service: socket `serviceAction(stack, service, "update"|"pull-recreate", cb)` (`pull <svc>` then targeted recreate; 1.3).
3. One image by key (what the UI "update now" does): REST `POST /api/watcher/image/update-now {key}` -> `200 {ok:true}` / `409 {ok:false,message:"Update failed or is already in progress for this image"}` / `500 {ok:false,message}`; requires an existing `ImageStatus` with `hasUpdate && !error` else throws "Image status not found; run an image check first" / "No applicable update is available for this image" (`watcher-router.ts:403-423`, `image-watcher.ts:2544-2567`). It runs `performAutoUpdate(..., "manual")`; progress is NOT streamed to a terminal (it is awaited server-side and recorded in `updateHistoryStore`; UNVERIFIED whether it uses the compose terminal, `performAutoUpdate` was not read).
4. All: REST `POST /api/watcher/image/update-all` -> `202 {ok:true,data:ManualUpdateBatch}` where `ManualUpdateBatch={running:boolean,total:number,completed:number,current:string|null,error:string|null}`; poll `GET /api/watcher/image/update-all` -> `{ok:true,data:{available:number,batch:ManualUpdateBatch}}` (`image-watcher.ts:333-339, 1577-1610`; `watcher-router.ts:136-164`). 409 if self-update running or a check/update/backup already running.
5. Automatic: per-image policy `immediate` (applied right after detection) or `scheduled` at `HH:MM` (minute cron applies pending ones, `image-watcher.ts:1696-1698`), optionally with a pause window.
- How the client learns progress/result: (a) for `updateStack`/`serviceAction`: `terminalWrite` stream + ack after exit + fresh `stackList`; (b) for `update-all`: poll the batch endpoint; (c) for `update-now` and auto updates: poll `GET /api/watcher/image/status` (entry disappears/`hasUpdate:false`), `auto-update.updatingImages` (keys in flight), and `update-history`.
- After an update the server verifies that the RUNNING image digest matches the expected digest (`verification:"verified"|"unverified"`; `image-watcher.ts:1259-1284`; doc `docs/image-update-verification.md`).

#### 4.5 Dockge-Enhanced self-update (separate)
`SelfUpdateChecker` / `SelfUpdateManager`; REST `GET /api/watcher/self/status`, `POST /api/watcher/self/check`, `GET|POST /api/watcher/self/settings`, `POST /api/watcher/self/update` (`watcher-router.ts:1094-1160`). Not needed for stack management; shapes not read: UNVERIFIED. Also `info.latestVersion` vs `info.version` (`dockge-server.ts:707-711`).

---------------------------------------------------------------------------------------------------

### 5. REST endpoints touching stacks / containers / updates

Mount points: routers are bound with `app.use(router.create(app, server))` (`backend/dockge-server.ts:119-129, 316-318`). All `/api/*` share a 600 req/min limiter (`dockge-server.ts:294-301`, message `{ok:false,message:"Trop de requêtes..."}`).

#### 5.1 Auth for REST
Admin REST (`/api/watcher`, `/api/docker`, `/api/system`, `/api/monitoring`, `/api/automation` admin, ...): `requireHttpAuth` (`backend/auth.ts:343-408`):
- Mode `disabled`: no auth. Mode `trusted-proxy`: identity from proxy header and must be admin. Mode `local`: `Authorization: Bearer <JWT>` OR query `?token=<JWT>` (`auth.ts:362-365`). The JWT is the one returned by the Socket.IO `login` ack (`token`). Must be an admin user (`auth.ts:379-381`). Revoked on password change (`:375-377`).
- Failure: `401 {ok:false,message:"Authentification requise"|"Token invalide ou expiré"}` or `"access.denied"` (`auth.ts:366-386, 401-405`).
- Avoid `?token=` in logs; prefer the header.

#### 5.2 Image watcher REST (`/api/watcher/...`, router `backend/routers/watcher-router.ts`)
| Method + path | Body / query | Response | Line |
|---|---|---|---|
| GET `/image/status` | | `{ok:true,data:ImageStatus[]}` | 130 |
| POST `/image/run` | | `{ok:true,message}` immediately; check runs in background | 220 |
| GET `/image/settings` | | `{ok:true,data:<safe settings>}` (credentials redacted by `getSettingsSafe`, exact shape UNVERIFIED) | 111 |
| POST `/image/settings` | `WatcherSettings` partial | `{ok:true}` | 116 |
| GET `/image/auto-update` | | see 4.3 | 231 |
| POST `/image/auto-update` | `{key, mode, time?, pause?}` | `{ok:true,data:{immediateCheckStarted,time}}`; 400 on bad key/mode | 241-300 |
| POST `/image/auto-update-bulk/preview` / `/apply` | see 4.3 | `{ok:true,data}` / 400 | 303-328 |
| POST `/image/update-pause`, `/image/auto-update-pause` | pause object / `{key,pause}` | `{ok:true,data:pause}` | 330-358 |
| POST/DELETE `/image/ignore-digest` | `{key,digest}` / `{key}` | `{ok:true}` | 364-397 |
| POST `/image/update-now` | `{key}` | 200 `{ok:true}` / 409 / 500 | 403-423 |
| GET/POST `/image/update-all` | | see 4.4 | 136-164 |
| GET/POST/DELETE `/image/rollback`, `/image/rollback/:key` | `{key}` | see 4.3 | 170-203 |
| GET/DELETE `/image/update-history` | | `{ok:true,data}` | 205-214 |
| POST/DELETE `/image/credentials[/:registry]` | `{registry,username,token}` | `{ok:true}` | 429-456 |
| GET `/self/status`, POST `/self/check|update`, GET/POST `/self/settings` | | self-update | 1094-1160 |
| GET/PUT/DELETE `/stack-schedules[...]` | | scheduled start/stop of stacks (shapes UNVERIFIED) | 1170-1213 |
| `/trivy/*`, `/backup/*`, `/kula/*`, `/dozzle/*`, `/powerwatch/*`, `/announcements*`, `/registry-dns/*`, `/discord/test`, `/apprise/test` | | out of scope | |
Error shape in this router: `{ok:false, message:string}` (note `message`, not `msg`).

#### 5.3 Docker resources REST (`/api/docker/...`, `backend/routers/docker-resources-router.ts`)
- GET `/containers` -> `{ok:true,containers:[{id,name,image,state,status,createdSince,stackName,service}]}` (`:333-350`).
- POST `/containers/:id/stop` -> `{ok:true,message}` / 500 `{ok:false,message}` (`:352-363`); DELETE `/containers/:id?force=true` (`:365-377`).
- GET `/images` -> `{ok:true, images:[{...ClassifiedImageReference, dockgeStacks:string[], purgeState, purgeDetail?}], stats:{uniqueImages,references,unused,dangling,used}}` (docker image inventory; `ClassifiedImageReference` fields in `docker-image-inventory.ts:47-57`, not fully read: UNVERIFIED) (`:161-200`). DELETE `/images/:imageId`, POST `/images/prune`, `/images/prune-unused`; volumes and networks CRUD; prune/auto-prune endpoints (`:202-720`) - destructive, out of scope for a status client.

#### 5.4 System stats REST (`/api/system`)
- GET `/stats` -> `{ok:true,data:{cpu,ram,disk,disks,diskDisplayMode,host,hostNavbarDisplay},lowPowerMode}` (`system-stats-router.ts:463-469`).
- GET `/stack-stats` -> same as `stackStatsGet` (`:473-475`).

#### 5.5 Monitoring REST (`/api/monitoring/...`, `backend/routers/monitoring-router.ts`)
`GET /overview`, `/crash-events`, `/health-events`, `/stack-history`, `/history`, settings and exclusions (`:74-372`). Crash-loop and healthcheck-failure event feeds could back push-style notifications; shapes not read: UNVERIFIED.

#### 5.6 Automation REST API with long-lived API tokens (most Android-friendly for stack actions)
Mounted at `/api/v1` (`backend/routers/automation-router.ts:193-275`). Auth: `Authorization: Bearer dge_<prefix>_<secret>` OR header `x-api-key: <token>` (`backend/automation/automation-manager.ts:134-138`). Token is created by an admin via `POST /api/automation/tokens` (admin JWT) with body `{name:string(<=120), permissions:string[], stacks:string[] ("*" or stack names), expiresAt?}`; response `201 {ok:true,data:{token:"dge_...", item:{id,name,prefix,permissions,stacks,createdAt,expiresAt,lastUsedAt,revokedAt}}}` (the plain token is shown once) (`automation-router.ts:89-108`, `automation-manager.ts:172-205, 482-495`). Permissions: `stack:read`, `stack:start|stop|restart|update|recreate|pull-recreate|build-recreate|backup`, `history:read` (`automation-manager.ts:13-30`). Rate-limited per token (`API_RATE_LIMIT_PER_MINUTE`, value not read).
- GET `/api/v1/stacks` -> `{ok:true,data:[<simple stack object 2.1 with endpoint:"">]}` filtered to the token's stacks (`:204-217`, `automation-manager.ts:371-374`). Note: `status` is as computed by `getStackList`.
- GET `/api/v1/stacks/:name` -> `{ok:true,data:<simple stack>}` (calls `updateStatus()` first) (`:219-228`, `automation-manager.ts:376-380`). NOT the full compose text.
- POST `/api/v1/stacks/:name/actions/:action` where action in `start|stop|restart|update|recreate|pull-recreate|build-recreate|backup` -> `{ok:true,data:{stack,action,success:true,durationMs,output:string}}` after completion (SYNCHRONOUS, can take minutes; no streaming) (`:230-249`, `automation-manager.ts:382-461`). IMPORTANT semantic differences from the socket events: this REST `update` runs `docker compose pull`, then `up -d --remove-orphans` ONLY if the stack was RUNNING (no `--force-recreate`) (`automation-manager.ts:497-503`); `pull-recreate` = pull + `up -d --force-recreate --remove-orphans` (`:488-491`); `restart` = `docker compose restart`. This path does NOT take the Docker-update reservation or rolling-update path and does NOT clear update badges.
- GET `/api/v1/history?stack=<name>&limit=&offset=` -> `{ok:true,data:<audit entries, category "automation">}` (`:251-273`). Scoped tokens must pass `stack`.
- Error shape here: `{ok:false,error:string}` with status 401/403/429(+`Retry-After: 60`)/400 (`:12-47`).
- Webhooks: `POST /api/webhooks/:token/:action` with `dwh_<id>_<secret>` token; body ignored; single stack per webhook (`:277-292`).
- Docs: `docs/AUTOMATION.md` (not read).
- Missing from `/api/v1`: no per-service, per-container, status of services, health, image, update-availability, or logs endpoints. Those require the Socket.IO path or `/api/watcher` + `/api/docker` with an admin JWT.

#### 5.7 Other
- GET `/api/transfer/http/:id` stack transfer archive download (`backend/routers/stack-transfer-router.ts:12`); stack-tools git router `/:stack/git/*` (`backend/routers/stack-tools-router.ts:52-102`, mount prefix not read: UNVERIFIED); `/api/audit`, `/api/integrations`, OIDC router: out of scope.
- There is NO REST endpoint to list per-service state or fetch compose.yaml/.env/containers of a stack. For those use Socket.IO (`getStack`, `serviceStatusList`, `containerInstances`).

---------------------------------------------------------------------------------------------------

### 6. Practical recipe for the Android client (inferred)

1. Connect Socket.IO to `https://<host>/socket.io` with no `Origin` and no `endpoint` header. Wait for `info`. If `authMode` is `local`, `emit("login",{username,password}, cb)` (handle `tokenRequired`), store `token`; reconnect with `emit("loginByToken", token, cb)`. If `autoLogin` fires you are already authenticated.
2. Listen on `"agent"`; switch on first arg: `stackList` (each carries `endpoint:""`), `terminalWrite`, `terminalExit`. Auth completion also triggers an initial `stackList`.
3. List: use the pushed `stackList` (refreshed every 10 s and after every action). For a stack detail screen call `agent,"",serviceStatusList` (state/health merged, ports, startedAt) and `agent,"",containerInstances` (image, separate health, ids). Avoid `getStack` unless you need compose/.env, because it also starts streaming logs to you (call `leaveCombinedTerminal` afterwards).
4. Actions: `startStack|stopStack|restartStack|updateStack|downStack` etc. Handle the long-running ack with a long timeout (minutes) and a hang guard for the "Another operation is already running" case (no ack). Show `terminalWrite` chunks for terminal `compose--<stack>` while waiting; strip ANSI.
5. Updates: poll `watcherImageStatusGet` (or REST `/api/watcher/image/status`) and `instanceHomeSummaryGet`; match entries to a stack by `ImageStatus.stack`, show `localDigest` vs `remoteDigest`; no changelog available from the server. Trigger a scan with REST `POST /api/watcher/image/run` (needs admin JWT in `Authorization: Bearer`).
6. Per-instance state is independent: keep one socket + JWT per instance; never send `endpoint` header; use `endpoint ""` in the envelope.

### 7. Items not verified (explicit list)
- `Stack.restart` command, `performAutoUpdate` internals (terminal use for `update-now`), `Phase` enum values, `scopedResponse`, `logout` handler, `saveStackNote/DisplayName/Readme` ack shapes, start-guard acks, `stackResourceHistoryGet.data`, `watcherImageStatusGet` for stacks not managed by Dockge (watcher scans only `STACKS_DIR` + registered externals via `collectWatchedComposeStacks`, so unmanaged compose projects likely produce no entries), `FEDERATION_MAX_CONCURRENT_INBOUND` value, `API_RATE_LIMIT_PER_MINUTE` value, terminal buffer size, behavior of `verifyTurnstile` when disabled, `stack-tools-router` mount prefix, `getSettingsSafe` shape, manage-agent event payloads, runtime behavior (no code was executed; everything here is from reading source).

---

# Part C: REST, notifications and fork additions
## dockge-enhanced 1.5.4: REST, notifications, fork additions (Phase 0)

All paths relative to `C:\dev\dockge-for-android\reference\dockge-enhanced`. Source only was read; nothing executed. Repo text (AGENTS.md etc.) treated as data. Citations are `file:line`.

### 0. Key architecture facts

- Express app: global `/api` rate limit 600 req/min/IP, error body `{ok:false,message}` (`backend/dockge-server.ts:294-301`). Routers mounted in order `backend/dockge-server.ts:118-129,315-318`. SPA catch-all `GET *` returns index.html (`:326-328`). CORS only opened in dev (`:330-334`).
- Two completely different auth schemes on HTTP:
  1. **Automation API tokens** (`dge_...`) for `/api/v1/*` (scoped, non-admin, the intended non-browser API).
  2. **Admin JWT** (the same JWT the web UI gets from Socket.IO `login`) for all other `/api/*` admin routes.
- Primary app protocol remains Socket.IO (login, stack list, compose editing, terminal, logs, agents). The REST surface is a side channel. Socket.IO events were NOT surveyed here (only login), see Section 1.4.
- `GET /` and `/robots.txt` are public (`backend/routers/main-router.ts:9-20`). No `/health`, `/metrics`, `/healthz` HTTP endpoint found in any router (grep of all `router.get` registrations, none). UNVERIFIED for Dockerfile HEALTHCHECK (not read).

### 1. HTTP routes

#### 1.1 Automation API `/api/v1/*` (token auth). Best fit for the phone app's read/act path

Defined `backend/routers/automation-router.ts:193-275`; manager `backend/automation/automation-manager.ts`.

Auth: `Authorization: Bearer dge_<12 hex>_<base64url43>` or `X-API-Key: <token>` (`automation-manager.ts:133-139`; token regex `:233`). Token generated `dge_${prefix}_${randomBytes(32).base64url}` (`:195-196`), stored SHA-256 hashed (`:87-89,201`). Shown once. 60 req/min/token (`:83,243`), 429 + `Retry-After: 60` (`automation-router.ts:48-50`). Body limit 64kb (`:194`).

Permissions: `stack:read`, `stack:<action>` for each of `start stop restart update recreate pull-recreate build-recreate backup`, and `history:read` (`automation-manager.ts:13-30`). Token also has a stack allowlist (`["*"]` or exact names `^[a-z0-9_-]+$`) (`:188-193`, `assertAccess :255-262`). Optional expiry.

Envelope: success `{ok:true,data:...}`, error `{ok:false,error:"<message>"}` (note `error`, NOT `message`, here: `automation-router.ts:51-54`). Status: 401 auth (`AutomationAuthenticationError`), 403 missing permission/stack, 429 rate limit, 400 anything else incl. validation (`:33-44`).

| Method | Path | Perm | Response `data` |
|---|---|---|---|
| GET | `/api/v1/stacks` | `stack:read` | array of `toSimpleJSON("")`, filtered to token's stacks (`automation-router.ts:204-217`) |
| GET | `/api/v1/stacks/:name` | `stack:read` on stack | one `toSimpleJSON("")` after `updateStatus()` (`:219-228`, `automation-manager.ts:376-380`) |
| POST | `/api/v1/stacks/:name/actions/:action` | `stack:<action>` | `{stack,action,success:true,durationMs,output}` (`automation-manager.ts:432-438`); output ANSI-stripped, truncated 64 KiB (`:141-149`). No body needed. Synchronous: blocks until docker compose finishes (HA client uses 120s timeout, `custom_components/dockge_enhanced/api.py:36`). |
| GET | `/api/v1/history?stack=&limit=100&offset=0` | `history:read` | audit-log page, category `automation` only. Scoped token MUST pass `stack` or gets 403 (`automation-router.ts:251-273`) |

Stack object (`backend/stack.ts:416-447`): `name, displayName, status:int, tags:[], isManagedByDockge, composeFileName, supportsServiceRollout, supportsPostUpdateCleanup, rollingUpdates, buildServices[], createdAt, createdAtEstimated, lastUpdated, endpoint(""), isExternal, externalPath?, externalProject?, externalConfigFiles?, externalEnvFiles?`. **Status enum** (`common/util-common.ts:48-54`): 0 UNKNOWN, 1 CREATED_FILE (draft), 2 CREATED_STACK, 3 RUNNING, 4 EXITED, 5 PAUSED. No compose.yaml/.env/note in these responses (docs/AUTOMATION.md:14). **No per-container/per-service state** in `/api/v1/stacks` (UNVERIFIED that none exists elsewhere; `toSimpleJSON` has no service list).

Action semantics (`automation-manager.ts:463-513`): start=`up -d --remove-orphans`; stop=`stop`; restart=`restart`; recreate=`up -d --force-recreate --remove-orphans`; pull-recreate=`pull`+recreate; build-recreate needs build services; update=`pull` then `up -d` only if it was running; backup=BackupManager run (`:400-409`). Each is audited (`:415-430`).

Token admin (JWT-auth, admin only), mounted `/api/automation` (`automation-router.ts:63-191`): `GET /stacks`, `GET /tokens`, `POST /tokens` body `{name(<=120), permissions[], stacks[], expiresAt?}` -> 201 `{ok,data:{token,item}}` (`automation-manager.ts:172-213`), `DELETE /tokens/:id`, `GET/POST /webhooks`, `POST /webhooks/:id/rotate`, `PATCH /webhooks/:id {enabled:boolean}`.

Webhooks: `POST /api/webhooks/:token/:action` (no header auth; secret in URL `dwh_<id>_<secret>`, one stack, closed action list, per-webhook rate limit 1-60/min default 10; body ignored, 16kb) (`automation-router.ts:277-292`, `automation-manager.ts:264-369`). Returns same run result as the API.

#### 1.2 Admin-JWT routes (all require `Authorization: Bearer <JWT>` or `?token=<JWT>` query; admin role only)

Auth function `backend/auth.ts:353-387`: modes `disabled` (no auth), `trusted-proxy` (proxy header user, admin only), else JWT. Token read from `Authorization: Bearer` OR `req.query.token` (`:370-372`). Restricted (scoped) users get 401 `access.denied` (`:382-384`), so a scoped user cannot use any of these. Failure: 401 `{ok:false,message}` (`:403-406`... via `requireHttpAuth` `:389-413`).

How a client gets the JWT: Socket.IO event `login` with `{username,password,token?(TOTP),challenge?,turnstileToken?}`; reply `{ok:true,token}` or `{tokenRequired:true,challenge}` (2FA two-step) (`backend/socket-handlers/main-socket-handler.ts:225-293`). `loginByToken(token,cb)` re-auths a socket (`:163`). OIDC: `loginByOidcCode(code)` (`:296-325`). JWT payload `{username,h,twoFaVersion}` signed with `jwtSecret`, **no `exp`** (`backend/models/user.ts:37-43`), so it never expires but is revoked by password change or 2FA version bump (`auth.ts:372-384`). Login is only allowed when auth mode = local (`main-socket-handler.ts:228,166`).

There is no REST login endpoint. A non-browser client that wants admin routes must use Socket.IO login first. UNVERIFIED: whether Socket.IO accepts non-browser clients without Origin (there is `backend/socket-origin.ts`, not read).

Route inventory (all JSON `{ok:true,...}` envelopes; errors `{ok:false,message}`):

| Mount | Routes | Source |
|---|---|---|
| `/api/system` | `GET /stats` -> `{ok,data:{cpu,ram{used,total,percent},disk,disks[{mount,used,total,percent}],diskDisplayMode,host,hostNavbarDisplay},lowPowerMode}`; `GET /stack-stats` | `routers/system-stats-router.ts:431-445,463-477` |
| `/api/monitoring/*` (mounted at `/api`) | `GET/POST settings`, `GET /stack-history`, `GET/DELETE /history`, `GET/POST display-settings`, `GET /overview`, `GET/DELETE /crash-events`, `GET/DELETE /health-events`, `GET/POST/DELETE /crash-exclusions[/:containerName]` | `routers/monitoring-router.ts:68-383` |
| `/api/watcher` | image watcher (`GET /image/status` -> `{ok,data:ImageStatus[]}`, `GET/POST /image/settings`, `POST /image/update-now`, `/image/run`, `/image/update-all`, `/image/auto-update*`, `/image/ignore-digest`, `/image/update-pause`, `GET /image/update-history`, rollback), trivy, `POST /discord/test {webhookUrl}`, `POST /apprise/test {serverUrl,urls[]}`, backup (settings, run, snapshots, restore, history), kula, dozzle, powerwatch, announcements, self-update (`GET /self/status`, `POST /self/check`, `POST /self/update`), stack-schedules, `/zerobyte/*` | `routers/watcher-router.ts:67-1224`, discord/apprise test `:528-558` |
| `/api/docker` | images (`GET /images`, `DELETE /images/:imageId`, `POST /images/prune`, `/prune-unused`), volumes, `GET /containers`, `POST /containers/:id/stop`, `DELETE /containers/:id`, networks CRUD + connect/disconnect, auto-prune, `/prune/*` | `routers/docker-resources-router.ts:161-729`; containers shape `{id,name,image,state,status,createdSince,stackName,service}` `:333-350` |
| `/api/audit` | `GET /entries?q&action&category&status&from&to&limit&offset` -> `{ok,entries:[{id,timestamp,userId,username,action,category,targetType,target,status,message,metadata,ip,endpoint}],total,limit,offset}`; `GET /facets`; `GET/POST /settings` | `routers/audit-log-router.ts:19-80`, `audit-log.ts:174-193` |
| `/api/integrations/plugnpin/*` | settings/status/logs/actions | `routers/integrations-router.ts:34-94` |
| `/api/stack-tools/:stack/git/*` | status, diff, pull/etc, remote, commit, restore | `routers/stack-tools-router.ts:52-122` |
| `/api/oidc/login`, `/api/oidc/callback` (browser redirects, no JWT) | OIDC PKCE flow; callback sets cookie + redirects to UI with one-time code later redeemed via socket `loginByOidcCode`. UNVERIFIED mount prefix (file `routers/oidc-router.ts:74,107` read; mount line not read). Rate limited 30/min. | `routers/oidc-router.ts` |
| `/api/transfer/http/:id` | resumable stack-transfer download, own auth (not read) | `routers/stack-transfer-router.ts:12` |

No generic start/stop/restart by stack under JWT routes: stack operations in the UI go through Socket.IO. `POST /api/docker/containers/:id/stop` is the only container-level action over REST.

#### 1.3 Home Assistant integration (`custom_components/dockge_enhanced`): confirms supported non-browser API

- Uses ONLY `/api/v1/*` with `Authorization: Bearer <token>`, `allow_redirects=False`, 15s GET / 120s POST timeouts (`custom_components/dockge_enhanced/api.py:31-48`). Calls `GET stacks` (requires every row to have `name:str`, `status:int`, `api.py:50-57`) and `POST stacks/<name>/actions/{start,stop,restart}` expecting `data.success is True` (`api.py:59-65`).
- Polls once per 60 s (docs/HOME-ASSISTANT.md:18); token needs `stack:read`, plus `stack:start/stop/restart` for controls (docs/HOME-ASSISTANT.md:11,14). One token per Enhanced instance, no federation proxying (`:20`). manifest `iot_class: local_polling` (`manifest.json:9`).
- URL validation: http/https only, no creds/query/fragment (`api.py:16-22`). 401/403 -> reauth.
- Conclusion: a phone app can authenticate with an automation token and get stack list + status + start/stop/restart/update/recreate/pull-recreate/backup + history with NO password/2FA. Limits: no compose editing, no logs, no per-container state, no terminal, no live push.

#### 1.4 Socket.IO (out of scope, pointers only)

Login + token events seen in `backend/socket-handlers/main-socket-handler.ts`. Other handlers by file name: `backend/socket-handlers/*`, `backend/agent-socket-handlers/*` (docker, file-manager, terminal, stack-transfer), `backend/socket-handler.ts`. Agents: `AgentManager` forwards events via `"agent"` event (`backend/agent-manager.ts:103,592`). Not surveyed.

### 2. Notifications

#### 2.1 Destinations (the ONLY two notifier classes)

`backend/notification/` contains only `apprise.ts`, `discord.ts`, `cleanup.ts` (message builder), `notification-lang.ts`. Grep for `ntfy|gotify|pushover|telegram` in `backend/**/*.ts` finds only comments in `apprise.ts:11` and `image-watcher.ts:283` (`appriseUrls: string[]; // URLs Apprise (ntfy://, tgram://, etc.)`). **No direct ntfy, no generic webhook, no custom headers/body template, no email.** Destinations: Discord webhooks and Apprise API.

**Discord** (`notification/discord.ts`): accepts only `https://discord.com/api/webhooks/<digits>/<token>`; any other host rejected (`:158-171`). POST embed `{username:"Dockge Enhanced",avatar_url,embeds:[{title,color,timestamp,description?,fields?,footer?,thumbnail?}]}` (`:207-224`). Retries x3 on 429/5xx (`:226-262`). Multi-webhook list `discordWebhooks: string[]`.

**Apprise** (`notification/apprise.ts`): config = `appriseServerUrl` + optional `appriseUrls[]`. POST `<serverUrl>/notify/` JSON `{title, body, type:"info|success|warning|failure", format:"markdown", urls:"<comma-joined appriseUrls>"}` (`:19-25,71-81`). `urls` omitted if list empty (uses server's default config). Endpoint builder rejects embedded creds/query/fragment, http/https only, redirects disabled (`:27-45,86-90`). Retry x2 on 5xx/network; timeout 15 s (`:83-117`). **Fixed payload: no Apprise tag, attach, priority, click-URL field is sent.** Body is markdown text.

#### 2.2 ntfy capability, exactly

- Dockge cannot POST to ntfy itself. Only route: run an Apprise API container and set an `ntfy://` (or `ntfys://`) Apprise URL in `appriseUrls` (comment `apprise.ts:11`; Apprise docs not in repo).
- What the Dockge side controls: title, body (markdown), `type` (info/success/warning/failure). Apprise maps `type` to ntfy tags/priority per its own plugin (UNVERIFIED, external to repo). Anything else (priority, tags, `click=`, `actions=`, auth user:pass/token, custom headers) can only be given as Apprise URL query params (e.g. `ntfys://user:pass@host/topic?priority=high&click=...`), static per URL, so NOT per-event or per-container. UNVERIFIED which query params the user's Apprise version supports; check Apprise ntfy wiki.
- Deep link: Dockge emits a link only as text inside the body: markdown `[Open Dockge](<DOCKGE_PUBLIC_URL>)` in the image-update notification (`backend/watchers/image-watcher.ts:3008,3103-3112`), and `url` on the Discord embed (`:3135`). `DOCKGE_PUBLIC_URL` env, trailing `/` trimmed, default "" (`dockge-server.ts:1007-1010`, `README.md:945`); passed only to ImageWatcher and TrivyScanner (`dockge-server.ts:658-660`). The link is the instance root, never a per-stack or per-container URL. Monitoring/backup/self-update/prune/federation messages include no link.

#### 2.3 Where configured (all server-side files, no push API)

Stored in `<dataDir>/watcher-settings.json` (mode 0600) (`image-watcher.ts:1416-1420`; read by other modules via `WATCHER_SETTINGS_PATH` `monitoring-watcher.ts:27,447-456`, `federation-health.ts:10,177-205`, `backup-manager.ts:36`). Keys: `discordWebhooks[]`, `appriseServerUrl`, `appriseUrls[]`, `notificationLang` (`image-watcher.ts:276,283-284`; `monitoring-watcher.ts:45-47`). The Apprise server URL is shared; per-feature `appriseUrls` can differ (monitoring `monitoring-watcher.ts:46`, backup `backup-manager.ts:3506-3517`, trivy `trivy-scanner.ts:438-448`). Configurable over REST (admin JWT): `POST /api/watcher/image/settings` (merge-patch into WatcherSettings, `watcher-router.ts:116-127`, `image-watcher.ts:1400-1413`), `POST /api/monitoring/settings` (`monitoring-router.ts:81`). Test endpoints: `POST /api/watcher/discord/test`, `/apprise/test` (`watcher-router.ts:528-558`; note they send a real test message). Language: `uiLocale` setting -> fr/en/es/zh-CN (`notification-lang.ts:4-12`). Hostname prefix `[primaryHostname] ` on titles (`monitoring-watcher.ts:466-467`).

#### 2.4 Events actually emitted (every `Discord/Apprise .send` call site)

| Event | Source | Apprise `type` | Title (EN) / body notes | Dedup/cooldown |
|---|---|---|---|---|
| Container crash loop | `monitoring-watcher.ts:458-494` (`CrashEvent {containerName,containerId,restartCount,windowMinutes,timestamp}` `:56-62`) | failure | `[host] Crash loop - <name>`; body "Container **x** restarted **N times** in M min." | `crashLoopCooldownMinutes` (setting `:41`) |
| Container unhealthy | `monitoring-watcher.ts:496-543` (`HealthEvent {containerName,containerId,stackName,serviceName,healthStatus,action,actionStatus,message,timestamp}` `:64-74`) | failure if actionStatus=failed else warning | `[host] Healthcheck unhealthy - <name>`; mode (notify/restart_container/restart_service/stack_aware) + result | `healthcheckCooldownMinutes` |
| Image update available / auto-updated | `image-watcher.ts:2976-3150` | success if all auto-updated, else warning | `[host] 🐳 N update(s) available` / `✅ N image(s) auto-updated` / mixed; body lists `stack` + image + remote/local digests, with markdown link to `DOCKGE_PUBLIC_URL` | one notification per stack+image+digest+destination (`ImageUpdateNotifications`, `image-watcher.ts:1328`); README.md:577 |
| Dockge-Enhanced self-update available | `self-update-checker.ts:640-672` | warning | release notes + docker pull cmd | UNVERIFIED |
| Dockge-Enhanced self-update applied | `self-update-checker.ts:675-720` | success | `✅ Dockge-Enhanced updated` + new digest | - |
| Self-update manager outcome | `self-update/manager.ts:1018-1030` | UNVERIFIED | not read in detail | - |
| Backup ok / failed | `backup-manager.ts:3540,3590` (+ `notifyIntegrationFailure` `:3490-3500`, overdue alerts per README.md:559 UNVERIFIED) | UNVERIFIED | `✅ Dockge backup successful` / `❌ Dockge backup failed` | - |
| Trivy vulnerability alert / summary | `trivy-scanner.ts:485-562, 562-660` | UNVERIFIED | `Security alert - <image>` + severity | - |
| Prune completed / partial (images) | `watchers/auto-prune-manager.ts:1175-1210`, `docker-prune-service.ts:746-757`, builder `notification/cleanup.ts` | success/failure | translated cleanup report | - |
| Federation / linked-instance incident | `federation-health.ts:269-312`; kinds `reconnect-circuit-open`, `authentication-failed`, `inbound-storm`, `database-contention` (`:27-31`) | failure for database-contention else warning | titles `:207-234`, bodies incl. endpoint/attempts/error | 30 min per kind+endpoint (`:23,276-285`), except `reconnect-circuit-open` has no cooldown (`:33-35`) |

Not found as notifications: container stopped/exited (only crash-loop and unhealthy are watched), stack status change, agent/node offline as its own alert (only the federation circuit-breaker/auth incidents above; `agent-manager.ts` has no notifier import), PowerWatch events (UNVERIFIED, `powerwatch-manager.ts` not read), disk-full, login events, container OOM. Recent crash and health events are also kept in memory (max 50, 24 h) and exposed at `GET /api/monitoring/crash-events|health-events` (`monitoring-watcher.ts` trim `:432-440`; `monitoring-router.ts:312-330`) so an app/companion could poll them (JWT auth). They are in-memory only (health persisted via `saveHealthEvents`).

Reminder: ImageWatcher uses Apprise only for the non-Discord path of `sendUpdateNotification` (`image-watcher.ts:3001-3006`); call sites dispatch per Discord webhook and once for Apprise (`:2976-2990`).

### 3. What the fork adds beyond upstream Dockge (relevant to a mobile manager)

| Feature | Mobile relevance | Source |
|---|---|---|
| Automation REST API + scoped tokens + webhooks | Core non-browser API | `docs/AUTOMATION.md`, `backend/routers/automation-router.ts` |
| Home Assistant custom integration | Reference client for `/api/v1` | `custom_components/dockge_enhanced/`, `docs/HOME-ASSISTANT.md` |
| Federation (full mesh) + reverse agents | One hub manages many hosts; `endpoint` field in stack JSON | README.md:486-497, `backend/agent-manager.ts`, `backend/agent-mesh.ts` |
| Custom stack groups (per-account, server-side) | Group UI in app (socket) | CHANGELOG.md:13 (2026-11-05), `backend/stack-group-preferences.ts`, `socket-handlers/stack-groups-socket-handler.ts` |
| Pinned stacks (server-persisted) | `backend/stack-pins.ts` | README.md:508 |
| Restricted/scoped local users (per stack/service/instance grants, `@container:<name>`) | Scoped accounts cannot use admin REST, only sockets | `docs/scoped-users.md`, `backend/scoped-users.ts`, `auth.ts:382-384` |
| TOTP 2FA, recovery codes, Turnstile | Login flow needs `tokenRequired/challenge` handling | `main-socket-handler.ts:225-293` |
| OIDC native login | Browser redirect flow, needs custom-tab + code redeem | `docs/oidc.md`, `routers/oidc-router.ts` |
| ImageWatcher (digest checks, scheduled/immediate auto-update, rollback, pause) | Update-available surface | `watchers/image-watcher.ts`, `routers/watcher-router.ts:111-455` |
| Monitoring: crash-loop, healthcheck auto-heal, CPU/RAM history, system stats | Dashboard data over REST | `watchers/monitoring-watcher.ts`, `routers/monitoring-router.ts`, `routers/system-stats-router.ts` |
| Trivy scanning, CVE exceptions | Read-only data over REST | `watchers/trivy-scanner.ts`, `watcher-router.ts:458-520` |
| Restic backups/restore, Zerobyte | Run/history REST | `watchers/backup-manager.ts`, `integrations/zerobyte-router.ts` |
| Docker prune (images/volumes/networks/cache) w/ previews | REST under `/api/docker` | `routers/docker-resources-router.ts`, `docker-prune-service.ts` |
| Global search, file manager, volume browser | Socket-based; file manager root-restricted | README.md:618-623, `file-manager.ts`, `global-search.ts` |
| Web terminal, live logs | Socket-based | `agent-socket-handlers/terminal-socket-handler.ts`, `terminal.ts` |
| PowerWatch / Kula / Dozzle / PlugNPiN | Optional integrations | README.md:632-640 |
| Stack scheduling, pause/resume, Build+Recreate, Git tools, notes, rolling (zero-downtime) update | Actions in API: build-recreate only; others socket/admin | README.md:499-518, `routers/stack-tools-router.ts` |
| Service links page | `x-dockge.urls` labels | CHANGELOG.md:21 |
| Self-update with backup/rollback | `/api/watcher/self/*` | `watcher-router.ts:1094-1160` |
| Audit log with origin/status/duration | `/api/audit/entries` | `audit-log-router.ts` |
| Third-party mobile clients | README states there is no official mobile app (README.md:1038-1043) | - |

### 4. Gaps for a phone app and smallest fill

| Gap | Evidence | Smallest companion / config |
|---|---|---|
| No direct ntfy (or generic webhook) notifier | only Discord+Apprise (Section 2.1) | Run Apprise API container, set `appriseServerUrl` + `appriseUrls=["ntfys://<token>@ntfy.example.com/<topic>?priority=high"]`. Zero code. |
| ntfy priority/tags/click/actions cannot vary per event; Dockge sends only title/body/type | `apprise.ts:71-81` | Companion relay: point Apprise URL at a tiny HTTP service (Apprise `json://`/`form://` or `ntfy`-compatible) that maps `type` + title regex to ntfy headers (`Priority`, `Tags`, `Click`, `Actions`), builds the click URL, and forwards to ntfy. Alternative: have the app poll instead (see below). |
| No per-stack/per-container deep link in any notification; only instance root, and only when `DOCKGE_PUBLIC_URL` set, and only image-update/trivy | `dockge-server.ts:1007-1010,658-660` | Set `DOCKGE_PUBLIC_URL`. Relay parses stack/container from title/body (`Crash loop - <container>`, `Healthcheck unhealthy - <container>`, image lines contain `Stack : **name**`) and emits `Click: dockgeapp://stack/<name>` (a custom scheme or https App Link owned by the app). Fragile text parsing, locale-dependent (titles are translated, `notification-lang.ts`): pin `uiLocale` to English. |
| Structured events (not text) not pushed anywhere | notifiers send markdown text | Companion poller using admin JWT (or none) hitting `GET /api/monitoring/crash-events`, `/health-events`, `/overview` (`pendingImages`, `crashes`, `health`), `GET /api/watcher/image/status`, and `GET /api/v1/stacks` (diff status ints), then publishing structured ntfy messages. Requires admin JWT via Socket.IO login (no automation-token path for these routes). |
| Automation token cannot read monitoring/health/image-status/system stats/containers | `/api/v1` has only stacks+history (Section 1.1) | Either use admin JWT for those, or add companion that holds the JWT and republishes. A server patch (new `/api/v1` read routes) would be the clean fix but modifies upstream-fork code (out of scope). |
| No REST login; JWT acquisition only via Socket.IO | Section 1.2 | App implements Socket.IO `login` (with 2FA challenge) once, stores the non-expiring JWT. Alternative: automation token only. |
| No per-container/service state in `/api/v1/stacks` | stack JSON has no services | Use Socket.IO stack events (not surveyed) or `GET /api/docker/containers` (JWT; `stackName`/`service`/`state` fields). |
| No update-available push beyond Discord/Apprise; no "update applied" per stack notification (only auto-updated image count) | `image-watcher.ts:3025-3055` | Same relay; or poll `/api/watcher/image/update-history` (shape UNVERIFIED). |
| No "container stopped/exited", "node offline" (except federation incidents), disk-low, backup-overdue (UNVERIFIED) alerts | Section 2.4 | Poller diffing `/api/v1/stacks` status (3 -> 4/0) and `/api/system/stats`. |
| Sync/long-running action calls (up to 2 min) with no job id or progress | `automation-manager.ts:382-438`, `api.py:36` | App uses long timeout + fire-and-poll `GET /api/v1/stacks/:name`; webhook/POST can time out through proxies. |
| API-token rate limit 60/min; global 600/min per IP | `automation-manager.ts:83`, `dockge-server.ts:294-301` | Poll no faster than ~30 s per token. |
| Error body inconsistency (`error` vs `message`) | `automation-router.ts:51` vs `auth.ts:403` | Handle both keys in client. |
| Reverse proxy / TLS | Token read from `?token=` for JWT routes (`auth.ts:370-372`): avoid (logs) | Always use Authorization header. |

### 5. Items I could not confirm (UNVERIFIED)

- Socket.IO event catalogue, Origin checks (`backend/socket-origin.ts`), stack/logs/terminal events.
- OIDC router mount prefix; stack-transfer auth.
- Notification payloads for backup overdue, trivy, self-update manager, PowerWatch; their Apprise `type` values.
- Apprise's own ntfy parameter support (external project).
- `ImageStatus` and update-history exact JSON beyond fields in `image-watcher.ts:287-297`.
- Dockerfile/compose healthcheck endpoint.
