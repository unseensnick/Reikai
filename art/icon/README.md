# Icon design sources

Source art behind Reikai's shipped icons, kept in-repo so the generated Android
resources are reproducible. These are working files: edit here, re-export, and
regenerate the `res/` resources below.

| File | What it is | Shipped resource it backs |
|---|---|---|
| `monochrome.svg` | Flattened single-path "R-flame" silhouette (white) | [`app/src/main/res/drawable/ic_reikai.xml`](../../app/src/main/res/drawable/ic_reikai.xml) — the in-app logo (More / About header) and the notification small-icon. Rendered tinted at runtime, so it must stay a single-colour silhouette. |
| `monochrome-project.svg` | Editable Inkscape project for the monochrome mark | (working file) export `monochrome.svg` from this |
| `drawing.svg` | Master source drawing of the Reikai "R-flame" icon (full colour artwork) | The README logo and the launcher icon art (the flat renders below + `ic_launcher_foreground.xml`) are produced from this |
| `Reikai-debug-flat.png`, `Reikai-nightly-flat.png`, `Reikai-release-flat.png` | Flat renders of the launcher icon per build channel | [`app/src/main/res/drawable/ic_launcher_foreground.xml`](../../app/src/main/res/drawable/ic_launcher_foreground.xml) + `mipmap-anydpi-v26/ic_launcher*.xml`; background `#280055` in `res/values/ic_launcher_background.xml` |

## Regenerating `ic_reikai.xml`

`ic_reikai.xml` is an Android vector built from `monochrome.svg` (viewBox `0 0 1024 1024`):
take the single `<path d="…">`, drop it into a `<vector>` with `viewportWidth/Height = 1024`
and `android:fillColor="#FFFFFFFF"`. The launcher art is multi-colour, so it cannot be reused
for the tinted logo / status-bar icon, which is why the dedicated monochrome silhouette exists.
