# Reikai → Mihon rebase overview

Reikai's foundation was rebuilt on [Mihon](https://github.com/mihonapp/mihon) instead of [Yōkai](https://github.com/null2264/yokai), so the fork can keep its identity (in-place upgrade, light novels, lightweight merge, J2K library feel) while shedding solo maintenance of everything that is not a Reikai-original feature.

This is the narrative overview. Individual features have their own plan docs in this folder; the living status snapshot lives in [ROADMAP.md](../../../ROADMAP.md), and the rules that govern day-to-day work live under [.claude/rules/](../../../.claude/rules/).

## Goal

Move Reikai's foundation off Yōkai and onto Mihon, re-applying only Reikai's distinguishing features on top, so the app upgrades in place for existing users and rides Mihon's actively-maintained upstream for everything that is not Reikai-specific.

## Why

Yōkai descends from TachiyomiJ2K, which is built on the older Android stack: Conductor for navigation and RxJava-backed presenters for screen logic. Reikai was migrating that stack to Compose (Google's modern UI toolkit) plus Voyager (a Compose navigation library) by hand, screen by screen. That meant doing work Mihon finished years ago, and solo-maintaining the whole stack including the legacy half that still shadowed every migrated screen.

Mihon already ships Compose plus Voyager throughout, and an active community carries its maintenance. Reikai's differentiators are almost all additive parallel verticals: light novels are disjoint from manga by design, the multi-source merge is one preference-backed helper, and category sort order is one preference. That is the smallest possible patch surface for riding a fast-moving upstream, so basing on Mihon means most of the app stays vanilla (and therefore free to maintain).

Mihon was chosen over Komikku (another Tachiyomi-lineage fork) because Komikku already ships a database-based merge that would conflict with Reikai's lighter preference-based merge at the library-collapse and chapter-aggregation seams, and it carries a far larger patch surface to track. Mihon has no merge feature, so Reikai's lands cleanly.

## Approach

Take Mihon as-is, keep Reikai's name and package identity so the update is seamless, and add Reikai's own features either in their own files (preferred) or as small, clearly-marked edits to Mihon's files. The work was structured as ordered phases so each built on a stable base.

**Phases (P0 to P9).** The rebase ran as a sequence where early phases unblocked later ones:

- **P0** forked Mihon and restored Reikai identity (see below); it blocked everything.
- **P1** re-applied the one extension-facing change (the related-manga contract on the source API) and stood up shared Compose infrastructure.
- **P2** carried Reikai's library screen onto Mihon: single-list collapsible categories, the hopper, dynamic grouping, filter/sort, category sort order, and the update-errors screen.
- **P3** carried the manga-details screen: the merge / Manage-sources UI, private tracking, two-finger range select, the cover-accent backdrop, and the recommendations carousel.
- **P4** wired the preference-based merge engine, preferred-source ranking, and tracker-link mirroring across the carried library and details.
- **P5** built the light-novel vertical (the long pole): novel domain models and database tables, a QuickJS-based plugin host, browse, details, reader, downloads, library integration, background updates, cross-source merge, and the backup proto.
- **P6** finished recommendations: the engine, taste profile, tracker recommendations, and the full-screen "See all" browse grid.
- **P7** applied reader tweaks to Mihon's still-View-based reader.
- **P8** was dropped (Mihon already covers it; see Decisions).
- **P9** added the genuinely-new Compose builds: category bulk-delete and the unified Recents (folded into the Updates and History tabs).

The library and manga-details screens are the two deliberately "owned" screens: Reikai replaces Mihon's versions with its own, re-typed onto Mihon's models. Everything else stays vanilla Mihon. Owning these two is the price of the J2K library identity, and the cost is re-applying upstream changes to them on each sync.

**The `// RK` patch-island convention.** Edits inside Mihon's own files (the Browse / Library tab wiring, backup proto fields, dependency-injection registration, the build identity) are fenced with `// RK -->` and `// RK <--` comment markers. This makes every Reikai change greppable (run `grep "// RK"` to find them all) and survivable when porting upstream changes. It mirrors how Komikku marks its `// SY` / `// KMK` patches. The rule is: anything that can live in its own file should, and only the unavoidable in-place edits get fenced.

**Net-new code in `reikai.*`.** All net-new Reikai code (its own ScreenModels, SQLDelight tables, Voyager screens, domain models, interactors) lives under the `reikai` package root (`app/src/main/java/reikai/`), kept out of Mihon's `eu.kanade.*` / `tachiyomi.*` / `mihon.*` trees so it never collides with an upstream file. Note: minified release builds need `reikai.**` in the ProGuard keep list, since R8 otherwise breaks Injekt's type reflection for novel types.

**Identity preservation.** Existing installs upgrade in place because the application id and app name are unchanged from the Yōkai era. In `app/build.gradle.kts` (fenced `// RK`): `applicationId = "eu.kanade.tachiyomi"` with the release suffix `.y2k` and debug suffix `.debugY2k`. Mihon's own id is `app.mihon`, but the `eu.kanade.tachiyomi` namespace is shared by both so source classes resolve either way. The app name `Reikai` lives in the i18n module (`i18n/src/commonMain/moko-resources/base/strings.xml`). The carried-over `versionCode 169` / `versionName 1.9.7.5.10` keep upgrade continuity from the last Yōkai-based build.

**Porting method.** Upstream Mihon changes are ported by hand from the local `refs/mihon/` clone. A file Reikai has not touched is copied verbatim; a file Reikai has patched is hand-merged inside its `// RK` island. Reikai's own remaining features are ported from the `design/library-compose` branch (the old Yōkai-based fork), re-typed onto Mihon's immutable domain models.

## Key files

- [CLAUDE.md](../../../CLAUDE.md): the project's top-level guide: architecture in brief, screen conventions, code-change defaults, and where everything lives.
- [.claude/rules/](../../../.claude/rules/): the working rules: [architecture.md](../../../.claude/rules/architecture.md) (Compose + Voyager, Injekt DI, preferences, coroutines, modules), [screen-conventions.md](../../../.claude/rules/screen-conventions.md) (the screen conventions a ported screen must follow), [workflow.md](../../../.claude/rules/workflow.md) (changelog, commits, upstream-sync method), plus [code-quality.md](../../../.claude/rules/code-quality.md), [testing.md](../../../.claude/rules/testing.md), [database.md](../../../.claude/rules/database.md), and [security.md](../../../.claude/rules/security.md).
- [app/build.gradle.kts](../../../app/build.gradle.kts): the identity patch (the `// RK`-fenced `applicationId`, `.y2k` / `.debugY2k` suffixes, version fields) and the signing config.
- `app/src/main/java/reikai/`: the net-new Reikai code tree (novel vertical, merge helpers, recommendations, library extensions).
- `i18n/src/commonMain/moko-resources/base/strings.xml`: the `Reikai` app name string and fork-only strings.
- [docs/dev/development.md](../development.md): architecture and module overview.
- [ROADMAP.md](../../../ROADMAP.md): the forward-looking plan and the per-phase status table.

## Status

The core rebase is complete and on-device verified. Snapshot (see [ROADMAP.md](../../../ROADMAP.md) for the live detail):

| Phase | What | Status |
|---|---|---|
| P0–P1 | Mihon base + Reikai identity + source-api related-manga contract | Shipped |
| P2 | Library screen carry (single-list + hopper, dynamic grouping, filter/sort, category sort order, update-errors screen) | Shipped |
| P3 | Manga-details carry (merge / Manage-sources UI, private tracking, range select, cover-accent backdrop, recommendations carousel) | Shipped |
| P4 | Preference-based merge engine + preferred-source ranking + tracker-link mirroring | Shipped |
| P5 | Light-novel vertical (domain/DB, QuickJS host, browse, details, reader, downloads, library, background updates, merge, grouping, unified Updates) | Shipped (incl. round-2 parity) |
| P6 | Recommendations (engine, taste profile, tracker recs, See-all browse) | Shipped |
| P7 | Reader tweaks (configurable bottom bar, chapters sheet, cover tint, mark-read-on-skip, resume/preload) | Shipped |
| P8 | Settings / shell carry | Dropped (Mihon already covers it) |
| P9 | Category bulk-delete + unified Recents | Shipped (Recents folded into Updates + History) |

The round-2 backlog (the manga/novel feature-parity sweep, for example novel batch migration, the download-queue reorder, the home-screen widget) and the revived adult-source subsystem have since shipped. The signed release pipeline (AGP-native signing plus the release and preview GitHub Actions workflows, with the in-app updater pointed at Reikai's own release repos) is built and CI-validated. Remaining low-value polish and parked items are tracked in [ROADMAP.md](../../../ROADMAP.md).

## Decisions & tradeoffs

- **Manual port.** Upstream Mihon changes are ported by hand from `refs/mihon/`. The cost is manual work on each sync; the benefit is the patch set stays intact and reviewable. Sync commits cite the upstream PR as `mihonapp/mihon#N` (a bare `#N` would link to a Reikai issue).
- **Re-typing to immutable models is the biggest mechanical cost.** Yōkai-era code used mutable models (`var` fields, `Int` flags, nullable ids). Mihon's domain models are immutable (`val` fields, `Long` flags, non-null ids, `@Immutable`). Every ported ScreenModel, filter, grouping, and sectioner had to be re-typed against Mihon's models and interactors. This re-typing was the single largest effort of the rebase, but it is what lets ported code blend into the Mihon base rather than dragging a parallel model layer along.
- **Keep the preference-based merge, do not adopt Komikku's database merge.** Reikai's merge stores merge groups in preferences rather than fake parent rows in the database: no schema, trivially reversible unmerge, near-zero coupling to the reader and downloader. Groups are stored as local id sets in preferences. Because ids are reassigned on restore, both the manga and novel backup paths serialize each group as stable `{url, source}` refs and rebuild against the restored ids (see [novel-backup.md](novel-backup.md)).
- **Two owned screens, not zero, not many.** Owning the library and manga-details screens diverges from "vanilla Mihon everywhere" because Mihon structurally lacks the J2K single-list / dynamic-grouping library (its library is bound to `Manga` with no content-type abstraction and uses paged tabs) and the details screen carries the merge UI, cover theming, and the shared content composable the novel vertical reuses. The tradeoff is re-applying upstream changes to these two screens on each sync.
- **No Koin; use Mihon's Injekt.** The Yōkai base mixed Koin and Injekt. Ported code registers through Mihon's Injekt instead, to avoid widening the patch surface against upstream.
- **Dropped phases.** P8 (settings / shell carry) was dropped after a gap-check found Mihon already covers it: FlareSolverr shipped earlier in P3, Mihon's theme set already covers nearly all of Yōkai's, and the rail-vs-bottom nav switch is automatic. A handful of features were ruled out of scope entirely: drag-to-reorder manual sort (never built anywhere, would be net-new), the stats drill-down, and one duplicate feature. Dedicated light-novel trackers were researched and found non-viable as on-device sync targets as of mid-2026.
