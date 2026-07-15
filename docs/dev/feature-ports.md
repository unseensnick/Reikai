# Feature ports (Komikku, Tsundoku, LNReader)

Reikai's **base** is Mihon. That relationship is a linear "synced through SHA X" frontier with its own process and ledger: [upstream-sync.md](upstream-sync.md).

The refs in this doc are a different relationship. Reikai borrows **specific features** from them, so there is no frontier to track and never will be. What matters instead is, per feature: what was taken, from which commit, when it was last checked against that ref, and the verdict. This doc is that record.

## Why this is not shaped like the Mihon ledger

- **No frontier.** Reikai is not "synced through" any Komikku or Tsundoku commit. Most of their commits are irrelevant to Reikai by construction, so a single base SHA would answer the wrong question. The useful question is "is our EXH port stale?", which is per-feature.
- **Their own upstream syncs are traps.** Komikku and Tsundoku are themselves Mihon forks, so a large share of their commits are their own Mihon syncs. Reikai takes those from `refs/mihon` directly. Porting one via a fork means re-targeting it twice, through their divergence and then ours.
- **Divergence runs both ways.** In several places Reikai is *ahead* of the ref. Those are recorded below so a later pass does not "sync" backwards into older code.
- **Compare implementation, not surface.** A matching commit title proves nothing. Verify against current Reikai code before porting; several past candidates turned out to be the ref catching up to Reikai.

## How to run a pass

1. Pull the ref clone. The clones live in the sibling `refs/` dir, not inside `app/`.
2. `git -C refs/<name> log --oneline <last-checked>..HEAD`.
3. Drop the noise: the ref's own Mihon syncs (they cite `mihonapp/mihon#...`), Weblate / i18n, dependency bumps.
4. For each remaining commit touching a feature listed below, verify against current Reikai code and record a verdict: **PORT** / **ALREADY-FIXED** / **N/A** (with the reason).
5. Bump "last checked" only for the features actually examined, and add a row to that ref's pass log.

There is no natural trigger for a pass the way a Mihon sync has one, so "last checked" is deliberately per-feature and only moves when someone really looks.

**Credit:** a port is credited in the commit body, and for user-facing features in the README "Adapted from ..." section. Mihon is the base and is not credited that way.

## Komikku

