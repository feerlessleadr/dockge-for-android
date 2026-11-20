# Dockge for Android

A standalone Android app to manage one or more [dockge-enhanced](https://github.com/Aerya/dockge-enhanced) instances from a phone. Personal project, sideloaded via Obtainium (not on the Play Store).

**Status:** in development (milestones M0 to M5 done; alerts in test). No release yet.

## Install with Obtainium (once releases exist)
1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add app, source URL `https://github.com/feerlessleadr/dockge-for-android`.
3. Obtainium reads GitHub Releases; each release is a signed APK tagged `vX.Y.Z`.

Updates only work if every release is signed with the same key.

## Alerts (ntfy and/or Gotify, no Google push)
The app can show alerts from your own [ntfy](https://ntfy.sh) server, your own [Gotify](https://gotify.net) server, or both: crash loops, unhealthy containers, image updates, Dockge self-updates, backup, security, cleanup and federation reports. It keeps one connection per enabled server open in a foreground service (Android shows a small permanent notification for it). Tapping an alert opens the instance, and the stack when the app can tell which one (best effort: for image updates the alert names the stack; other alerts do not name it reliably).

dockge-enhanced cannot publish to ntfy or Gotify itself, so the path is Dockge, then an Apprise API server, then your notification server. Apprise only sends; the app listens to ntfy and/or Gotify.
1. Run an [Apprise API](https://github.com/caronc/apprise-api) container and give dockge-enhanced its address.
2. Add one Apprise URL per destination in dockge-enhanced (Dockge sends to all of them). ntfy: `ntfys://ntfy.example.com/your-topic` (add `token@` after the scheme when the server needs login). Gotify: `gotifys://gotify.example.com/<application-token>` (create the application token in Gotify under Apps).
3. Set `DOCKGE_PUBLIC_URL` on the instance. Alert messages then contain the instance address, which is how the app picks the instance to open.
4. In the app: Alerts (top bar of the instance list). For ntfy enter the server, topic and optional access token. For Gotify enter the server and a client token (create one in Gotify under Clients; it is a different token from the application token). Turn the source on, allow notifications, Save. ntfy has a "Send test message" button; for Gotify use the test notification button in Dockge's Apprise settings.
5. If both services receive the same alert the app shows it once. If alerts arrive late or stop, set the app's battery use to Unrestricted (the Alerts screen has a button).

On the lock screen alerts only say what kind of alert it is. Names appear after unlocking. Your reverse proxy must pass WebSocket connections for Gotify.
