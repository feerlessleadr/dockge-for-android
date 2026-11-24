# STATUS (2026-11-20)

**Phase:** v0.1.1 is published (https://github.com/feerlessleadr/dockge-for-android/releases/tag/v0.1.1): Material 3 redesign and two selectable app icons. v0.1.0 was published earlier the same day. 149 unit tests pass.

## Done
- M0 to M7 done and owner-verified (see docs/progress.md). Release workflow proven twice (v0.1.0, v0.1.1: signed, certificate pinned).
- 0.1.1 (PR 14, merged): own blue palette by default (wallpaper colors opt-in), compact screen titles, bottom navigation (Instances, Settings), Updates screen behind the amber banner, Alerts opened from Settings, stack filters, state-aware stack buttons, checklist progress sheet, Blue and Light launcher icons. Spec: `docs/design.md`. Owner tested the debug build ("looks better, merge").
- Published: `dockge-for-android-v0.1.1.apk` (1860242 bytes, SHA-256 2c597142fd6aded74f0d8012a54ca6ae2056c17c18c0a3053827890a8801568c) and its `.sha256`. Same pinned signer certificate as 0.1.0.

## Next action
- Owner: update 0.1.0 to 0.1.1 through Obtainium and report. That proves the update path (same key, higher version code). Check the app opens, the icon switch works in Settings, and a home-screen shortcut from 0.1.0 may need adding again.
- Then pick the next work (ideas below). Nothing is queued.

## Open questions
- What next? Candidates: per-service update, update-all, rollback, container logs, compose editor, terminal.
- TOTP seed storage: not implemented; ask before ever doing it.

## Not yet verified
- Obtainium install and update with the signed releases (0.1.0 to 0.1.1), and the signed build at runtime.
- Icon switch on the owner's launcher; 0.1.1 layout on small screens and large fonts.
- Token re-login after a server restart, a revoked token, login rate limit (live). Linked (federated) servers: update status and events. The progress parser against other compose versions.

## Owner manual steps
- Update through Obtainium and report. Release tags only on "publish it". A tag that produced a public release is never moved or deleted; a bad release is replaced by the next patch version.
