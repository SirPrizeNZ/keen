<p align="center">
  <img src="assets/logo.png" alt="Keen" width="480">
</p>

<p align="center">
  <img src="assets/keen-title.png" alt="Keen" width="150">
</p>

<p align="center">
  <strong>A lightweight, remote-first Android TV browser that streams the modern web — including torrents — straight from the page you're on.</strong>
</p>

<p align="center">
  <a href="https://github.com/SirPrizeNZ/keen/releases/download/v0.1.92/keen-0.1.92-32bit-armeabi-v7a.apk"><img src="https://img.shields.io/badge/download-APK%20v0.1.92%20%C2%B7%2032--bit-111111?style=for-the-badge" alt="Download Keen v0.1.92 APK"></a>
  &nbsp;
  <a href="https://github.com/SirPrizeNZ/keen/releases/latest"><img src="https://img.shields.io/badge/github-releases-24292f?style=for-the-badge" alt="GitHub Releases"></a>
  &nbsp;
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-2ea44f?style=for-the-badge" alt="MIT License"></a>
</p>

---

Keen runs on the Android System WebView already on your TV — no bundled Chromium, no second engine — and adds a focused control, blocking and playback layer on top. One Activity, one live WebView, driven entirely by a remote.

## What it does

- ✅ **Streams from the browser** — open a site, pick what you want, block the junk, play.
- ✅ **Direct torrent streaming** — activate a magnet or `.torrent`, or paste a magnet in the address bar, and Keen streams the largest video immediately.
- ✅ **Native TV playback** — Media3/ExoPlayer reaches the platform's hardware decoders, so audio a WebView can't decode (E-AC-3, DTS) plays through.
- ✅ **English subtitles by default** — auto-selected whenever the media carries them.
- ✅ **Resume where you left off** — a **Continue** card restarts your last stream, including an interrupted torrent, after a power cycle or a low-memory kill.
- ✅ **Favourites** — one press from the control bar; sites come back as tiles on the home screen.
- ✅ **Layered ad & popup defence** — request blocking plus popup, redirect and overlay containment, not a single filter.
- ✅ **Built for a remote** — D-pad navigation with a pointer fallback, minute-by-minute timeline scrubbing, clean fullscreen and return.
- ✅ **Light on TV hardware** — a separate torrent process, continuity checkpoints and memory-pressure cleanup, tuned for 2 GB boxes.

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

1. On the TV, enable USB and wireless debugging in Developer options.
2. Note the TV's IP address and debugging port.
3. From a computer with Android platform tools:

```bash
adb connect <tv-ip>:5555
adb install -r keen-0.1.92-32bit-armeabi-v7a.apk
```

Accept the debugging prompt on the TV if it appears. Wireless debugging may show a port other than `5555` — use the one the TV displays.

## How it works

### Streaming & playback

- ✅ Torrent engine runs in a **separate process**, downloads **sequentially**, serves only over a **loopback HTTP bridge**, and **deletes its cache** when playback ends.
- ✅ Long playback holds a **foreground service**, so Android's app freezer can't stall the stream mid-episode.
- ✅ The loading screen reports live **peers, seeds and speed** with a **byte-accurate**, smoothly animated progress readout.

### Blocking that covers the whole journey

- network ad and tracker blocking
- service-worker request interception
- popup quarantine before a new window reaches the screen
- hostile redirect containment
- external-app escape prevention
- intrusive overlay removal
- site-specific playback and navigation repairs

Traditional blockers ask *"should this request load?"* Keen also asks *"did the user actually choose to go there?"* — legitimate playback and login flows continue; unwanted popups are destroyed without ever taking over the television.

### Remote control

- ✅ Directional focus that reaches off-screen elements and scrolls them into view.
- ✅ Long-press **OK** to switch between D-pad navigation and pointer control.
- ✅ Focus the timeline scrubber and hold left/right to walk playback a **minute at a time**.

## New in v0.1.92

- ✅ Remote-first **home screen** — favourite roundels and a **Continue / resume card** with a progress strip.
- ✅ **English subtitles** selected automatically when available.
- ✅ **Minute-by-minute** timeline scrubbing on the focused scrubber.
- ✅ **Byte-accurate** loading percentage with a smooth digit animation.
- ✅ Redesigned focus cue — an eased **inward border** instead of a scale-up.
- ✅ Torrent **resume position** and durable checkpoints across kills and power cycles.
- ✅ TV **overscan-safe** home layout.

## Legal

> Only stream content you are legally permitted to access. Keen does not bypass DRM or access controls.

## License

[MIT](LICENSE)
