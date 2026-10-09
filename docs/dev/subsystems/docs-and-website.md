# Docs and website

## Purpose

How Reikai's documentation is organised and published. User docs live in this repo next to the code that invalidates them, and reikai.app builds from them; developer docs live beside them and never publish. A contributor should be able to tell from the tree where a new doc goes and which docs a change has to touch.

## How it works

### The tiers

- **User docs**, `docs/*.md`, `docs/guides/`, `docs/faq/`, with their illustrations beside them and the shared file-tree icons in `docs/img/`. Each page opens with an italic `_Dev records: ..._` line pointing at the dev docs for its area. `docs/README.md` is the front door and carries the topic map (feature area to user doc and dev records).
- **Dev reference**, `docs/dev/`: `docs/dev/README.md` is its front door, with the "what to update when you finish something" table and who owns which fact. Process records (`upstream-sync.md`, `feature-ports.md`, `off-path-manifest.md`, `parked.md`), `recipes.md` and `testing.md` sit at its top level.
- **Subsystem docs**, `docs/dev/subsystems/`: one page per subsystem describing how it works now, rewritten in place. The template and rules are its [README](README.md).
- **Plans**, `docs/dev/plans/`: in-flight design only. Once the work lands, its lasting content moves into a subsystem doc and the plan is deleted.
- **Process files outside `docs/`**: `CHANGELOG.md` (`[Unreleased]` feeds the nightlies, a version section feeds a release), `ROADMAP.md` (forward backlog only), and the rules in `.claude/rules/`, which bind agents and contributors alike.

Every fact has one owner. The Mihon frontier lives only in the `upstream-sync.md` ledger; the docs site's own sync ledger is `UPSTREAM-SYNC.md` in the website repo; what shipped when lives in `CHANGELOG.md` and the release tags.

### The website

reikai.app is a VitePress site in its own public repo, `unseensnick/Reikai-website` (checked out at `../Reikai-website`), MPL-2.0 to match Mihon's site, which it started from. GitHub Pages serves it.

