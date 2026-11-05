# Progress log

## 2026-11-05
- Registered project, initialised repo, cloned reference fork.
- Pushed initial commit, added pr.yml and release.yml. Protection and secrets pending owner approval.
- Applied branch protection on main (PR + build-and-test, admins enforced).
- Created release keystore and set 4 repo secrets via local script (passwords typed in terminal only).
- Phase 0: three source readings merged into docs/dockge-api.md (PR). Gaps and companion options listed at the top. Waiting for owner approval.
- Owner approved plan. Wrote docs/architecture.md and docs/build-plan.md (M0 to M7). Nothing verified on a real instance yet.

## 2026-11-05, M0 scaffold
- Gradle project (AGP 9.4.1, Kotlin 2.4.21, Compose BOM 2026.09.00, Gradle wrapper 9.8.1), copied the wrapper and version catalog pattern from the owner's other app. Package `com.feerlessleadr.dockge`, minSdk 26, targetSdk 36, compileSdk 37.
- Release signing from `SIGNING_*` env vars; `-PversionName` and `-PversionCode` from the release workflow. A release build without the key fails unless `-PallowDebugSigning` is passed.
- Verified locally (Windows, JBR 25): `assembleDebug testDebugUnitTest` passes; `assembleRelease -PallowDebugSigning -PversionName=0.1.0 -PversionCode=100` succeeds and `output-metadata.json` shows versionName 0.1.0, versionCode 100.
- NOT verified: the app has not been launched on an emulator or phone; CI run on a real Gradle project (this PR is the first); the signed release path (needs a tag).
- `.gitattributes` forces LF on `gradlew` so Linux CI can run it.
- In PowerShell, quote `-P` arguments that contain dots (`'-PversionName=0.1.0'`), otherwise the argument is split.
