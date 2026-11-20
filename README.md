# Dockge for Android

A standalone Android app to manage one or more [dockge-enhanced](https://github.com/Aerya/dockge-enhanced) instances from a phone. Personal project, sideloaded (not on the Play Store, no Google push).

**Status:** 0.1.0 is published (instances, stacks and containers, actions, updates, alerts). See the Releases page.

## What it does
- Several instances (and the servers linked to them), live status, stacks and containers with state, health, image and uptime.
- Start, restart, stop, down and update for a stack, start, restart and stop for a container, each with a progress screen. Everything that stops or recreates something asks first.
- Update badges and a one-tap, confirmed update of a stack (the server has no changelog, so the app shows the image digests and a link to the image page).
- Alerts the app raises itself (see below).
- Login with user name, password and two-factor code. Only the session token is stored, encrypted with the Android Keystore. No password and no two-factor seed is stored.
- Not in this version: logs, compose or environment editor, terminal.

Requirements: Android 8.0 or newer, an https address for each instance (a reverse proxy or VPN is fine), and the [dockge-enhanced](https://github.com/Aerya/dockge-enhanced) fork (the plain Dockge does not have the update and monitoring features).

## Install with Obtainium
1. Install [Obtainium](https://github.com/ImranR98/Obtainium) and allow it to install apps when Android asks.
2. In Obtainium choose Add App and enter `https://github.com/feerlessleadr/dockge-for-android` as the source. Leave the settings at their defaults and add it.
3. Obtainium downloads the signed APK from the newest GitHub Release and installs it. Later releases arrive as updates.

Installing by hand also works: download `dockge-for-android-vX.Y.Z.apk` from the release, and compare its SHA-256 with the value in the release notes (`sha256sum` on Linux and macOS, `Get-FileHash` in PowerShell).

Every release is signed with the same key, and Android only installs an update that matches the signature of the installed app. The signing certificate's SHA-256 fingerprint is `E4:12:AC:F4:14:61:EE:D0:B8:98:03:19:98:0F:FC:E6:0B:F6:B4:1B:5A:97:3D:7B:3C:7E:59:5A:0D:A1:A0:F3` (also in each release's notes; check it with `apksigner verify --print-certs`). If you ever see an install fail with a signature error, do not uninstall and reinstall to "fix" it; check that the download came from this repository's releases.

## First start
1. Add an instance (name, https address). Sign in with your Dockge user (and the two-factor code if you use one).
2. For alerts open Alerts in the top bar, switch Alerts on and allow notifications. If alerts arrive late, set the app's battery use to Unrestricted.
3. For update information, switch on the image watcher in that Dockge. For crash, health, backup and security alerts the Dockge user needs the administrator role.

## Alerts
The app watches your Dockge servers itself and notifies you. No notification server (ntfy, Gotify, Apprise) and no Google push are involved: while alerts are on, the app keeps its connections to your instances open in a foreground service (Android shows a small permanent notification for it).

What it tells you about:
- a new image update (needs the image watcher switched on in that Dockge)
- a crash loop or an unhealthy container, a failed backup, a critical or high security finding (these come from the instance's monitoring feed and need your Dockge user to be an administrator)

Tapping an alert opens the instance, and the stack when the alert is about one stack. Alerts about cleanup, linked-server problems and Dockge's own updates cannot be watched this way, and only the instance you added is read, not the servers linked to it. On the lock screen alerts only say what kind of alert it is; names appear after unlocking.

Turn it on in the app: Alerts (top bar of the instance list), switch Alerts on, allow notifications. If alerts arrive late or stop, set the app's battery use to Unrestricted (the Alerts screen has a button).
