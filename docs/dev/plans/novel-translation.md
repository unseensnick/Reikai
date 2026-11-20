# Novel translation

## Goal

Read a novel in your own language: chapters, chapter titles, and the titles and descriptions in browse and on the details page, translated on the device for free with Google's ML Kit, or through an engine of your choice. A translated chapter can be read aloud, exported as an EPUB (translated, original, or both), and prepared while it downloads.

## Why

Requested in unseensnick/Reikai#107. Tsundoku ships translation with twelve engines, but its two code paths follow different rules (one flattens markup and drops image placeholders, the other sends a whole chapter unchunked), its cache key ignores the engine and the source language, and its engine list has a selectable entry that can never be picked. The design below keeps its feature set and removes those failure classes by construction: every engine, every surface and the export see text through one kernel and one cache.

## Approach

### What the spikes measured

On a Galaxy Z Fold6 (Android 16) and an emulator, through instrumented spikes in the `spike/translation` worktree (`MlKitTranslateSpike`):

- **Download and size.** Korean, Japanese, Chinese and Indonesian models download in 3 to 5 s each and take about 40 MB apiece (200 MB with English). The library adds `libtranslate_jni.so` (16.4 MB, 7.2 MB compressed) and `liblanguage_id_l2c_jni.so` (1.0 MB) per ABI; native libraries are extracted at install (`useLegacyPackaging`), so arm64 grows by about 7.7 MB to download and 17 MB installed.
- **Speed.** An 8-paragraph Korean chapter takes 0.94 s paragraph by paragraph and 0.54 s with the paragraphs in flight together. 4,000 characters in one call returned complete in 1.2 s, linear in length.
- **No Play services needed.** With `com.google.android.gms` disabled, language identification, model download and translation all ran.
- **Plain text only.** Paragraphs joined by newlines came back as one line, every time. Inline markers (`<b>`, brackets, double square brackets, hash-wrapped numbers) came back mangled, and one wrapped word was dropped.
- **Names drift.** Plain translation spelled one Korean name three ways and one Japanese name two ways across eight sentences. Swapping the chosen spelling into the source first came back intact 21 of 24 times, and the 3 misses differed only in letter case.
- **Language identification.** Confidence 1.00 for Korean, Japanese, Chinese and English chapter text, 0.63 for Indonesian (still right); undetermined for a two-letter string.
- **Lifecycle.** A closed `Translator` throws `IllegalStateException`; another client for the same pair keeps working.

### The kernel: one way text reaches an engine

