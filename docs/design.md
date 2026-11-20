# Design (0.1.1)

Material 3 with the app's own blue palette (light and dark). Wallpaper colors (dynamic color) are an opt-in switch in Settings, off by default: the first device test showed they override the palette and make the app look generic. The owner approved four mockups on 2026-11-20 and asked for two selectable app icons. This file is the spec the code follows.

## Principles
- A compact screen title directly under the status bar (`ScreenHeader`, no large app bar: it left a big empty gap), content in tonal cards (`surfaceContainer`, 16 dp corners), 16 dp screen padding, 8 dp between cards.
- One filled button per screen (the main action); everything else tonal or text.
- State is shown by an icon tile (40 dp, 12 dp corners) and a small tonal chip, never by color alone: the label is always there.
- Status colors: ok and warn come from `DockgeColors.status` (no slot in the Material scheme, light and dark variants in `Theme.kt`); error uses the scheme's own `errorContainer`. `Tone` (Ok, Warn, Error, Info, Neutral) in `ui/components/Components.kt` maps a meaning to container and content color.

## Screens
- **Bottom navigation** (Instances, Settings) on the two top-level screens only. Everything else (stacks, stack, updates, alerts, edit, diagnostics) has a back arrow and no bottom bar.
- **Instances**: summary line, an amber banner when stacks have updates (tap opens the Updates list), one card per instance (tile by connection state, name with the instance color dot, address, status line, update chip, overflow menu), extended FAB "Add instance".
- **Stacks**: summary, search field (pill), filter segments All / Running / Updates / Stopped, cards with a state tile and an Update chip. "Stopped" is every stack that is not running; the list has no per-stack health, so there is no "Issues" filter.
- **Stack detail**: status chip and where it runs, the single filled **Update** button when an update exists, tonal Start/Restart/Stop/Down, an amber update card (images, digests, image page link), a Containers section with a tile, state chip, image, uptime and ports and Restart/Stop or Start.
- **Update progress sheet**: modal bottom sheet, fixed size: title, phase, progress bar with percent, a checklist of the last items (check, spinner or warning icon), a detail line, "Show full output" and Hide/Close.
- **Alerts**: unchanged content, opened from a card in Settings.
- **Updates**: the amber banner opens one list of every stack with an update, grouped by instance; tap opens the stack.
- **Settings**: Alerts card, wallpaper-colors switch, App icon chooser, About (version, build). Stack detail: Start/Restart/Stop by state with icons, Down in the overflow menu.

## App icons
Two launcher icons, selectable in Settings: **Blue** (white layers on the app blue) and **Light** (dark layers on a pale blue tile). Both are adaptive icons with a monochrome layer, so Android 13 themed icons work. Implemented as two `activity-alias` entries that carry the launcher intent filter; `AppIcon` enables one and disables the other (new one first), with `DONT_KILL_APP`. `MainActivity` itself has no launcher filter any more; notifications still target it directly.
Consequence: the launcher entry changes from `MainActivity` to the `.IconBlue` alias, so a home-screen shortcut made from 0.1.0 can disappear when 0.1.1 is installed and has to be added again from the app drawer. Switching icons later does the same, which Settings says.

## Not in this change
Login and add-instance screens keep their current layout (they only pick up the new theme). No custom font. No change to behavior.
