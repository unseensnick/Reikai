# Ported Mihon docs: read this before editing

These 23 files under `docs/guides/` and `docs/faq/` began as **Mihon's documentation, copied**
from [mihonapp/website](https://github.com/mihonapp/website) at `455bd7c` (2026-08-16). They cover the
core reading experience Reikai inherits, which Reikai has never documented: getting started, tracking,
reader settings, backups, storage, downloads, source migration, local source and troubleshooting.
About 10,700 words.

**They are staged, not published.** The website's `sync-docs.mjs` reads `docs/*.md` at the top level
only, so nothing in these two folders reaches the site until someone extends that walker. That is
still deliberate: the text is debranded, but the illustrations below are Mihon's screenshots and the
content has not been checked against how Reikai actually behaves.

## Licence and attribution

Mihon's website is **MPL-2.0**, which is file-level copyleft. These files keep that licence even
inside an Apache-2.0 project, so keep the attribution above, and if Reikai's versions are published,
publish the modifications too. Credit them the way the code ports do.

## Done

- **Debranded.** 96 occurrences of the app name across 18 files became Reikai. Two things were
  deliberately kept: links to `mihon.app` and `github.com/mihonapp`, and the third-party tool
  actually called "Mihon Backup Viewer". `contribute.md` was deleted rather than edited: it was
  entirely Mihon's contribution process, and Reikai's own FAQ already answers bug reports, feature
  requests and pull requests.
- **The `<nav to="...">` shortcode is ported**, in the website repo at `src/.vitepress/shortcodes.ts`.
  The map is Reikai's own navigation rather than a copy: Reikai can show one combined Recents tab or
  separate Updates and History tabs, so all three keys exist and a doc picks what it means.

## The illustrations are settled

The assets now live beside the pages, under `docs/guides/` and `docs/faq/`, and are referenced by
absolute `/docs/...` paths. They belong in this repo for the same reason the markdown does: a UI
change that invalidates a screenshot happens here. The tree icons are shared, so they sit in
`docs/img/`.

There were 28 asset files, 5.6 MB, in Mihon's `public/`. What happened to each:

- **The 21 reader demos are reused as-is.** They show a page being rendered, not app chrome, and
  Reikai's reader is upstream's unchanged, so they are accurate. The three double-tap animations were
  re-encoded from animated WebP to VP9 WebM (3.9 MB to 858 KB) and their `<img>` tags became
  `<video autoplay loop muted playsinline>`.
- **The five app-UI crops were re-shot**, as six, on a clean emulator. All five were stale: the
  backup screenshots predate the "Last automatically backed up" line, and the crash-log one shows a
  "Send crash reports" row that exists in neither app any more. That row is now called **Share crash
  logs**, so the file is `share-crash-logs.{light,dark}.webp` and the page text was corrected.
  Mihon's page also served its dark capture to light-theme readers, which is why ours is six files.
- **The Android 7.1 clip was dropped**, along with the tab that held it. Reikai's `minSdk` is 26, so
  that path can never apply to a Reikai user.
- **The Android 10 install-permission clip is reused.** It records Android's own UI, not the app.

Captures follow `docs/dev/readme-showcase.md`: SystemUI demo mode for a clean status bar, app theme
on **System** so `adb shell cmd uimode night yes|no` drives the light and dark pair.

## What still has to happen before any of it ships

1. **Eight `/forks/` links have no target on Reikai's site.** Six in `backups.md`, one in
   `faq/general.md`, pointing at Mihon's fork-endorsement pages and at four individual forks.
   `npm run build` fails on dead links, so these have to be rewritten or dropped before the guides
   are wired in.
2. **The `file`, `file jpg`, `file cbz` and `file-extension` span classes** come from their theme
   stylesheet, and need either the CSS or a rewrite.
3. **`sync-docs.mjs` walks only the top level** and copies only `.md`. It needs to recurse and to
   carry the assets into the site's `src/public/docs/`.
4. **Two pre-Android-8 sections survive elsewhere.** `troubleshooting/index.md` has a WebView table
   with an "Android 6 and below" row, and `common-issues.md` addresses "Android 9 and below". Neither
   has an asset, so both were left for the content pass.

## Where Reikai already differs

Do not assume a file is accurate just because it reads plausibly. Known divergences to check while
editing: Reikai's library has two view modes, categories carry a sort override and span both content
types, the reader picks webtoon mode automatically, merged entries fold several sources into one card,
and novels exist at all. Nothing in these files knows about any of that.