[Komikku](https://github.com/komikku-app/komikku), a healthy Mihon fork in the SY lineage. Reikai's largest borrow source. Ref: `refs/komikku` (branch `master`).

### Ported features

| Feature | Reikai home | Last checked | Notes |
|---|---|---|---|
| Adult / EXH subsystem | `exh/`, [adult-sources.md](../adult-sources.md), [exh-subsystem.md](plans/exh-subsystem.md) | 2026-07-15 @ `af7b919e90` | Ported wholesale, re-typed onto Mihon's models. Shipped 0.1.6. The EH tag data (`exh/eh/tags/`) is a **verbatim** vendored copy and was byte-identical to Komikku's at the 2026-07-15 pass, so refresh it wholesale rather than hand-merging. |
| Adult browse parity | `source/online/all/EHentai.kt` (`genericMangaParse`), `SourcePagingSource`, `MetadataMangasPage`, [adult-browse-parity.md](plans/adult-browse-parity.md) | 2026-07-15 @ `af7b919e90` | Shipped 0.1.6. Upstream's only changes to these paths ride Komikku's TachiyomiX 1.6 adaptation, which Reikai does not follow; see "Deliberately not taken". |
| MangaDex enhanced source + MDList tracker | `exh/md/`, [md-enhanced-source.md](plans/md-enhanced-source.md) | 2026-07-15 @ `af7b919e90` | Shipped 0.2.0. Reikai is ahead here, see below. |
| Related-mangas carousel | `HttpSource` `// RK` island, `reikai/domain/recommendation/`, [related-mangas.md](../related-mangas.md) | 2026-07-15 @ `af7b919e90` | Reikai's taste layer sits above Komikku's baseline. |
| Edit-info dialog | `reikai/presentation/details/EntryEditInfoDialog.kt` | 2026-07-15 @ `af7b919e90` | Ported from Komikku's manga-only dialog; Reikai unifies it across manga + novels and stores edits non-destructively. The one sanctioned native-XML surface. Untouched upstream since the port. |
| Library tag search | `exh/search/`, [library-tag-search.md](plans/library-tag-search.md) | 2026-07-15 @ `af7b919e90` | A trimmed port of Komikku's `exh/search` package (parser only; their SQL-emitting browse path was not taken). Untouched upstream since the port. Reikai is ahead here, see below. |
| Extension-installer ANR + queue fixes | `extension/installer/Installer.kt`, `extension/util/ExtensionInstallService.kt` (`5f3ec4515`) | 2026-07-15 @ `af7b919e90` | Shipped 0.2.1. Both files untouched upstream since the port. Komikku's later extension work is its extension-store / TachiyomiX 1.6 direction, not these fixes. |
| AniList GraphQL error parsing | `ccaa2767b` | 2026-07-15 @ `af7b919e90` | Shipped 0.2.1. Komikku's later `35db2840c8` is the same fix; already held. |

### Where Reikai is ahead (do NOT port backwards)

- **MangaDex delegate headers / auth.** Reikai threads the delegate `headers` through every `MangaDexService` request and refreshes on the delegate client, so the extension's User-Agent survives; Komikku only added this in `94ea603c75` / `2155baf60c` and **still** drops the UA on logout. Reikai's browser UA makes the MangaDex API 400, which is why this matters.
- **MangaDex tracker covers.** Reikai routes them through a scoped Coil fetcher (`MangaDexTrackCoverFetcher`); Komikku's `473e9c6d69` is a self-described "dirty hack" global OkHttp interceptor, and `bc90201cc2` then exists only to gate that hack.
- **Delegated metadata parsing.** Reikai's delegated sources override `getMangaUpdate` (the "getMangaUpdate collapse"), so Komikku's `3921bbf425` bug (subclass `fetchMangaDetails` overrides being bypassed) cannot occur here.
- **Chapter order into `getMangaUpdate`** and the **redundant details-fetch guard**: Reikai already had both when Komikku fixed them (`8659bed4df`, `26dbe3c0ad`).
- **Library tag search.** Wildcards actually work in Reikai (the parser emits a regex); Komikku's library matcher hands its SQL-`LIKE` pattern to an in-memory comparison, so wildcards are inert there. Reikai's `flushAll` also resets the exclude/exact flags between components, fixing a latent carry-over in Komikku's parser. See [library-tag-search.md](plans/library-tag-search.md).

### Deliberately not taken

- **Komikku's own direction:** the extension-store / TachiyomiX 1.6 migration, XLog logging, its i18n rebrand. Reikai follows Mihon's extension model. Note this pulls otherwise-appealing commits with it, and they are traps: `3b409096d4` makes `MangasPage` a regular class (deprecating `component1`/`component2`/`copy`) purely to suit TachiyomiX 1.6, and `1bfd8e0876` ("cleanup unused code and redundant method overrides") deletes `getMangaUpdate` and the parse stubs from `EHentai.kt` **because their new base makes them redundant**. Reikai's base does not: `getMangaUpdate` is our primary override point (the "getMangaUpdate collapse"), so taking that cleanup would break the delegated path.
- **`948e41bd54` (`manualFetch` in `MetadataUpdateJob`).** Reikai's file is byte-identical to Mihon's, and Mihon omits `manualFetch` too. This is Komikku diverging from Mihon, not drift in a Reikai port; taking it would open a permanent `// RK` island in a shared file for a niche cover-refresh case. If the behavior is wanted, upstream it to Mihon.
- **`5d6ea9fbb8` (merged-chapter SQL sort).** Reikai has no `merged` table; merging is pref-based by design and the same symptom is already handled in `MergedChapterProvider`.
- **`abc3cbbea1` (`customMangaInfo` `@Transient`).** Reikai's custom-info is an overlay applied at the ScreenModel layer, not a field on the `Manga` model, so the serialization hazard does not exist here.
- Similar-manga carousel, external-aggregator handlers, cover-quality niceties: dropped with the MD enhanced source, see its plan doc.

### Pass log

| Date | Ref HEAD | Range | Result |
|---|---|---|---|
| 2026-07-15 | `af7b919e90` | `3615dc23a9..af7b919e90`, the last two stale entries (adult browse parity, extension-installer) | **0 ported; every Komikku entry is now current.** Extension-installer: the two ported files are untouched upstream. Adult browse parity: 3 commits touch its paths, all riding the TachiyomiX 1.6 adaptation, and one of them (`1bfd8e0876`) is a trap worth knowing, see "Deliberately not taken". Reading the diffs mattered: by title it looks like a harmless dead-code cleanup. |
| 2026-07-15 | `af7b919e90` | `3615dc23a9..af7b919e90`, the three entries left stale by the pass below (EXH, tag search, edit-info) | **1 ported**: the EH tag-data refresh (`2011491510` -> `f84b44a31`), a verbatim copy plus two new tag files and a new `location` namespace. **Edit-info and tag search: zero upstream commits in range, verified unchanged.** EXH had 12 commits touching `exh/`, but 11 were already assessed (the MangaDex cluster, `manualFetch`) or Komikku's own direction (XLog logging, the TachiyomiX 1.6 adaptation), leaving only the tag data. Note for next time: tag search lives at `exh/search/`, so a `*tag*` path filter misses it. |
| 2026-07-15 | `af7b919e90` | `3615dc23a9..af7b919e90` (69 commits) | **1 ported**: the related-mangas capability gate + response-close (`eeacb0a7de` -> `810f774da`). Of the 69: 24 were Komikku's own Mihon syncs (taken from `refs/mihon` instead), ~12 Weblate / bumps / EHTags data, 33 Komikku-original. Of those, the whole MangaDex delegated cluster turned out to be Komikku catching up to Reikai; two were already fixed here; the rest N/A or Komikku's own direction. One skipped by policy (`948e41bd54`, see above). |
| 2026-07-04 | `3615dc23a9` | Parity audit (details / browse / adult / MangaDex) | Audit only, no ports. Action items promoted to ROADMAP; full record in `docs/dev/audits/` (local). |

## Tsundoku

[Tsundoku](https://github.com/Cody-Duong/tsundoku), an Apache-2.0 Mihon fork built for novels. The reference for novel-reader features and the future native-reader migration. Ref: `refs/tsundoku` (branch `main`).

### Ported features

| Feature | Reikai home | Last checked | Notes |
|---|---|---|---|
| Chapter release-date parsing | `reikai/data/novel/NovelDateParser.kt` (`054f5f8f3`) | 2026-07-15 @ `11a6ffce3` | From tsundoku's `JsSource.parseReleaseTime` (ISO / "3 days ago" / date-format fallbacks). LN chapters previously stored no date at all. |
| Plugin text sanitizing | `reikai/novel/host/NovelTextSanitizer.kt` (`eeda00bee`) | 2026-07-15 @ `11a6ffce3` | From tsundoku's `JsSource` `stripInvalidChars` + entity decoding. Applied to metadata + chapter names; chapter bodies get control-char stripping only, so HTML survives. |
| Next-page probing | `reikai/presentation/novel/browse/NovelBrowseScreenModel.kt` (`c565c5413`) | 2026-07-15 @ `11a6ffce3` | From tsundoku's `inferHasNextPage`: full page assumes more, short page eager-probes the next and caches it for the matching load-more. |
| Download-queue card gutter | `reikai/presentation/download/EntryDownloadCardList.kt` (`7e7bd0a5e`) | 2026-07-12 | Tsundoku's 16.dp card gutter, adopted during the queue redesign. Layout detail only. |

**Informed by, but implemented independently:** the LN-plugin host runtime work (`226dd7d1b`, `3875e70f3`, `e04d4e5fe`). The *gap list* came from diffing tsundoku's JS runtime against Reikai's, but each fix is Reikai-native: real `setTimeout` delays via a `__lnDelay` async binding (30s cap), `Buffer` / `Blob` / `Response.arrayBuffer()` / fuller headers, an `X-XSRF-TOKEN` header, and a truncated-plugin-cache heal.

### Where Reikai is ahead (do NOT port backwards)

- **The LN-plugin host.** Reikai vendors real cheerio / htmlparser2 / dayjs / protobuf / noble-ciphers, plus an `Intl` stub, typed storage with expiry, a restore trust-gate, and CF/Flaresolverr-aware timeouts. Tsundoku's runtime is thinner.
- **Paged-chapter aggregation.** Reikai already walks paged chapter lists (`NovelPageWalk`); this was on the port list until the comparison showed it was already done.
- Tsundoku's "truncated-download heal" only re-downloads truncated **plugin source files**, not HTTP bodies. Reikai's equivalent (`3875e70f3`) covers the same case; neither side heals truncated response bodies.

### Deliberately not taken

- **Novels-as-manga entry merge. Ruled out.** Tsundoku gets its unified-everything simplicity by making novels *be* manga rows (hashing the `String` plugin id to a `Long`). Reikai deliberately keeps `novels.source TEXT` in separate tables; merging the entry tables would mean source-id collisions, broken novel FKs, and forking `mangas.source` from Mihon. No item on the roadmap requires it.
- **Native novel reader (Option 3).** Evaluated, deferred to its own branch. It ports via **adapters**, not a storage merge: lift tsundoku's `NovelViewer` text engine onto Reikai's novel domain through `ReaderChapter` / `Page` / `PageLoader`. Do not transplant `NovelViewer.kt` verbatim; it is fused to Mihon's `ReaderActivity`. See [novel-reader-tsundoku.md](plans/novel-reader-tsundoku.md).
- **Content-type binary fetch.** Auto-detecting binary responses by Content-Type would garble a mislabeled non-UTF-8 (GBK / Shift-JIS) text source, and no novel plugin fetches raw binary. See ROADMAP "Parked".
- **LNReader filter -> `FilterList` conversion.** Reikai renders the plugin's raw filter schema directly and that works; the real defect was a member-name mismatch in Reikai's own `filterInputs` shim, fixed in `226dd7d1b`. Adopting tsundoku's model would be a rewrite for no added coverage.
- **On-device translation stack.** Parked; see ROADMAP.

### Pass log

| Date | Ref HEAD | Range | Result |
|---|---|---|---|
| 2026-07-15 | `11a6ffce3` | Scout of `JsSource` + the JS runtime vs Reikai's LN host | **3 ported** (dates, text sanitizing, next-page probing) plus a tsundoku-informed host-runtime batch. The scout reversed the assumed priority: the runtime gaps that looked urgent (`arrayBuffer` / `Buffer` / `Blob`) had ~zero real plugin demand, while honoring `setTimeout` delays was the actual cause of plugin failures. |
| 2026-07-14 | (scout) | Port-candidate survey | Candidates promoted to ROADMAP by area; translation stack parked. |

## LNReader

[LNReader](https://github.com/LNReader/lnreader) is the origin of Reikai's novel-reader engine and the LN plugin format. Refs: `refs/lnreader-main`, `refs/lnreader-2.0.3-Pre-release`, `refs/lnreader-plugins`.

Two distinct relationships, neither shaped like the two above:

- **The vendored reader engine.** `app/src/main/assets/lnreader-web/js/core.js` is a vendored copy of LNReader's reader core, driven by Reikai's WebView novel reader. It is vendored, not synced: it is replaced wholesale if ever updated, and the tsundoku native-reader track would retire it entirely. Treat a change here as a deliberate re-vendor, not a port.
- **The plugin format + `@libs` semantics.** Reikai's QuickJS host (`app/src/main/assets/lnhost/headless.js`) implements LNReader's plugin contract, and cites `refs/lnreader-main` inline where it mirrors upstream helper behavior (`fetch` defaults, the storage envelope, filter types). Those citations are the record; the host is Reikai-owned code, not a port to re-sync.

**The plugin ecosystem is tracked elsewhere.** Reikai's plugins live in the [`reikai-lnreader-plugins`](https://github.com/unseensnick/reikai-lnreader-plugins) fork, which has its own `docs/upstream-sync.md`, its own upstream-frozen marker, and its own health-sweep tooling. Do not duplicate that record here; it would drift. Plugin-side work belongs in that repo.
