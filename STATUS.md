# STATUS (2026-11-20)

**Phase:** v0.1.0 is published. 0.1.1 (Material 3 redesign and two selectable app icons) is built on branch `m8-design` and in a pull request; not yet looked at on a device. 149 unit tests pass.

## Done
- M0 to M7 done and owner-verified (see docs/progress.md). Release workflow proven (v0.1.0, signed, certificate pinned).
- 0.1.1 code: theme with status colors, bottom navigation (Instances, Alerts, Settings), restyled instance list, stack list (search, filter segments), stack detail, progress sheet checklist, Settings tab with the icon chooser, two adaptive icons switched through activity aliases. Spec: `docs/design.md`. Release notes drafted: `docs/releases/v0.1.1.md`.

## Next action
- Owner: install the debug build from the PR branch, look at every screen in light and dark, try switching the icon, report what to change.
- Then: fix findings, merge on "Merge PR N", and publish 0.1.1 on "publish it" (that also proves the Obtainium update over 0.1.0).

## Open questions
- Does the icon switch work on the owner's launcher (aliases can be slow or leave the old icon until the launcher refreshes)?
- TOTP seed storage: not implemented; ask before ever doing it.

## Not yet verified
- 0.1.1 layout on a device (small screens, dark mode, large font), the icon switch, bottom bar behavior with the back stack and deep links from alerts.
- Installing the signed release through Obtainium, and an Obtainium update over a second tag.
- Token re-login after a server restart, a revoked token, login rate limit (live). Linked (federated) servers: update status and events. The progress parser against other compose versions.

## Owner manual steps
- Test the 0.1.1 debug build and report. Release tags only on "publish it". A tag that produced a public release is never moved or deleted.
