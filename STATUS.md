# STATUS (2026-11-20)

**Phase:** M6 (notifications) is built on branch m6-notifications (PR 10) and waiting for the owner's device test. ntfy was sent as a first APK; the new build adds Gotify and all alert kinds. M0 to M5 are merged and owner-verified. Debug and release (R8) builds succeed. Nothing in M6 has run on a device yet.

## Done
- Project registered in WORKSPACE.md; repo, branch protection (PR + `build-and-test`, admins enforced), release keystore and 4 repo secrets in place.
- M0 scaffold, M1 login, M2 saved instances, M3 read-only stacks, M4 stack and container actions, M5 update status and one-tap update: merged and owner-verified.
- M6 (this branch):
  - Two alert sources, each on or off: ntfy (streaming `/<topic>/json`) and Gotify (WebSocket `/stream` with a client token, catch-up from the last message id). Tokens in the Keystore vault. One foreground service (`specialUse`) while any source is on; restarted after reboot, app update and when the app opens. Reconnect 1 s doubling to 60 s.
  - Alerts for every notification the fork sends: crash loop, unhealthy, image update, Dockge self-update, backup, security, cleanup, federation. The same event arriving through both sources is shown once (same title and text within 2 minutes). Lock screen shows only a generic line per kind.
  - Tap opens `dockge://instance/<id>[/stack/<name>]`. Update alerts name the stack in the body, so one updated stack opens that stack (with its server); several open the instance. Other alerts open the instance (crash loop and unhealthy try a whole-word stack name match).
  - Alerts screen (top bar of the instance list): a section per source, notification permission, connection state, ntfy test message, battery button. README has the Apprise setup for both.

## Next action
- Owner device test of M6 (below). Then merge PR 10 (owner go-ahead) and start M7 (release 0.1.0).

## Owner test (M6)
Part 1, ntfy without Dockge: Alerts, fill in ntfy server and topic, turn on, allow notifications, Save. Connection should say "listening". Send test message: an alert "Dockge test" appears. Lock the phone and send another: the lock screen shows only a generic line. Tap the alert. Swipe the app away and send another; reboot and send another.
Part 2, Gotify:
1. In Gotify create a Client (Clients, name it "Dockge Android") and copy its token.
2. In the app turn on Gotify, enter the server (https) and the client token, Save. Connection should say "listening".
3. Send a message to Gotify from another app or `curl` with an application token. It should arrive as an alert.
4. Turn the app off for a minute (airplane mode), send two messages, turn it back on: both should arrive (catch-up).
Part 3, with Dockge: Apprise API with one URL per service (README), `DOCKGE_PUBLIC_URL` set. Use Dockge's Apprise test button, then trigger an image update on the test stack (the whoami retag trick works). With both services on, you should get ONE alert per event. Tap the update alert: it should open the `whoami` stack. Send me a screenshot of the notification text.

## Open questions
- TOTP seed storage: not implemented; ask before ever doing it.

## Not yet verified
- All of M6 on a device: foreground service survival (app kill, reboot), permission flow, Gotify WebSocket through your reverse proxy, catch-up, dedupe across sources, tap routing, real Apprise message formatting for ntfy and Gotify.
- Linked (federated) servers' update status through the production instance; rollout-pending ack and connection loss during Update; the M5 update-refresh fix on a device.
- Token re-login after a server restart, revoked token, login rate limit (live); release APK at runtime; release workflow (first real test is M7).

## Owner manual steps
- M6 test above. Later: pin the cert SHA-256 in release.yml and finish the README Obtainium section (M7).