The site owns no copy of the docs. At build time `scripts/sync-docs.mjs` walks this repo's `docs/` tree: markdown goes through a transform into `src/docs/`, every other file is copied into `src/public/docs/` so an absolute `/docs/...` asset URL resolves. The transform drops the `_Dev records:_` line and rewrites links to repo files the site does not publish (`dev/`, `ROADMAP.md`, `CHANGELOG.md`) into GitHub URLs at `REIKAI_DOCS_REF`, which defaults to the app repo's checked-out branch. It skips `README.md`, `dev/`, `guides/PORTING.md` and `navigation.json` (the app's menu map, which `shortcodes.ts` reads to render `<nav to="...">` chips).

The site is built twice per deploy: the stable docs at the root from this repo's `main`, and the nightly docs under `/preview/` from the commit the latest nightly was built from. Only the stable half carries the download, changelogs, related apps and privacy pages. `scripts/sync-changelogs.mjs` generates the changelogs page as markdown from the GitHub releases API, filtering to three-segment versions so Yokai-era releases stay out.

Three things trigger a deploy of the site's `deploy.yml`:

- `deploy-docs.yml` here sends `docs-updated` after a push to `main` that touches published docs (its path filter mirrors the sync's skip list).
- `site-release.yml` sends `app-release` when a stable release is published, so the download page's version numbers move.
- `nightly.yml` sends `app-release` from its "Ask the site to rebuild" step when a nightly is published.

All three authenticate with `PREVIEW_REPO_TOKEN`, the fine-grained token that also publishes nightlies; `GITHUB_TOKEN` cannot reach another repository. The app's help links all build on `Constants.URL_DOCS`.

### What the lints check

`scripts/lint-docs.sh` holds every docs rule once; the `pre-commit` hook feeds it staged content and `docs-lint.yml` runs it over the tree. It rejects content-source names in `CHANGELOG.md` and `ROADMAP.md`, em dashes and bare `#N` in `ROADMAP.md` and the sync records, a missing bold headline on a new `[Unreleased]` entry, plan codenames and dates in code comments, an unpinned `twin of` marker, a Key files path that does not exist in a plan, and history in a subsystem doc (`subsystem-docs`, on staged pages in the hook and every page in CI: dates, SHAs, steps, rounds, phases, owner rulings, a Status heading, more than 300 lines). CI runs `key-files` over both plans and subsystem docs. `scripts/lint-docs-test.sh` asserts each rule still rejects a real violation.

## Key files

In this repo:

- `docs/README.md`: the user-docs front door and topic map.
- `docs/dev/README.md`: the dev front door, the finish-a-change table, fact ownership.
- `docs/dev/subsystems/README.md`: the subsystem template and rules.
- `docs/navigation.json`: the menu map behind the `<nav>` chips.
- `docs/guides/PORTING.md`: the record of the guides adapted from Mihon's site.
- `scripts/lint-docs.sh`: every docs check; `scripts/lint-docs-test.sh` pins them.
- `.github/workflows/deploy-docs.yml`, `site-release.yml`, `docs-lint.yml`.

In `../Reikai-website`:

- `scripts/sync-docs.mjs`: `SKIP`, `transform`, the dev-records and repo-link rewrites.
- `scripts/env.mjs`: `DOCS_REF`, `SITE_VARIANT`, resolution order (environment, then `.env`, then a derived default).
- `scripts/sync-changelogs.mjs`: the generated changelogs page.
- `src/.vitepress/config.mts`: the sidebar and outline.
- `src/.vitepress/shortcodes.ts`: the `<nav>` shortcode.
- `src/.vitepress/theme/DownloadCards.vue`: the download page, reading both release channels.
- `.github/workflows/deploy.yml`: the two-half build and its triggers.
- `UPSTREAM-SYNC.md`: the site's sync ledger against Mihon's website.

## Invariants and traps

- **A behaviour change updates its user doc in the same commit.** The site rebuilds from the repo, so the doc is the published page.
- **A doc link to a dev file is rewritten to GitHub, not published.** A new kind of repo link the transform does not match renders on the site as a dead relative link.
- **A deploy must start after the push lands.** The site build checks this repo out, so a deploy triggered first publishes the old docs; that is why the trigger is a dispatch from a post-push workflow.
- **The site build passes most rendering failures.** `::: tabs` without its plugin renders as literal text, a missing `.only-light` / `.only-dark` rule stacks both screenshots, and a missing `markdown: { headers: true }` leaves the outline empty. Only dead links fail the build, so check the built page.
- **A line starting with `<nav ...>` opens a CommonMark HTML block** and swallows the paragraph; write the shortcode mid-sentence.
- **Two site pages carry hand-written feature copy**: the landing page's feature cards and the privacy policy. Neither is synced, so a capability change leaves them stale with the build green.
- **The privacy policy describes the build.** Official builds use `-Pdist=github`, which turns telemetry on, and Crashlytics and Analytics default to on; a change to the dist profile or those defaults makes the page wrong.
- **`CNAME` lives in `src/public/`**, pinned to LF, because an Actions deploy replaces the site wholesale and a domain held only in the Pages settings is dropped.

## Decisions

- **Docs and their illustrations stay in the app repo.** A copy in the site repo drifts the first time a behaviour change updates a doc, and a screenshot is invalidated by the same UI change as its sentence.
- **Changelogs are generated markdown, not a Vue component.** VitePress builds the outline, anchors and search index from markdown, so component headings would reach none of them.
- **Subsystem docs describe the present; plans describe work not yet built.** Mixing the two produced plans that read as diaries, where the current behaviour had to be reconstructed from a run of amendments.
- **`adult-sources.md` publishes.** It is a detailed feature doc, which the public-facing naming rule exempts; the landing page and the site repo's description stay generic.
- **The tier is shown by folder, not by filename.** Encoding it in names would have broken about a hundred links for a benefit the folders and front doors already give.

## Extending

- **A new user page**: add it under `docs/`, give it the `_Dev records:_` line, add it to the topic map in `docs/README.md` and to the sidebar in the site's `config.mts`.
- **A new subsystem doc**: follow the template in `docs/dev/subsystems/README.md`, add it to its index, delete the plans it replaces and re-point their links.
- **A new docs rule**: add it to `scripts/lint-docs.sh` and a rejecting case to `scripts/lint-docs-test.sh`, so the hook and CI share it.

## Tests

`bash scripts/lint-docs-test.sh` (every rule rejects a real violation), `bash scripts/lint-docs.sh subsystem-docs`, `bash scripts/lint-docs.sh key-files <file>`. For the site, `npm run build` in `../Reikai-website` and read `src/.vitepress/dist`.

## Related

- Rules: [workflow.md](../../../.claude/rules/workflow.md) (CHANGELOG, public-facing naming), [roadmap-plans.md](../../../.claude/rules/roadmap-plans.md).
- The site's sync ledger: [UPSTREAM-SYNC.md](../../../../Reikai-website/UPSTREAM-SYNC.md).
