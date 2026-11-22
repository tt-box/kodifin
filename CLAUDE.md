# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Kodifin: an Android remote control (Jetpack Compose, Material 3) for media centers and network players — Kodi, Jellyfin/Emby, Plex, UPnP/DLNA, Chromecast, AirPlay 1, Roku, Fire TV/Android TV (ADB). **Zero trackers** is a hard product requirement. minSdk 26, targetSdk 36, compileSdk 37, JDK 17+.

Code comments, KDoc, UI strings, README and commit messages are written in **French** — keep that convention. Package root: `io.github.ttbox.kodifin`.

## Commands

On Windows use `gradlew.bat` (or `./gradlew` from Git Bash).

```bash
./gradlew test testDebugUnitTest lint assembleDebug       # what the README calls "the build"
./gradlew :protocol:kodi:test                              # one JVM protocol module
./gradlew :protocol:kodi:test --tests "io.github.ttbox.kodifin.kodi.KodiTest"
./gradlew :app:testDebugUnitTest --tests "io.github.ttbox.kodifin.discovery.*"
./gradlew koverHtmlReport                                  # aggregated coverage → build/reports/kover/html/index.html
./gradlew koverVerify                                      # fails under 80 % line coverage (run by CI, not wired to `check`)
bash scripts/check-no-trackers.sh                          # fails if a tracking lib is in the release classpath/APK (CI runs it)
```

CI (`.github/workflows/ci.yml`) runs: `test testDebugUnitTest lint assembleDebug assembleRelease koverXmlReport koverHtmlReport koverVerify`, then the tracker check, CycloneDX + OSV-Scanner CVE scan (also weekly by cron), and regenerates `.github/badges/coverage.svg` and `.github/badges/cve.svg` on `main`. The dependency graph is submitted by `dependency-graph.yml` (Gradle file changes + weekly).

Note: `test` covers the pure-JVM modules; Android modules (`:app`, `:feature:cast`) need `testDebugUnitTest`.

## Architecture

Multi-module Gradle build (versions in `gradle/libs.versions.toml`):

- `core/model` — the shared contract: `RemoteController` (`RemoteController.kt`), `Device`/`DeviceKind`, `RemoteKey`, `Capability`, `PlaybackState`, typed errors (`RemoteError.kt`: `RemoteException(reason)`, `UnsupportedActionException(action, kind)`), OkHttp helpers (`kodifinHttpClient`), UDP abstractions and SSDP (`Ssdp.kt`, shared by UPnP and Roku). Also has `testFixtures` (`TestHttp.kt`, `CapabilityContract.kt`) consumed by protocol and app tests.
- `protocol/*` (kodi, mediaserver, plex, upnp, roku, airplay, firetv) — **pure Kotlin/JVM libraries, no Android dependency**. Each implements `RemoteController` for one protocol. Tested without emulator via MockWebServer, Turbine and fake transports. Keep them Android-free.
- `feature/cast` — Google Cast (Android library; needs Play Services).
- `app` — Compose UI, device discovery, DataStore persistence, Bluetooth HID keyboard, ADB pairing UI. `RemoteViewModel` owns the connection; each panel is a feature object sharing a `RemoteSession` (`vm.queue`, `vm.library`, `vm.series`, `vm.search`, `vm.transfer`). Pure form logic lives in `connection/` (`DeviceForm.kt`), texts in `text/` (`UiText`, `ErrorText.kt`).

Key design points (spread across several files):

