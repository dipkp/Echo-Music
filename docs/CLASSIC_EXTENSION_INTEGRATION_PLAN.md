# Echo 5.2.85 Classic extension integration plan

## Non-negotiable boundary

- Keep the Echo Music 5.2.85 Compose UI, player, mini-player, bottom navigation, home,
  search, library, theme, icons, transitions, and animations unchanged.
- Import the Echo Nightly extension contract and dynamic-loading backend only.
- The sole new top-level UI entry is `Settings -> Extensions`, implemented with the
  existing `Material3SettingsGroup` and `Material3SettingsItem` components.
- Never commit this work to `main`; the integration lives on
  `classic-5.2.85-hotfix`.

## Architecture

1. Vendor Nightly's `common` extension contract in an isolated `echo-common` Android
   library. Keeping the original `dev.brahmkshatriya.echo.common` packages preserves
   binary compatibility with extension APKs without importing Nightly UI code.
2. Add a classic-only extension manager that:
   - discovers APKs in app-private storage;
   - validates Nightly manifest metadata and extension type declarations;
   - loads extension classes with `DexClassLoader`, including ABI-native libraries;
   - injects per-extension settings and metadata;
   - persists the active music extension and track payloads needed by a restored queue.
3. Adapt extension search shelves/tracks to the existing Innertube `SearchSummaryPage`
   and `SongItem` models. `OnlineSearchResult` continues to render the existing classic
   list item, typography, menus, spacing, and mini-player.
4. Mark extension tracks with an opaque `echoext:` media ID. Intercept only these IDs in
   the existing resolving data source, ask the active extension's `TrackClient` for a
   stream, and hand the resolved URL/headers back to the existing Media3 player.
5. Use a single-item classic `ListQueue` for extension results so the app never sends an
   extension ID to YouTube's radio/next endpoint.

## Verification

- Build `assembleUniversalFossDebug` on the repository's JDK 21 / Android SDK 36 CI.
- Confirm `versionCode = 526`, `versionName = 5.2.85`, and the classic application ID.
- Treat Kotlin/Gradle failures as integration defects and iterate until CI produces the
  classic APK artifact.
- Runtime-smoke the install, activate, search, and play paths with a compatible music
  extension when an Android runtime is available; loader errors must remain visible in
  the Extensions settings screen rather than crashing the app.
