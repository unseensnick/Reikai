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

## What still has to happen before any of it ships

1. **The images.** 74 references to `/docs/...` assets, 61 files and 7.5 MB in their `public/`, as
   light and dark pairs. **They are screenshots of Mihon's app.** Some surfaces are close enough to
   Reikai's to reuse; the library, details and recents screens are not, since Reikai reworked them.
   Each one is a decision: reuse, re-shoot, or drop the illustration.
2. **The `file`, `file jpg`, `file cbz` and `file-extension` span classes** come from their theme
   stylesheet, and need either the CSS or a rewrite.

## Where Reikai already differs

Do not assume a file is accurate just because it reads plausibly. Known divergences to check while
editing: Reikai's library has two view modes, categories carry a sort override and span both content
types, the reader picks webtoon mode automatically, merged entries fold several sources into one card,
and novels exist at all. Nothing in these files knows about any of that.
