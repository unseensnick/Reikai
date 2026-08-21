# Ported Mihon docs: read this before editing

These 23 files under `docs/guides/` and `docs/faq/` began as **Mihon's documentation, copied**
from [mihonapp/website](https://github.com/mihonapp/website) at `455bd7c` (2026-08-16). They cover the
core reading experience Reikai inherits, which Reikai has never documented: getting started, tracking,
reader settings, backups, storage, downloads, source migration, local source and troubleshooting.
About 10,700 words.

**They are live on the site.** `sync-docs.mjs` walks the whole `docs/` tree, carrying the markdown
into `src/docs/` and everything else into `src/public/docs/`, and the sidebar follows Mihon's own shape:
site pages, then Frequently Asked Questions, then Guides. What is still outstanding is the content: **the text has been debranded and
spot-corrected, not audited against how Reikai actually behaves.** The known-wrong statements found
so far are listed under "Where Reikai already differs" below, and the two that were flatly false
about Reikai (light novels, and Yōkai backup compatibility) are fixed.

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

## What the wiring needed

Five things in the website repo, most of which the build would not have told you about:

- **`sync-docs.mjs` walks the whole tree** and splits by extension: markdown through the existing
  transform into `src/docs/`, everything else copied verbatim into `src/public/docs/`, which is where
  an absolute `/docs/...` asset URL resolves. Its skip list is `README.md`, `dev/` and this file: all
  three are contributor material sitting in the same tree as the user docs.
- **`vitepress-plugin-tabs`.** Seven of these pages are built on `::: tabs`, and without the plugin
  every one of those blocks renders as the literal `:::` text. **The build stays green while it
  happens**, so read the built HTML rather than trusting the exit code.
- **The `.tree` styles**, ported from their `theme/styles/tree.styl` into `style.css` as plain CSS,
  with `--vp-c-brand-darker` swapped for the `--vp-c-brand-1` current VitePress ships. The three
  pages that used it each carried a scoped `@import` of that stylus file, which had to come out.
- **`.only-light` / `.only-dark`.** Screenshots come in pairs with both in the markup and the theme
  hiding one. This also fails silently: with no rule, both render, stacked, and it reads as an
  editing mistake rather than a missing stylesheet.
- **`markdown: { headers: true }`.** VitePress registers its heading extractor only
  `if (options.headers)`, and the option has no default, so every page on the site was built with
  `headers: []` and an empty "On this page" aside. The aside still draws its own title, so it looks
  furnished until you count the links in it.

**Three of those five fail without failing the build.** Read the built HTML in
`src/.vitepress/dist`, or drive the served page, rather than trusting a green build.

## The re-cut is done

Reikai's own pages no longer sit beside near-duplicates of themselves. Three were folded into the
guide that already covered the topic, and their files are gone:

- `categories.md` into `guides/categories.md`, as the three sections Reikai adds.
- `backup-restore.md` into `guides/backups.md`, folded into the existing lists rather than appended,
  since the guide already covered creating and restoring.
- `tracker-sync.md` into `guides/tracking.md`, as two sections on merged entries and the
  library-wide refresh.

Three answers moved out of `FAQ.md` into the FAQ page that owns their topic: a failing download into
`faq/downloads.md`, edited titles versus sorting into `faq/library.md`, auto webtoon mode into
`faq/reader.md`. What is left of `FAQ.md` is about the project rather than the app, so it stays whole
and leads the Frequently Asked Questions group.

The rest of Reikai's docs are guides in their own right with no Mihon counterpart, so they keep their
files and sit in the Guides group: multi-source grouping, library search, related manga, adult
sources, and FlareSolverr under Troubleshooting. `built-in-sources.md` is reference and sits under
Browse.

## What still has to happen

1. **The content pass.** See below. This is the real remaining work.
2. **Two pre-Android-8 sections.** `troubleshooting/index.md` has a WebView table with an "Android 6
   and below" row, and `common-issues.md` addresses "Android 9 and below". Reikai's `minSdk` is 26.

## Where Reikai already differs

Do not assume a file is accurate just because it reads plausibly. Two statements were not merely
stale but flatly wrong about Reikai, and both were found by reading rather than by any check:
`faq/general.md` said Reikai cannot read light novels, and `guides/backups.md` said Yōkai backups do
not restore. Assume there are more.

Known divergences to check while editing: Reikai's library has two view modes, categories carry a
sort override and span both content types, the reader picks webtoon mode automatically, merged
entries fold several sources into one card, and novels exist at all. Nothing in these files knows
about any of that.