`TranslationSegments` cuts a chapter into the prose an engine translates and rebuilds it from the answers. A stretch of inline text between block edges, breaks, pictures, rules and blank lines is one unit; a stretch over 1,000 characters is cut into sentence groups by `TextSegments.sentences`, and the groups are joined back with a space, or with nothing for an unspaced target language (Japanese, Chinese, Thai, Lao, Khmer, Burmese). Formatting inside a stretch is flattened, since no engine can be trusted with a marker; a single wrapper around the whole stretch is kept. Letterless marks (a footnote's `<sup>1</sup>`, an empty anchor) never reach the engine and are put back after the text. Ruby readings are never sent and leave with their base text. `pre`, `code`, `style`, `script`, `textarea` and `noscript` are never sent; neither is text without letters. Units are deduplicated, and a stretch with any piece unanswered keeps its original whole.

Each rebuilt stretch is wrapped in `<span data-rk-src="N">`, N indexing the originals the chapter result carries, so either renderer can show a paragraph's original. Auto-split inserts its breaks inside the span and both sanitizers keep the attribute (`SourceMarkSurvivesPipelineSpike`).

**A plain-text chapter is translated as HTML.** Plain text skips the sanitizer and is escaped whole by `plainTextToHtml` after the pipeline, which would print a mark as text, and its auto-split starts a new paragraph inside a mark. So when a `.txt` chapter is translated, `prepare` ends by turning it into its escaped HTML form (one `<p>` per blank-line paragraph, hard wraps joined) and `finish` treats it as HTML. One kernel path serves both kinds; the visible cost is that auto-split's breaks in a translated `.txt` chapter are line breaks rather than new paragraphs.

Engines receive only `List<String>`, so no engine handles markup and no engine has a rule of its own about what to send.

### The seam

`NovelContentPipeline.process` splits into `prepare` (title strip, normalize, extra spacing, regex rules) and `finish` (auto-split, lowercase, bare paragraphs, sanitize), with `process` kept as `finish(prepare())`. `NovelChapterTextLoader.load` runs `prepare`, translates, then `finish`, so a user's rules match the source language and auto-split counts the translation's words. Both renderers and read aloud take the result unchanged. Raw-HTML mode skips translation.

`load` returns a result with a translation status instead of a pair: `Off`, `Translated(engine, from)`, or `Original(reason)` (engine failed, model downloading, language unknown). The reader caches `Off` and `Translated` as today; `Original` is shown with its reason and a Retry, and is not cached as final, so a failure cannot stick until a setting changes. `cacheIfCurrent` answers stored, stale or not cacheable, and `warmNeighbour`'s retry loop runs again only on stale, so an `Original` neighbour is warmed once rather than in a loop. A finished model download reloads every chapter in the window that showed `Original(downloading)`, through the same generation bump a settings change uses.

### Engines

`TranslationEngine.translate(texts, from, to): List<String>`, one call per batch, with one shared HTTP layer: one status-to-error mapping (`TranslationError`: key rejected, quota, rate limited, unreachable, bad answer), one retry rule (429 and 5xx, backoff, at most two), and one request-size batcher. Shipped engines:

- **ML Kit**, standard builds only. One `Translator` per language pair held for the session and closed with it; calls in flight together per chapter; the model is checked with `RemoteModelManager.getDownloadedModels` before use, so a missing model returns `Original(downloading)` and the chapter reloads when the download ends, never a load that waits on Wi-Fi.
- **OpenAI-compatible chat**: base URL, key and model, covering OpenAI, DeepSeek, NVIDIA NIM, Ollama, OpenRouter and Gemini (`v1beta/openai/`). Units go out as a numbered JSON array with a versioned prompt and must come back as an array of the same length, or the batch is retried one unit at a time; message content is read as a string or as an array of text parts, since Gemini returns either.
- **DeepL**, **Google Cloud Translation**, **LibreTranslate** (any instance, key optional). Each is asked for plain text (`format=text` for Google Cloud, whose default is HTML), so no answer arrives entity-escaped and is escaped again by the kernel; each engine's request and response are pinned by fixtures.

Not taken from Tsundoku: the Google web scraper (against Google's terms and broken by any page change), HuggingFace, SYSTRAN (shares HuggingFace's id there, never selectable), and Custom HTTP (the OpenAI-compatible and LibreTranslate engines cover its shapes).

### Glossary

Per entry, manga or novel (keyed by `EntryId`, since details and chapter titles translate for both), a list of source term to chosen spelling. Before translating, terms are replaced longest first, two characters at least, at word boundaries in a spaced script; after, a case variant of a spelling is restored to it exactly, only in a unit where a swap happened and no more times than it swapped, so an ordinary "will" or "rose" elsewhere is never rewritten. The same mechanism serves every engine. A merged series reads and edits the list of its library lead, and a split copies it to every member, so no glossary is lost or doubled. It lives in its own table, `entry_translation_glossary`, and travels in backups as a field on both entry types (a `// RK` island in each backup model). It is part of what the cache stores under, since it changes the text sent.

### Cache

A SQLDelight table `translation_segment` keyed by engine key (engine, model, prompt version), kernel version, source and target language, and the SHA-256 of the unit as sent, holding the translation and a last-used time. One lookup per chapter with an `IN` list (the bundled SQLite takes 32,766 variables), one `INSERT OR REPLACE` transaction to store, so the reader, a download and an export writing one unit never conflict, and last-used updated once per chapter. A running total of stored bytes is kept beside it, and eviction of the least recently used runs past the cap, never a scan per insert. Cleared from settings, never in backups. Not on `ChapterCache`'s disk cache, which `NovelChapterTextCache` uses for whole chapters: units are looked up by the hundred across five surfaces, which is a query, not a file per key. A change of engine, model, prompt, language or glossary misses by itself; a failed chapter resumes from what it already stored. The reader, downloads, export, browse and details all share it.

### Language

From the entry: a local novel's own language, else `NovelSource.lang` through `toLangCode` (the rule `entryLanguage` already applies for export), and for manga `Source.lang`, normalised to the engines' codes by one mapper (`zh-Hant` and the like). When the source says `all`, ML Kit's language identification reads the chapter on standard builds; FOSS builds leave it to the engine (DeepL, Google Cloud and LibreTranslate detect, an LLM is told to). A source already in the target language is not translated, per chapter, which covers a novel in your language with no setting.

### Settings and the per-novel switch

Global, in Settings, Reader, novels: translate novels not in your language (off by default), engine and its settings (keys as `Preference.privateKey`, so a backup carries them only when Private settings is ticked, as with tracker logins), target language (device language by default), translate titles and descriptions in browse and details, pre-translate on download, and for ML Kit a model manager (downloaded languages, sizes, delete), Wi-Fi-only downloads, and the note that ML Kit sends usage data to Google. Clear translations sits beside them.

Per novel: follow the global switch, on, or off, in `viewerFlags` bits 0xC00. Written through the reader's `GroupViewerFlags` writer, as orientation and rendering mode are, so a merged series shares it as it is set, with the mask added to `GatherGroupViewerFlags` so members a restore left disagreeing are reconciled; reloading through the route a rendering-mode change takes. With both at their defaults nothing is translated until asked.

### In the reader

- A line at the top of a translated chapter: the engine and the source language, with Show original, which flips the novel's switch. The reader sheet's toggle writes the same switch, so there is one state.
- Long-press a paragraph for its original in a sheet. When text selection is on, long-press is selection's, so the selection menu carries Show original instead; neither renderer customises that menu today, so this is new work on both. The WebView reads `data-rk-src`; the native renderer has `NovelChapterTags.prepare` rewrite each mark into a numbered tag its handler turns into a span, the way it numbers anchors.
- The chapter title is translated as one more unit and shown in the reader. The details chapter list, shared by both types, shows a cached title translation where one exists and translates the titles on screen in one batch when titles translation is on, manga and novels alike; novel chapters whose text was translated carry a mark, which manga hides (a manga chapter has no text). A whole list is never translated on open.
- A toggle reloads by percent, and the line position kept for a rebuilt Activity is dropped with the old text.
- Read aloud: a chapter shown translated is spoken by the engine's voice for the target language when the chosen voice is another language, and split by that language's sentence rules; a chapter shown in the original keeps the chosen voice. Decided per chapter, so a window mixing both switches as it crosses.
- Neighbour warm-up translates every neighbour with ML Kit and only the next chapter with a network engine, since those calls cost money.

### Downloads, export, browse and details

- **Pre-translate on download**: a stored chapter of a novel set to translate is handed to its own deferrable WorkManager job, outside the download lane and its save lock, which runs `prepare` and the engine into the cache. A translation failure never fails a download, and the job resumes from the cache after a time limit or a kill. With a paid network engine, a queue of more than 50 chapters asks once before translating. The file stays the original.
- **EPUB export**: Original, Translated, or Both. Translated and Both run each chapter through `prepare` (so the user's regex rules apply, as they do in the reader, where an Original export keeps the stored text untouched as today), the kernel and the cache, translating what the cache lacks; a chapter that cannot be translated goes in as its original and the finished notice counts them. The book's language becomes the target language. Both puts each stretch's translation after its original, inside the same block, marked as the translation.
- **Browse and details**, for manga and novels alike (both surfaces are shared): browse rows are mapped one at a time, so a shared title translator in the catalogue engine collects the visible rows' titles, debounced, into one batch keyed by `EntryBrowseRow.key`, across the per-source grids and the four multi-source lists. A details page sends its title and description. Translations are display only: they never reach the stored title, duplicate checks, tracker search, or the Edit info and tracker-autofill prefill.

### Builds

A `translate-mlkit` module with `src/mlkit` and `src/noop` source sets, as `telemetry` has, gated by a new `BuildConfig.includeMlKit` (`flag("include-mlkit") ?: distribution != FOSS`). `includeTelemetry` cannot serve, because it is false for local builds. On FOSS the ML Kit choice, the model manager, the Wi-Fi option and the usage note are hidden, there is no default engine, and turning translation on opens engine setup.

## Key files

- `TranslationSegments` and `TranslationSegmentsTest`, on the spike/translation branch until the kernel moves in.
- `reikai/novel/content/NovelContentPipeline.kt`, `process`.
- `reikai/novel/source/NovelChapterTextLoader.kt`, `load`.
- `reikai/presentation/reader/NovelReaderViewModel.kt`, `cacheIfCurrent`, `warmNeighbour`, `latestTopLine`.
- `reikai/presentation/reader/text/NovelChapterTags.kt`, `prepare`, `ANCHOR_TAG`.
- `app/src/main/assets/novel-web/reader.js`, the `touchstart` long-press.
- `reikai/domain/merge/GatherGroupViewerFlags.kt`.
- `reikai/data/novel/tts/SystemTtsEngine.kt`, `pieces`.
- `reikai/novel/export/NovelEpubExportWorker.kt`, `export`.
- `reikai/domain/novel/text/TextSegments.kt`, `sentences`.
- `gradle/build-logic/src/main/kotlin/mihon/gradle/BuildConfig.kt`, `telemetry/build.gradle.kts`.

## Build order

Each step ends green on its check before the next starts.

1. **Kernel.** Move `TranslationSegments` and its tests in, drop `ofPlainText` for the HTML route, add the `data-rk-src` marks. Check: the 26 tests plus mark tests and a `.txt` chapter case, every rule mutation-checked.
2. **Cache, engine contract, shared HTTP layer, glossary.** Check: a fake engine test for count mismatch, retry, the glossary round trip, and cache keys missing on each key part.
3. **Seam and status.** Split the pipeline, the loader result, the reader's cache rule. Check: `NovelContentPipelineTest` unchanged and green, a fake engine test that a failed chapter is not cached and Retry reloads it, and that a neighbour returning `Original` is warmed once.
4. **ML Kit module and build gate.** Check: a FOSS APK listing has no `com/google/mlkit` classes and no `libtranslate_jni.so`; the standard build translates on device.
5. **Network engines.** Check: request and response fixtures per engine against their documented shapes, then one live call each with a key the owner supplies.
6. **Settings, per-novel switch, reader line, sheet toggle.** Check: the switch reloads, a merged series shares it, the toggle lands by percent.
7. **Long-press original, chapter titles, chapter list mark, read aloud voice, warm-up cap.** Check: long-press on both renderers on device; read aloud speaks a translated Korean chapter in English.
8. **Download pre-translation, EPUB export modes.** Check: a pre-translated chapter opens with no engine call; an exported Both book opens in an EPUB reader.
9. **Browse and details for both types.** Check: one batch call per page, manga and novel alike.
10. **Docs.** A subsystem doc folding this plan, CHANGELOG entries, ROADMAP, the parked entry removed.

## Open questions

None open. Ruled: the five engines above, ML Kit on standard builds, off by default per novel, the spikes before building.

## Decisions & tradeoffs

- **Formatting inside a sentence is lost in translation.** Bold, italics and links inside a paragraph come back as plain text, because the engines cannot keep markers and translating each formatted piece alone breaks the sentence. A paragraph wrapped whole keeps its wrapper.
- **Manga chapters are not translated.** A manga chapter is images with no text for an engine to read; text recognition is out of scope. Browse and details translation covers manga, since their titles and descriptions are text.
- **The cache stores units, not chapters**, so every surface shares one store and nothing needs renaming when a chapter moves.
- **One glossary mechanism for every engine** instead of DeepL's glossary API and an LLM prompt each, since the swap-and-restore measured exact for ML Kit, the hardest case.
- **API keys follow tracker logins into backups**: carried only when Private settings is ticked, since a user restoring onto a new phone may want them, and an app-state key would make that impossible.
- **A translated `.txt` chapter is finished as HTML**, trading paragraph breaks for line breaks at auto-split points, so marks and the kernel have one path.
- **A download keeps the original file.** Translations live in the cache, so changing the engine or the language never rewrites a download.
