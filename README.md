# Dockge for Android

A standalone Android app to manage one or more [dockge-enhanced](https://github.com/Aerya/dockge-enhanced) instances from a phone. Personal project, sideloaded via Obtainium (not on the Play Store).

**Status:** in development (milestones M0 to M5 done; alerts in test). No release yet.

## Install with Obtainium (once releases exist)
1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add app, source URL `https://github.com/feerlessleadr/dockge-for-android`.
3. Obtainium reads GitHub Releases; each release is a signed APK tagged `vX.Y.Z`.

Updates only work if every release is signed with the same key.

## Alerts
The app watches your Dockge servers itself and notifies you. No notification server (ntfy, Gotify, Apprise) and no Google push are involved: while alerts are on, the app keeps its connections to your instances open in a foreground service (Android shows a small permanent notification for it).

What it tells you about:
- a new image update (needs the image watcher switched on in that Dockge)
- a crash loop or an unhealthy container, a failed backup, a critical or high security finding (these come from the instance's monitoring feed and need your Dockge user to be an administrator)

Tapping an alert opens the instance, and the stack when the alert is about one stack. Alerts about cleanup, linked-server problems and Dockge's own updates cannot be watched this way, and only the instance you added is read, not the servers linked to it. On the lock screen alerts only say what kind of alert it is; names appear after unlocking.

Turn it on in the app: Alerts (top bar of the instance list), switch Alerts on, allow notifications. If alerts arrive late or stop, set the app's battery use to Unrestricted (the Alerts screen has a button).
