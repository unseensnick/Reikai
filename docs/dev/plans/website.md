# Documentation website

Developer-facing record of the Reikai docs site: why it exists, how it is wired to this repo, and what is deliberately not done yet. Scaffolded 2026-08-21; lives in its own repo at `../Reikai-website`, unpublished.

## Goal

One place a user can learn Reikai, instead of reading Mihon's site for the core reader, this repo for everything Reikai adds, and LNReader's docs for novel plugins.

## Why

The scatter is real but smaller than it first looks, and the shape of it decided the design. **The fork sites document nothing fork-specific.** Komikku's and Tsundoku's sites are the same VitePress template as mihon.app with the same copy; Komikku's getting-started guide covers "Adding sources" and "Adding series to your library" and never mentions merging, enhanced sources or adult sources. Tsundoku's FAQ path 404s. So the features Reikai borrowed from them are documented in exactly one place in the world: this repo.

What is genuinely scattered is three things. Mihon's core docs, which are real and deep. LNReader's plugin documentation. And Reikai's own nine user docs, the only coverage of multi-source grouping, manual merge, category sort order, the novel side and the adult subsystem.

That points at a **content** gap rather than a hosting one: Reikai's docs assume a reader who already knows the lineage. The two `mihon.app` links in onboarding and the storage setting exist for exactly that reason.

## Approach

VitePress, because Mihon's site is VitePress and markdown-first, so the existing docs are already the input format. Started clean rather than forking their repo, with `refs/mihon-website` as the reference clone beside the other refs.

**The docs live in this repo, not the site repo.** `scripts/sync-docs.mjs` reads `docs/*.md` at build time and rewrites two things that only make sense in-repo: the italic dev-records footer each page opens with (dropped, it is contributor material) and repo-relative links (rewritten to absolute GitHub URLs). A copy checked into the site repo would drift the first time a behaviour change updated a doc, which is not hypothetical: `docs/FAQ.md` went stale within hours of the auto-webtoon fix on the day the site was scaffolded.

**Changelogs are generated markdown, not a component.** `scripts/sync-changelogs.mjs` writes `src/changelogs/index.md` from the GitHub releases API. VitePress builds the page outline, heading anchors and the local search index from markdown at build time, so a Vue component's headings would reach none of the three.

## Key files

In `../Reikai-website`:

- `scripts/env.mjs`: resolves configuration so `npm run dev` needs nothing exported. A real environment variable beats `.env`, which beats a derived default; the docs ref derives from the app repo's current branch.
- `scripts/sync-docs.mjs`: pulls the user docs out of this repo. `REIKAI_DOCS_REF` is **inert today**, because every link it would rewrite lives in the dev-records footer the sync strips; it starts mattering the first time a doc links a repo file inline.
- `scripts/sync-changelogs.mjs`: generates the changelogs page. Filters to three-segment versions, so the Yokai-era `1.9.7.5.x` releases stay out.
- `src/.vitepress/shortcodes.ts`: the `<nav to="...">` shortcode. Mechanism and icons from Mihon (MPL-2.0), map is Reikai's own.
- `src/.vitepress/theme/DownloadCards.vue` and `release.data.ts`: the download page, reading both release buckets.

In this repo:

- `docs/guides/`, `docs/faq/`: 23 debranded Mihon guides, staged and unpublished, with their illustrations beside them. `docs/guides/PORTING.md` is the record.
- `docs/img/`: the file-tree icons those guides share, plus Reikai's own 64px icon.
- `docs/built-in-sources.md`: the shorthand table, moved out of the adult sources page; linked by the issue template and the FAQ.

## Status

**Scaffolded and running locally, nothing published.** No GitHub repo, no remote, no domain, no deploy. Home, download, changelogs and all ten user docs build and serve.

**The illustrations are settled.** All 28 assets were ruled on and now live beside their pages in this repo: 21 reader demos reused because Reikai's reader is upstream's unchanged, five app-UI crops re-shot as six because Mihon's were stale against their own app, one Android 7.1 clip dropped against `minSdk` 26, one Android system clip reused. The three double-tap animations went from animated WebP to VP9 WebM, 3.9 MB to 858 KB. `docs/guides/PORTING.md` carries the per-asset record.

**Not done, in the order it matters.** The ported guides are still not wired into the site: `sync-docs.mjs` walks only the top level and copies only `.md`, so it has to recurse and carry the assets into `src/public/docs/`. Eight `/forks/` links point at pages Reikai's site does not have, and the build fails on dead links. The `file`, `file jpg`, `file cbz` and `file-extension` span classes those docs use come from Mihon's stylesheet and need porting or rewriting. The existing nine docs would benefit from a re-cut (see Decisions).

## Decisions & tradeoffs

- **Docs stay in the app repo** (owner, 2026-08-21). The alternative is a copy in the site repo, and the drift argument above is not theoretical.
- **So do their illustrations.** A screenshot is invalidated by the same UI change that invalidates the sentence next to it, so it belongs in the repo where that change lands, referenced by an absolute `/docs/...` path and copied into the site's `public/` at sync time. The app repo already carries 29 MB of README assets, so 6 MB of doc captures is not a new kind of cost.
- **Buy the domain before shipping an in-app link to it** (owner, 2026-08-21). Pages hosting is free either way and supports custom domains, so the money buys only the name. It matters because in-app help URLs ship inside released APKs and live forever: a `github.io` path dies if the repo or account is ever renamed, a domain is a DNS change. Starting on Pages and adding the domain later is safe, since GitHub redirects the old URL.
- **Re-cut the existing docs after merging the ported guides, not before.** They overlap directly: `categories.md` against `guides/categories.md`, `backup-restore.md` against `guides/backups.md`, `tracker-sync.md` against `guides/tracking.md`, and one `FAQ.md` against six FAQ files. Re-cutting first means doing the work twice.
- **Publishing still trades against the public-facing naming rule.** A docs site can stay plain and source-name-free, but it is indexed. `docs/adult-sources.md` names the sources 8 times and cannot avoid it, since the in-app toggle is labelled with one. Whether it publishes is unresolved.
- **No doc-sync ledger for the fork sites.** There is nothing on them to track.

## Gotchas

- **A line starting with `<nav ...>` opens a CommonMark HTML block** and swallows the rest of the paragraph. Write the shortcode mid-sentence, as Mihon does.
- **VitePress's reset sets `svg { display: block }`**, so an inline icon needs `display: inline-block` or it takes its own line.
- **A `.paths.ts` file cannot import a `.data.ts` loader's `data`**: paths run first. Mihon avoids this with plain async functions.
- **The two release channels name their APKs differently** (`reikai-v0.3.1.apk` against `reikai-r1535.apk`), so identify the universal build by the absence of an ABI token rather than by version shape.
