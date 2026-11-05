# dockge-for-android

Android app (Kotlin + Jetpack Compose) to manage dockge-enhanced instances (Aerya fork) from a phone. Personal sideload via Obtainium. Public repo: feerlessleadr/dockge-for-android. Standalone: no connections to other workspace projects.

- Read `STATUS.md` first, then `docs/progress.md`. Search `DECISIONS.md` only when needed.
- `reference/` holds a local clone of Aerya/dockge-enhanced. It is gitignored. Never commit it.
- External dependencies (not workspace connections): dockge-enhanced (Socket.IO/REST), ntfy server (push). No FCM.
- Public repo: no LAN IPs, real hostnames, usernames, keys, tokens or passwords in any file. Use `dockge.example.com`.
- Never write the GitHub token anywhere. `gh` lives at `C:\Program Files\GitHub CLI\gh.exe` (not on PATH).
- Keystore lives outside the repo; secrets go in GitHub repo secrets only. Signing key must never change.
- Ask before destructive or externally visible actions (visibility, secrets, releases, branch protection).
- Commits: small, `feerlessleadr <github@thecgkg.com>`, `git commit -F <file>`. Tags only when the owner says "publish it".
