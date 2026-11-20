# STATUS (2026-11-20)

**Phase:** M6 (alerts) is built on branch m6-notifications (PR 10) and waiting for the owner's device test. M6 was redesigned on 2026-11-20: the app now watches the Dockge instances directly, with no ntfy, Gotify or Apprise in the app (decision in DECISIONS.md). M0 to M5 are merged and owner-verified. 137 unit tests pass; debug and release (R8) builds succeed. Nothing in M6 has run on a device yet.

## Done
- Project registered in WORKSPACE.md; repo, branch protection (PR + `build-and-test`, admins enforced), release keystore and 4 repo secrets in place.
- M0 scaffold, M1 login, M2 saved instances, M3 read-only stacks, M4 stack and container actions, M5 update status and one-tap update: merged and owner-verified.
- M6 (this branch):
  - Alerts screen (top bar of the instance list): master switch, Updates, Containers (crash loops, unhealthy), Backups (failures only), Security (critical or high findings), notification permission, per-instance feed status, battery button.
  - `AlertWatcher`: image updates from the update status the app already keeps; everything else from `GET /api/monitoring/overview` on each connected instance every 60 s, with the saved session token as a Bearer header (admin users). The first look per instance is silent; the same event never alerts twice, also across restarts.
  - Foreground service (`specialUse`) keeps the process alive while alerts are on; restarted after reboot, app update and when the app opens. Lock screen shows only a generic line per kind. Tap opens `dockge://instance/<id>[/stack/<name>]`: updates and security findings name the stack, health events carry it, crash alerts guess it from the container name.
  - Not watchable: cleanup, federation and Dockge self-update reports (Apprise or Discord only); crash and health events of servers linked to an instance (unverified).

## Next action
- Owner device test of M6 (below). Then merge PR 10 (owner go-ahead) and start M7 (release 0.1.0).

## Owner test (M6), on the isolated test instance first
1. Install the new APK. Open Alerts, switch Alerts on, allow notifications. The instance should say "Monitoring feed read <time>". If it says "refused the saved login" or "no monitoring feed", send me that line (the feed or your user may not be allowed; that is what this test verifies).
2. Update alert: the whoami retag trick (retag an old image as `latest`, Down and Start in the app, run the image check in Dockge). Within about 5 minutes (or after force-close and reopen) an "Update available: whoami" alert should arrive. Tap it: it should open the whoami stack. Lock the phone and trigger another: the lock screen should show only a generic line.
3. Crash alert: in the test VM run a throw-away container that crashes in a loop, for example `docker run -d --restart always --name crashy busybox false`, then remove it when done (`docker rm -f crashy`). Crash-loop detection must be switched on in the test Dockge (Monitoring settings). Within about a minute an alert should arrive. Tell me what it says.
4. Swipe the app away from recents and repeat step 2 or 3: it should still arrive. Reboot and check the permanent notification comes back.
5. Optional: turn a kind off and trigger it, then turn it back on: the old event must not alert.

## Open questions
- TOTP seed storage: not implemented; ask before ever doing it.

## Not yet verified
- All of M6 on a device and a real server: the monitoring feed with the app's stored token (and whether trusted-proxy or auth-disabled servers need no token), foreground service survival (app kill, reboot), permission flow, tap routing, whether crash and health events of linked servers can be read.
- Linked (federated) servers' update status through the production instance; rollout-pending ack and connection loss during Update; the M5 update-refresh fix on a device.
- Token re-login after a server restart, revoked token, login rate limit (live); release APK at runtime; release workflow (first real test is M7).

## Owner manual steps
- M6 test above. Later: pin the cert SHA-256 in release.yml and finish the README Obtainium section (M7).
