# Icon design sources

Source art behind Reikai's shipped icons, kept in-repo so the generated Android
resources are reproducible. These are working files: edit here, re-export, and
regenerate the `res/` resources below.

| File | What it is | Shipped resource it backs |
|---|---|---|
| `monochrome.svg` | Flattened single-path "R-flame" silhouette (white) | [`app/src/main/res/drawable/ic_reikai.xml`](../../app/src/main/res/drawable/ic_reikai.xml) — the in-app logo (More / About header) and the notification small-icon. Rendered tinted at runtime, so it must stay a single-colour silhouette. |
| `monochrome-project.svg` | Editable Inkscape project for the monochrome mark | (working file) export `monochrome.svg` from this |
| `drawing.svg` | Master source drawing of the Reikai "R-flame" icon (full colour artwork) | The README logo and the launcher icon art (the flat renders below + `ic_launcher_foreground.xml`) are produced from this |
| `Reikai-release-flat.png`, `Reikai-preview-flat.png`, `Reikai-debug-flat.png` | Flat renders of the launcher icon, one per build channel | The foreground layer of each channel's launcher icon: `main` (stable) as [`ic_launcher_foreground.xml`](../../app/src/main/res/drawable/ic_launcher_foreground.xml), `preview` and `debug` as generated `mipmap-*/ic_launcher_foreground.webp`, wired by that source set's `mipmap-anydpi-v26/ic_launcher*.xml` |
| `Reikai-nightly-flat.png` | The purple render the pre-release channel used before it went teal | (kept for reference; backs nothing shipped). **Confusing name since the 2026-08-21 rename**: the channel is now called `nightly`, but the render it actually ships is `Reikai-preview-flat.png` above. The file names were left alone rather than swapped, which would have made this table lie about history. |

Each channel also sets its own `ic_launcher_background` colour, which must match the render's
deepest tone: `#280055` (stable), `#053A48` (preview), `#242424` (debug). The channels differ by
hue and saturation on purpose, since two icons separated only by lightness are indistinguishable
at launcher size. `<monochrome>` is deliberately NOT per-channel: every channel points at `main`'s
`ic_launcher_monochrome.xml`, so themed icons stay identical. Android Studio's Image Asset wizard
repoints that at the colour foreground and writes a per-channel copy, so check both
`ic_launcher.xml` and `ic_launcher_round.xml` after regenerating.

## Regenerating `ic_reikai.xml`

`ic_reikai.xml` is an Android vector built from `monochrome.svg` (viewBox `0 0 1024 1024`):
take the single `<path d="…">`, drop it into a `<vector>` with `viewportWidth/Height = 1024`
and `android:fillColor="#FFFFFFFF"`. The launcher art is multi-colour, so it cannot be reused
for the tinted logo / status-bar icon, which is why the dedicated monochrome silhouette exists.
