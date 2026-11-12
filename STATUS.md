# STATUS (2026-11-12)

**Phase:** M6 (notifications) is built on branch m6-notifications and waiting for the owner's device test. M0 to M5 are merged (M5 as PR 9, owner-tested on the test instance, with a fix for refreshing update status on resume). 135 unit tests pass; debug and release (R8) builds succeed. Nothing in M6 has run on a device yet.

## Done
- Project registered in WORKSPACE.md; repo, branch protection (PR + `build-and-test`, admins enforced), release keystore and 4 repo secrets in place.
- M0 scaffold, M1 login, M2 saved instances, M3 read-only stacks, M4 stack and container actions, M5 update status and one-tap update: merged and owner-verified.
- M6 (this branch):
  - Alerts screen (instance list top bar): ntfy server (https), topic, optional access token (Keystore vault), Receive alerts switch, notification permission, connection state, "Send test message", battery settings button.
  - Foreground service (`specialUse`) keeps one streaming connection to `/<topic>/json`; resumes from the last message id, first run starts "now"; reconnect 1 s doubling to 60 s; repeated ids dropped. Restarted after reboot or app update and whenever the app opens.
  - Alert notification: title and text after unlock, generic kind text on the lock screen. Tap opens `dockge://instance/<id>[/stack/<name>]`: instance by saved address or host in the text, else the only instance; stack only when exactly one known stack name appears as a whole word (best effort).
  - README has the Apprise to ntfy setup.

## Next action
- Owner device test of M6 (below). Then merge the PR (owner go-ahead) and start M7 (release 0.1.0).

## Owner test (M6)
Part 1, no Dockge needed (do this first):
1. You need an ntfy server address and a topic name (letters, digits, - and _). If the topic needs login, an access token. Tell me if you need help picking these.
2. Install the M6 debug build. Open Alerts, fill in server and topic, turn on Receive alerts, allow notifications, Save. Connection should say "listening" and a small permanent notification appears.
3. Tap "Send test message". An alert "Dockge test" should appear within seconds. Lock the phone and send another: the lock screen should show only a generic line.
4. Tap the alert: the app opens. Swipe the app away from recents, send another test message from the ntfy web UI or another device: it should still arrive. Reboot the phone and send one more.
Part 2, with Dockge (needs your setup):
5. Run Apprise API, give dockge-enhanced its address and an `ntfys://...` URL for the same topic, set `DOCKGE_PUBLIC_URL` (see README). Use the Dockge "test notification" button or trigger an image update on the test stack.
6. Tap the alert: the app should open the right instance, and the stack when it is the only one named. Send me a screenshot of the notification text (the exact title text matters for classification).

## Open questions
- ntfy server address, topic and whether it needs a token (owner).
- TOTP seed storage: not implemented; ask before ever doing it.

## Not yet verified
- All of M6 on a device (foreground service survival, boot start, permission flow, tap routing, lock-screen text, real Apprise message wording).
- Linked (federated) servers' update status through the production instance; rollout-pending ack and connection loss during Update; update-refresh fix from M5.
- Token re-login after a server restart, revoked token, login rate limit (live); release APK at runtime; release workflow (first real test is M7).

## Owner manual steps
- M6 test above. Later: pin the cert SHA-256 in release.yml and finish the README Obtainium section (M7).
