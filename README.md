<p align="center">
  <img src="assets/logo.png" alt="Keen" width="440">
</p>

<p align="center">
  <img src="assets/keen-title.png" alt="Keen" width="150">
</p>

<p align="center">
  <strong>Your Android TV already has a browser engine.<br>Keen just makes it <em>yours</em>.</strong>
</p>

<p align="center">
  <a href="https://github.com/SirPrizeNZ/keen/releases/download/v0.1.92/keen-0.1.92-32bit-armeabi-v7a.apk"><img src="https://img.shields.io/badge/download-APK%20v0.1.92%20%C2%B7%2032--bit-111111?style=for-the-badge" alt="Download Keen v0.1.92 APK"></a>
  &nbsp;
  <a href="https://github.com/SirPrizeNZ/keen/releases/latest"><img src="https://img.shields.io/badge/github-releases-24292f?style=for-the-badge" alt="GitHub Releases"></a>
  &nbsp;
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-2ea44f?style=for-the-badge" alt="MIT License"></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/size-18.4_MiB-111111" alt="18.4 MiB">
  <img src="https://img.shields.io/badge/Android_TV-10%2B_(API_29)-3ddc84" alt="Android TV 10+">
  <img src="https://img.shields.io/badge/ABI-armeabi--v7a-555" alt="armeabi-v7a">
  <img src="https://img.shields.io/badge/engine-System_WebView-orange" alt="System WebView">
</p>

---

An **18.4 MiB** Android TV app that turns the WebView already sitting on your box into a full streaming browser — torrents, ad-blocking, hardware-decoded playback and remote-first navigation included. **No bundled Chromium. No second engine. No bloat.**

> Open any site on your TV, tap a video or a magnet link, and Keen strips the junk, grabs the stream, and hands it to the hardware decoder — all from a single Activity driven by a five-button remote.

---

## Why it exists

Android TV boxes ship with a perfectly capable browser engine — the **Android System WebView**, a Chromium-based component Google maintains and updates at the OS level. Most "TV browser" apps ignore it and bundle their own 100+ MiB copy of Chromium.

Keen doesn't. It wraps the WebView **already on your device** with three focused layers:

| Layer | What it adds |
|:--|:--|
| **Control** | D-pad focus, pointer fallback, address bar, favourites, a remote-first home screen |
| **Blocking** | Seven-stage ad, popup, redirect and overlay defence |
| **Playback** | Media3 / ExoPlayer with hardware decoders, torrent streaming, subtitle selection, resume |

The result: an **18.4 MiB** signed APK that boots in under a second on a 2 GB box.

---

## What you get

### 🌐 The simplest browser for Android TV
Open any site. Navigate with the D-pad or a pointer. Bookmark favourites to the home screen as tiles. It's a real WebView — every site that works in Chrome on Android works here.

### 🧲 Stream torrents without leaving the page
Tap a magnet link, drop a `.torrent`, or paste a `magnet:` URI into the address bar. Keen spins up a **separate torrent process**, downloads the largest video file **sequentially**, and pipes it over a **loopback HTTP bridge** straight into ExoPlayer. No files land in your Downloads folder. The cache deletes itself when you stop.

### 🔊 Play audio the WebView can't
The WebView's software decoder chokes on E-AC-3, DTS and similar codecs. Keen intercepts the media URL and hands it to **Media3 / ExoPlayer**, which reaches the TV's **hardware decoders** directly. Surround sound just works.

### 💬 Subtitles, automatically
If the stream carries English subtitle tracks, Keen selects them by default. No menu diving.

### ⏯ Resume after anything
A **Continue** card on the home screen remembers your last stream — URL, playback position and torrent download offset. Power cut? Low-memory kill? Reboot? Pick up where you left off.

### 🛡 Seven layers of blocking
Not one filter. Seven:

1. **Network-level** ad & tracker request blocking
2. **Service-worker** request interception
3. **Popup quarantine** — new windows are caught before they render
4. **Hostile-redirect** containment
5. **External-app escape** prevention — no surprise "open in another app" hijacks
6. **Intrusive-overlay** removal
7. **Site-specific** playback & navigation repairs

