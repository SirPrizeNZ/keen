# Contributing to Keen

Thanks for your interest in improving Keen — a lightweight, remote-first Android TV
browser that reuses the system WebView instead of bundling a second engine.
Contributions of all sizes are welcome.

Keen is licensed under the **GNU AGPL-3.0**. By contributing, you agree that your
contributions are licensed under the same terms.

## Ways to help

- **Report bugs** — crashes, playback failures, sites that don't work, remote
  quirks. A clear repro and your device/box model go a long way.
- **Test on real hardware** — Keen targets cheap, low-memory Android TV boxes.
  Reports from actual devices (model, Android version, RAM) are especially useful.
- **Fix site compatibility** — many rough edges are per-site playback or
  navigation issues; small, targeted repairs are ideal first contributions.
- **Improve the code** — the [Roadmap](README.md#roadmap) lists larger efforts
  (64-bit builds, accessibility, more subtitle languages, richer favourites).
- **Docs** — clarify setup, architecture, or these guidelines.

## Before you start

For anything more than a small fix, please **open an issue first** to discuss the
approach — it saves everyone time and avoids duplicated work.

## Development setup

Keen is a single Android module written in **Kotlin**.

**Requirements**

- Android Studio (recent stable) or the Android command-line tools
- JDK 17
- Android SDK with API 35 (`compileSdk` 35, `minSdk` 29)

**Build and run a debug build**

```bash
# 32-bit (classic TV boxes)
./gradlew :app:assembleArmeabiV7aDebug

# or a universal debug build
./gradlew :app:assembleUniversalDebug
```

Install onto a device or emulator over adb:

```bash
adb install -r app/build/outputs/apk/armeabiV7a/debug/app-armeabiV7a-debug.apk
```

For a TV box on your network, connect with wireless debugging first
(`adb connect <tv-ip>:<port>`), then install as above.

## Project layout

The whole app lives under `app/src/main/java/com/keenzero/app/`, organised by
concern:

| Area | Package |
|:--|:--|
| Single Activity / home surface | `KeenActivity`, `home/` |
| WebView host & hardening | `web/` |
| Request/popup/overlay blocking | `blocking/`, `navigation/` |
| Remote input & cursor | `input/` |
| Media playback | `playback/` |
| Torrent streaming | `torrent/` |
| Resume / continuity | `continuity/` |

The [architecture diagram](README.md#architecture-at-a-glance) maps how these fit
together end to end.

## Pull requests

- Keep PRs **small and focused** — one logical change per PR is much easier to review.
- **Match the surrounding style**: the codebase favours clear names and comments
  that explain *why*, not *what*. Mirror the density and idiom of nearby code.
- Describe **what you changed and how you tested it** — ideally on real TV
  hardware, noting the device.
- Make sure the project still builds (`./gradlew :app:assembleArmeabiV7aDebug`).

## Responsible use

Keen is a general-purpose browser and an open-protocol media client. It bypasses
no DRM and defeats no access controls. Please keep contributions aligned with
lawful, user-controlled access to content.

## Security

If you find a security issue, please **do not** open a public issue — report it
privately via the repository's security contact so it can be addressed before
disclosure.
