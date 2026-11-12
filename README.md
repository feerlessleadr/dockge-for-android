# Dockge for Android

A standalone Android app to manage one or more [dockge-enhanced](https://github.com/Aerya/dockge-enhanced) instances from a phone. Personal project, sideloaded via Obtainium (not on the Play Store).

**Status:** in development (milestones M0 to M5 done; alerts in test). No release yet.

## Install with Obtainium (once releases exist)
1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add app, source URL `https://github.com/feerlessleadr/dockge-for-android`.
3. Obtainium reads GitHub Releases; each release is a signed APK tagged `vX.Y.Z`.

Updates only work if every release is signed with the same key.

## Alerts (ntfy, no Google push)
The app can show alerts from your own [ntfy](https://ntfy.sh) server: crash loops, unhealthy containers and image updates. It keeps one connection to a topic open in a foreground service (Android shows a small permanent notification for it). Tapping an alert opens the instance, and the stack when the app can tell which one (best effort: the message does not name them reliably).

dockge-enhanced cannot publish to ntfy itself, so the path is Dockge, then an Apprise API server, then ntfy:
1. Run an [Apprise API](https://github.com/caronc/apprise-api) container.
2. In dockge-enhanced, set the Apprise server address to that container and add an Apprise URL for your topic, for example `ntfys://ntfy.example.com/your-topic` (add `token@` after the scheme when the server needs login).
3. Set `DOCKGE_PUBLIC_URL` on the instance. Image-update messages then contain the instance address, which is how the app picks the instance to open.
4. In the app: Alerts (top bar of the instance list), enter the ntfy server, topic and optional access token, turn on Receive alerts, allow notifications, Save. "Send test message" posts to the topic so you can check the app side without Dockge.
5. If alerts arrive late or stop, set the app's battery use to Unrestricted (the Alerts screen has a button).

On the lock screen alerts only say what kind of alert it is. Names appear after unlocking.
