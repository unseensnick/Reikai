# README showcase animation

The animated banner in the README (`.github/readme-images/screens.webp`) is a **transparent
animated WebP**: five device-framed phones side by side, each cross-fading between two app states.
Transparent so it blends on GitHub's light or dark theme. Panels:

1. **Library**: manga library ⟷ novels library
2. **Detail**: manga detail (Unified multi-source + Related carousel) ⟷ novel detail
3. **Reader**: manga reader ⟷ novel reader
4. **History**: the unified "All" history, light ⟷ dark theme
5. **View modes**: tabbed library ⟷ single-list library

The phone frame is the official **Pixel 10 Pro XL** device-art skin (screen 1344x2992, so the
emulator must run at that exact resolution for a 1:1 fit).

## Reproduction kit

Everything needed to rebuild lives in `.github/readme-images/showcase/`:

- `stills/` - the 9 source screenshots (1344x2992 PNGs).
- `frame/back.png`, `frame/mask.png` - the Pixel 10 Pro XL frame (body + corner/cutout mask).
- `make-frames.sh` - composites each still into the frame, rounds the screen corners, writes `framed/`.
- `make-webp.sh` - builds the transparent animated WebP from `framed/` into `../screens.webp`.

`framed/` and the `_capture/` scratch folder are git-ignored (regenerated, not committed).

### Rebuild from the existing stills (no device needed)

```bash
cd .github/readme-images/showcase
bash make-frames.sh    # stills/ + frame -> framed/
bash make-webp.sh      # framed/ -> ../screens.webp
```

Needs `ffmpeg` with the `libwebp_anim` encoder on PATH. Note: ffmpeg can **encode** animated WebP
but cannot decode it back, so verify the result by opening `screens.webp` in a browser/viewer (or
render a frame straight from the filtergraph in `make-webp.sh` with `-ss <t> -frames:v 1 out.png`).

The WebP **must be lossless** (`-lossless 1`): lossy WebP encodes alpha as subsampled `yuva420p`,
which leaves a faint tinted background where it should be transparent. Lossless keeps alpha perfect,
so the render size is kept modest (derived from `H`) to keep the file a few MB.

## Capturing fresh stills

Captures are driven over `adb` against a **Pixel 10 Pro XL** AVD (Android Studio Device Manager).
The screenshots are just the device framebuffer (1344x2992); the frame is composited later, so the
emulator's own skin doesn't matter for the output.

1. **AVD**: 1344x2992, density 480, the `pixel_10_pro_xl` skin. Install the debug app
   (`eu.kanade.tachiyomi.debugY2k`) and restore a backup so the library/history have content.
2. **Clean status bar** via SystemUI demo mode (re-broadcast before each capture, it can lapse). See
   the `clean-status-bar-capture` note in the agent memory for the full recipe; the key flags:
   `adb shell settings put global sysui_demo_allowed 1` then the demo broadcasts with
   `network ... wifi show level 4 fully true` (drops the no-internet `!`) and `notifications visible false`.
3. **Theme**: app appearance set to **follow system**, then toggle with `adb shell cmd uimode night yes|no`
   (dark for panels 1-3 and 5, plus both for the panel-4 history light/dark pair).
4. **Scrollbar**: wait ~3s after any scroll before `screencap` so the fading scrollbar is gone.
5. Capture the 9 states into `stills/` with these exact names (the scripts depend on them):
   `p1a_manga_lib`, `p1b_novel_lib`, `p2a_manga_detail`, `p2b_novel_detail`, `p3a_manga_reader`,
   `p3b_novel_reader`, `p4a_history_all_light`, `p4b_history_all_dark`, `p5a_manga_tabbed`.

Capture method (byte-accurate): `adb -s <emu> shell screencap -p /sdcard/x.png` then `adb pull`
(use PowerShell for `/sdcard` paths; MSYS bash mangles them).

## Tuning

- **Timing** (`make-webp.sh`): `HOLD` = seconds each state holds, `FADE` = cross-dissolve seconds,
  `L` must equal `2*HOLD + 2*FADE`. Current: hold 2.8s, fade 0.3s, loop 6.2s.
- **Layout**: `STEP` = horizontal spacing between phones; `H` = phone height in the strip.
- **Frame fit**: `make-frames.sh` places the screen at (60,55) and rounds corners to radius 108 (from
  the skin `layout`). If you swap to a different device skin, update those three numbers.
- A different frame (e.g. a Samsung Galaxy skin) works too, but Samsung's official emulator skins bake
  a colored backdrop around the phone that has to be masked out; the Pixel skins are clean device-only
  frames with a transparent surround, which is why this uses one.
