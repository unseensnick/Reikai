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
- `scripts/sync-docs.mjs`: pulls the user docs out of this repo, walking the whole `docs/` tree and splitting by extension: markdown through the transform into `src/docs/`, everything else copied into `src/public/docs/`, where an absolute `/docs/...` asset URL resolves. Its skip list is the contributor material living in the same tree: `README.md`, `dev/`, `guides/PORTING.md`. `REIKAI_DOCS_REF` is **inert today**, because every link it would rewrite lives in the dev-records footer the sync strips; it starts mattering the first time a doc links a repo file inline.
- `src/.vitepress/config.mts`: the sidebar, in Mihon's three-group shape, plus `outline: [2, 3]` and `markdown: { headers: true }`, which the outline needs to hold anything.
- `src/.vitepress/theme/style.css`: the ported `.tree` diagram styles and the `.only-light` / `.only-dark` theme pair, both from Mihon's stylesheets (MPL-2.0).
- `scripts/sync-changelogs.mjs`: generates the changelogs page. Filters to three-segment versions, so the Yokai-era `1.9.7.5.x` releases stay out.
- `src/.vitepress/shortcodes.ts`: the `<nav to="...">` shortcode. Mechanism and icons from Mihon (MPL-2.0), map is Reikai's own.
- `src/.vitepress/theme/DownloadCards.vue` and `release.data.ts`: the download page, reading both release buckets.

In this repo:

- `docs/guides/`, `docs/faq/`: 23 guides adapted from Mihon's site, live, with their illustrations beside them. `docs/guides/PORTING.md` is the record and is skipped by the sync.
- `docs/img/`: the file-tree icons those guides share, plus Reikai's own 64px icon.
- `docs/built-in-sources.md`: the shorthand table, moved out of the adult sources page; linked by the issue template and the FAQ.

## Status

**All 30 pages are live locally, on one sidebar in Mihon's shape**: site pages unlabelled at the top, then Frequently Asked Questions, then Guides, with multi-page topics nested and collapsed and the aside outline two levels deep. Reikai's own pages are filed into those two groups by what they are, not by where they came from, so there is one Tracking page rather than a Mihon one and a Reikai one.

**The illustrations are settled.** All 28 assets were ruled on and now live beside their pages in this repo: 21 reader demos reused because Reikai's reader is upstream's unchanged, five app-UI crops re-shot as six because Mihon's were stale against their own app, one Android 7.1 clip dropped against `minSdk` 26, one Android system clip reused. The three double-tap animations went from animated WebP to VP9 WebM, 3.9 MB to 858 KB.

**The re-cut is done.** `categories.md`, `backup-restore.md` and `tracker-sync.md` were folded into the guide that already covered each topic and deleted; three answers moved out of `FAQ.md` into the FAQ page that owns them. `docs/guides/PORTING.md` carries both records.

**Not done, in the order it matters.** The ported text has been debranded and spot-corrected, never audited against how Reikai behaves, and two statements turned out flatly wrong rather than merely stale. Nothing is published yet: no GitHub repo, no remote, no domain, no deploy.

## Decisions & tradeoffs

- **Docs stay in the app repo** (owner, 2026-08-21). The alternative is a copy in the site repo, and the drift argument above is not theoretical.
- **So do their illustrations.** A screenshot is invalidated by the same UI change that invalidates the sentence next to it, so it belongs in the repo where that change lands, referenced by an absolute `/docs/...` path and copied into the site's `public/` at sync time. The app repo already carries 29 MB of README assets, so 6 MB of doc captures is not a new kind of cost.
- **Buy the domain before shipping an in-app link to it** (owner, 2026-08-21). Pages hosting is free either way and supports custom domains, so the money buys only the name. It matters because in-app help URLs ship inside released APKs and live forever: a `github.io` path dies if the repo or account is ever renamed, a domain is a DNS change. Starting on Pages and adding the domain later is safe, since GitHub redirects the old URL.
- **The re-cut happened after the guides were wired in, not before** (done 2026-08-21). Each overlapping pair was merged into the ported guide rather than the other way round, because the guide already covered the common ground and Reikai's page only carried what it adds.
- **Publishing still trades against the public-facing naming rule.** A docs site can stay plain and source-name-free, but it is indexed. `docs/adult-sources.md` names the sources 8 times and cannot avoid it, since the in-app toggle is labelled with one. Whether it publishes is unresolved.
- **No doc-sync ledger for the fork sites.** There is nothing on them to track.

## Gotchas

- **A line starting with `<nav ...>` opens a CommonMark HTML block** and swallows the rest of the paragraph. Write the shortcode mid-sentence, as Mihon does.
- **VitePress's reset sets `svg { display: block }`**, so an inline icon needs `display: inline-block` or it takes its own line.
- **A `.paths.ts` file cannot import a `.data.ts` loader's `data`**: paths run first. Mihon avoids this with plain async functions.
- **The two release channels name their APKs differently** (`reikai-v0.3.1.apk` against `reikai-r1535.apk`), so identify the universal build by the absence of an ABI token rather than by version shape.
- **The build is green for most rendering failures.** `::: tabs` without `vitepress-plugin-tabs` renders as the literal `:::` text; a missing `.only-light` / `.only-dark` rule shows both screenshots of a pair, stacked; and without `markdown: { headers: true }` every page builds with `headers: []` and an empty "On this page" aside that still draws its own title. The dead-link check is the only thing that fails a build, so verify by reading `src/.vitepress/dist` or by driving the served page.
- **`vitepress preview` serves a stale page after a rebuild.** Restart it rather than reloading, or you will chase a fix that already landed.