- **Capability model**: `RemoteController` has default methods that throw `UnsupportedActionException`; controllers advertise `capabilities` and `supportedKeys`. Optional features are separate opt-in interfaces in `core/model/.../MediaControls.kt` (`SupportsImages`, `SupportsQueue`, `SupportsLibrary`, `SupportsTracks`, `SupportsPower`, …) plus `PersistsCredentials`, `DescribesServer`, `SupportsTransferOut`. The UI checks `is SupportsX` to show features — add new optional features as a new interface rather than widening `RemoteController`. `assertHonorsCapabilities` (testFixtures) checks that nothing advertised throws `UnsupportedActionException`; `CapabilityContractTest` applies it to every controller.
- **Errors**: protocols never put user-facing text in exceptions; they throw `RemoteException(RemoteError.X)` / `UnsupportedActionException(UnsupportedAction.X)` and the app translates via `Throwable.toUiText()` (`text/ErrorText.kt`, `strings_errors.xml`).
- **Manual DI**: `AppContainer` in `app/.../KodifinApp.kt` wires everything (no DI framework). `ControllerFactory` (`app/.../controller/ControllerFactory.kt`) maps a `Device` to its protocol controller; Android-dependent controllers (Fire TV/dadb, Cast) go through the `PlatformControllers` interface so the factory stays JVM-testable.
- **Discovery**: `DiscoveryManager` aggregates `DiscoverySource`s (mDNS via an `NsdBackend` abstraction, SSDP, GDM, UDP 7359…). Android system classes (NsdManager, MulticastLock) sit behind small ports (`NsdBackend`, `MulticastLockPort`) so logic is unit-testable.
- **Persistence**: `DeviceRepository` and `SettingsRepository` use Preferences DataStore (separate files). Secrets (Kodi password) are encrypted via `SecretStore` (Android Keystore); Jellyfin/Emby passwords are exchanged for a token and discarded.
- **Coverage exclusions**: root `build.gradle.kts` excludes `@Composable` functions, `@Preview`, generated code and thin Android/SDK adapters from Kover. When adding a new untestable Android adapter, follow the same port/adapter split and add it to that exclusion list rather than lowering the 80 % threshold.

## Privacy constraints (enforced by CI)

- No analytics, ads or crash-reporting SDKs. `app/build.gradle.kts` excludes `com.google.android.datatransport` and firebase encoders pulled in by the Cast SDK; `scripts/check-no-trackers.sh` must stay green.
- Controllers must only talk to the controlled device; the only off-LAN traffic allowed is plex.tv when the user links a Plex account.
- The OkHttp client only follows redirects to the same host (tokens/passwords must not leak).

## Build / release specifics

- Root `build.gradle.kts` pins patched versions of build-tool transitive deps (bouncycastle, guava, jackson…) for CVE alerts; CycloneDX inventory only includes runtime classpaths.
- GitHub Actions are pinned by commit SHA; Dependabot PRs are auto-merged when CI is green.
- Release: push a `vX.Y.Z` tag → `.github/workflows/release.yml` builds signed + unsigned APK/AAB, checks the pinned signing-cert SHA-256. Version name comes from the tag (`KODIFIN_VERSION_NAME`), versionCode from commit count (`KODIFIN_VERSION_CODE`). Local signing reads `keystore.properties` (git-ignored; RSA 4096 upload key, alias `kodifin-upload`; Google Play re-signs with its own key); without it `assembleRelease` yields an unsigned APK.

## Pending follow-ups

- **Jellyfin Tizen remote control (upstream).** Jellyfin 12+ disables legacy authorization by default; the Tizen app from the Samsung store (1.1.0) embeds an old jellyfin-web that opens its WebSocket with `?api_key=`, so its session is never remote-controllable (Kodifin then reports "Aucun client contrôlable"). Tracked upstream in jellyfin/jellyfin-tizen#441, with fixes proposed in #442 (build from `release-12.z`) and #443 (auto-update workflow, app version aligned with jellyfin-web). When resuming work, check their status with `gh issue view 441 --repo jellyfin/jellyfin-tizen` and `gh pr view 442|443 --repo jellyfin/jellyfin-tizen`.
- **Temporary server workaround to undo.** On the developer's home Jellyfin server (Thor), `EnableLegacyAuthorization` was set to `true` (2026-11-22) so the TV stays controllable. Once a Tizen build with a recent jellyfin-web (one whose WebSocket uses `ApiKey`) is published and installed on the TV, set it back to `false` (Dashboard → Networking, or `POST /System/Configuration`) and confirm the TV session still reports `SupportsRemoteControl: true`.