> [!NOTE]
> Traditional blockers ask *"should this request load?"*
> Keen also asks *"did the user actually choose to go there?"* — legitimate playback and login flows continue, while unwanted popups are destroyed before they ever touch your screen.

### 📺 Built for the remote, not a mouse
- Directional focus reaches off-screen elements and scrolls them into view
- **Long-press OK** toggles between D-pad and pointer mode
- Focus the scrubber and **hold ← / →** to walk playback **one minute at a time**
- Clean fullscreen, clean return, no orphaned UI

### 🪶 Tuned for cheap hardware
- Torrent engine runs in a **separate process** — a crash never takes the browser down
- A **foreground service** keeps Android's app freezer from killing a long stream
- **Memory-pressure cleanup** and continuity checkpoints designed for **2 GB RAM** boxes
- Loopback-only HTTP bridge — nothing is exposed to the network

---

## Download

| | |
|:--|:--|
| **Version** | v0.1.92 (`versionCode` 112) |
| **Platform** | Android TV / Google TV · Android 10+ (API 29+) |
| **ABI** | **32-bit ARM (`armeabi-v7a`)** |
| **Size** | 18.4 MiB (signed) |
| **APK** | **[keen-0.1.92-32bit-armeabi-v7a.apk](https://github.com/SirPrizeNZ/keen/releases/download/v0.1.92/keen-0.1.92-32bit-armeabi-v7a.apk)** |
| **Checksum** | [`SHA256SUMS`](https://github.com/SirPrizeNZ/keen/releases/download/v0.1.92/SHA256SUMS) |
| **Release notes** | [Keen v0.1.92](https://github.com/SirPrizeNZ/keen/releases/tag/v0.1.92) |

The published build is the 32-bit ARMv7 APK for classic Android TV hardware. There is no dedicated arm64 package yet.

### Install over Wi-Fi

1. On the TV, enable **USB debugging** and **Wireless debugging** in Developer options.
2. Note the IP address and port the TV displays.
3. From a computer with Android platform tools:

```bash
adb connect <tv-ip>:<port>
adb install -r keen-0.1.92-32bit-armeabi-v7a.apk
```

4. Accept the debugging prompt on the TV if it appears.

> [!TIP]
> Wireless debugging often shows a port other than `5555` — use the exact one the TV displays.

---

## New in v0.1.92

- 🏠 **Remote-first home screen** — favourite roundels and a **Continue** card with a live progress strip
- 💬 **Auto English subtitles** when the media carries them
- ⏱ **Minute-by-minute scrubbing** on the focused timeline
- 🔢 **Byte-accurate loading %** with a smooth digit animation
- 🎯 **Redesigned focus cue** — an eased inward border instead of a scale-up
- 💾 **Torrent resume** — durable checkpoints survive kills and power cycles
- 📐 **Overscan-safe** home layout for older panels

---

## Architecture at a glance

```
┌─────────────────────────────────────────────┐
│               Keen  ·  1 Activity            │
│                                              │
│  ┌───────────┐   ┌───────────────────────┐   │
│  │  Control  │   │   System WebView      │   │
│  │  bar /    │──▶│   (already on TV,     │   │
│  │  home     │   │    Chromium-based,    │   │
│  │  screen   │   │    updated by Google) │   │
│  └───────────┘   └──────────┬────────────┘   │
│                             │ media URL      │
│                  ┌──────────▼────────────┐   │
│                  │   Media3 / ExoPlayer  │   │
│                  │   (HW decoders, subs) │   │
│                  └──────────▲────────────┘   │
│                             │ loopback HTTP  │
│  ┌──────────────────────────┴────────────┐   │
│  │  Torrent process (separate)           │   │
│  │  libtorrent4j · sequential · cache    │   │
│  │  auto-delete · foreground service     │   │
│  └───────────────────────────────────────┘   │
└──────────────────────────────────────────────┘
```

The loading screen reports live **peers, seeds and speed** with a byte-accurate, smoothly animated progress readout — so you always know what the stream is doing before the first frame.

---

## Legal

> [!IMPORTANT]
> Only stream content you are legally permitted to access. Keen does not bypass DRM or access controls.

---

## License

[MIT](LICENSE)
