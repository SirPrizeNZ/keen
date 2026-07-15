<p align="center">
  <img src="assets/logo.png" alt="Keen" width="280">
</p>

<h1 align="center">Keen</h1>

<p align="center">
  <strong>Android TV browser for streaming sites and D-pad remotes.</strong><br>
  Not a phone browser. Not a desktop browser. TV-only.
</p>

<p align="center">
  <a href="releases/keen-0.1.14-armeabi-v7a.apk"><img src="https://img.shields.io/badge/download-APK%20v0.1.14-111111?style=for-the-badge" alt="Download APK"></a>
  <a href="https://github.com/SirPrizeNZ/keen/releases/latest"><img src="https://img.shields.io/badge/github-releases-24292f?style=for-the-badge" alt="Releases"></a>
</p>

---

## Download

| Build | Target | File |
|-------|--------|------|
| **v0.1.14** (current) | **32-bit ARM (`armeabi-v7a`)** Android TV | **[keen-0.1.14-armeabi-v7a.apk](releases/keen-0.1.14-armeabi-v7a.apk)** |

- SHA-256: see [`releases/SHA256SUMS`](releases/SHA256SUMS)
- Same artefact is attached on [GitHub Releases](https://github.com/SirPrizeNZ/keen/releases)

**Install (sideload):** copy the APK to the TV (USB, network share, or `adb install`), then open it with a file manager that supports package install, or:

```bash
adb connect <tv-ip>:5555
adb install -r releases/keen-0.1.14-armeabi-v7a.apk
```

Requires **Android TV / Google TV, API 29+ (Android 10+)**.

---

## Purpose

Keen exists to make real streaming websites usable on a television with a **standard remote**.

Most TV “browsers” are cut-down mobile or desktop Chromium shells. They assume touch, mouse, tabs, and a user who can dismiss popups. Streaming catalogues do the opposite: heavy pages, nested players, full-screen video, and hostile interstitials.

Keen is built around one journey:

```text
Launch → open site → find content → play → fullscreen → control with D-pad → back out cleanly
```

---

## How it differs from other browsers

| | Typical TV / mobile browser | Keen |
|--|----------------------------|------|
| **Platform** | Phone/tablet first, TV optional | **Android TV only** |
| **Input** | Touch / mouse / soft keyboard | **D-pad remote first** (continuous pointer + DOM focus) |
| **UI chrome** | Tabs, bookmarks, extensions | Single browse shell: URL bar, progress, pointer |
| **Streaming** | Generic web page | Playback-oriented WebView path (fullscreen, media keys) |
| **Hostile UI** | User handles popups manually | Overlay / “not a robot” / QR trap guard (SPA-safe) |
| **Engine** | Often full Chromium fork | **System WebView** — smaller install, OS-updated engine |
| **Scope** | General browsing | Streaming sites and remote operation |

Keen is **not**:

- a Chromium fork  
- a phone browser with a leanback icon  
- a tabbed multi-window desktop browser  
- a torrent client, VPN, or media downloader  

---

## Platform & ABI

| Item | Value |
|------|--------|
| Form factor | **Android TV / Google TV only** |
| minSdk | **29** (Android 10) |
| targetSdk | 35 |
| **Primary release** | **`armeabi-v7a` (32-bit ARM)** |
| arm64-v8a APK | **Not shipped yet** |
| Runtime | Pure Kotlin / Java + System WebView (no app JNI today) |

**Why 32-bit first:** a large share of living-room Android TV boxes still run **ARMv7**. The published release is the `armeabiV7a` product flavour (`com.keenzero.app.v7a`).

The codebase also defines a `universal` flavour (same Java/Kotlin bytecode). That can install on 64-bit devices, but **the supported, signed download is the 32-bit ARMv7 APK**. A dedicated arm64 package will be published when it is a first-class product target.

---

## Features (current)

- Leanback launcher entry + TV banner  
- System WebView browse surface  
- Continuous remote pointer (hold-to-move) and long-press mode toggle  
- URL bar usable from the pointer; Enter loads and dismisses IME  
- Horizontal rail scroll under the pointer (Netflix-style carousels)  
- Hostile interstitial / QR “confirm you’re not a robot” guard (SPA-safe)  
- Navigation containment for common trap patterns  
- Host-based blocking assets for lab / baseline lists  

---

## Build from source

Requirements: JDK 17, Android SDK 35, release keystore (for release builds).

```bash
./gradlew :app:assembleArmeabiV7aRelease
# APK:
# app/build/outputs/apk/armeabiV7a/release/app-armeabiV7a-release.apk
```

Release signing is read from `~/.keen-zero/signing/keen-release.properties` (or `local.properties` keys). Debug builds do not need that keystore:

```bash
./gradlew :app:assembleArmeabiV7aDebug
```

---

## Repository layout

```text
app/                 Android application (Kotlin)
branding/            Icon + TV banner masters and density pack
assets/              README logo (transparent background)
releases/            Published APK + checksums
```

Lab HTML fixtures under `app/src/main/assets/lab/` are for development and automated checks, not end-user content.

---

## Status

Pre-release. Validated on physical Android TV hardware (ARMv7). Expect rough edges on hostile sites; report issues with device model, WebView version, and URL.

---

## License

[MIT](LICENSE)
