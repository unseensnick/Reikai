# Novel reader rendering

## Purpose

How a light-novel chapter becomes a screen inside the shared reader: the novel model and its chapter window, the content pipeline, the two rendering modes (native text, the default, and Reikai's own WebView document), reading progress and landing, and read aloud. The host, the engine, the chrome and the settings sheet are in [reader.md](reader.md).

## How it works

### The model and the window

`NovelReaderViewModel` is the novel session: the chapter list (through `GetNextNovelChapter.readingOrder` and `hiddenAmong`, the rules the resume uses), the display settings as one `NovelReaderSettings` flow so a change restyles live, chapter text, progress, history, trackers, mark-read and the trim behind the reader. `NovelReaderProvider` adapts it for the engine and builds the viewport the rendering mode picks (`NovelPreferences.readerRenderingMode`, `NATIVE` or `WEBVIEW`; an unknown stored name such as the retired `LEGACY` falls back to `NATIVE`). Both viewports take one `NovelViewportCallbacks` value, so the two cannot be wired differently.

`NovelChapterTextLoader` is the one session-scoped chapter loader: the downloaded copy first, else `NovelSource.parseChapter`, then the content pipeline. `reloadChapter(fromSource)` drops the chapter from the session's text cache and, from the source, skips the downloaded copy once.

Reading is seamless in both directions while `readerSeamlessChapters` is on (the default). The model publishes a `Window` of up to three chapters (previous, current, next) with a generation and an anchor, and the host drives the viewport's `ChapterWindow` verbs (`append`, `prepend`, `evict`) in the order `NovelWindowDiff` gives: dropped chapters first, then below, then above. The viewport reports the visible chapter back, so the title, progress, mark-read and history follow the reader across a seam. The next chapter is fetched on open but joins the window only once the reader passes `readerAutoLoadNextAt` (95%) of the current one (`NovelWindowReach.joinable`); a previous chapter joins only once the chapters below have arrived or failed (`previousMayJoin`), and the window reaches past up to three chapters that fit on one screen. A failed neighbour waits 15 seconds before an unprompted retry (`ChapterRetryCooldown`, the rule the manga reader's preloads share) and draws a failure with Retry at the window edge (`NovelBoundaryFailureView`, `ChapterWindow.setBoundaryFailures`); an explicit open or Retry clears the wait.

The `lane` mutex orders every open, crossing and window publish. On a rebuild (rotation, a mode switch, a redraw) the host starts the renderer at `landingOf`, the live position, and reports `rendererLanded`; until then the model drops what the renderer says. `NovelOpenLanding` keeps a chapter the landing passed over from being read, saved, tracked or deleted until the reader moves (`ReaderProvider.onReaderMoved`). `NovelResume` opens a read chapter at its start, as `ChapterLoader` does for manga. `NovelCompletionLatch` finishes a chapter once a session.

### Progress, completion and landing

Progress is a whole percent of the visible chapter, from that chapter's own bounds (`ChapterScrollProgress` natively), measured against the chapter's height less one screen for every chapter, so a percent saved with a neighbour below restores to the same place. It is stored in hundredths but written and resumed in whole percent, written from live reports debounced and flushed on pause (`flushPosition`). A chapter is read at `readerMarkReadPercent` (97 by default) through `isChapterComplete`.

A chapter shorter than the screen holds at 0 and reports that it fits. `NovelLeaveRule` reads it when the reader leaves it forward, including the chapters a fling carries the reader over, and a forward step from one that fits reads it whatever the skip setting says. The last chapter the reader can reach is read when its last line is seen (`onChapterEndSeen`, held while its pictures load), since it has nothing to be left into.

A rebuilt renderer lands on the line the reader had at the top, reported by both renderers as the count of shown characters before it in the chapter (`shownCharPrefix`, `topLine` in `reader.js`, spaces, pictures and ruby readings left out), so a rotation or a mode switch lands on the same line. A landing by percent waits for the chapter's pictures, since it is a fraction of the final height; a line landing does not. The wait is capped at `CHAPTER_IMAGE_WAIT_MS` (3 s), and while it runs both renderers veil the chapter in the reader's background and keep touches, the wheel and the volume keys off the text (`ResumeVeil`), so the reader never sees the chapter's start meanwhile or scrolls the landing away. The native renderer lifts the veil in the same pass as the landing; the WebView renderer lifts it on the page's ready, which the page sends only after its seek. Manga resumes by page index and has no such wait.

### The seam and the end marker

The boundary between chapters is the webtoon viewer's: Mihon's `TransitionText` taking two chapter names, 128dp above and below, a centred column capped at 460dp. `NovelSeam.between` gives the downloaded mark and the missing-chapter warning through `ChapterGap.atSeam`, the rule `MissingChapters.calculateChapterGap` also uses. Whether a seam between two consecutive chapters shows is the novel "Always show chapter transition" setting (`NovelSeam.drawn`), and the last reachable chapter always ends with "Finished" over "There's no next chapter" (`NovelSeam.end`). A reader inside a seam is in the chapter below it, in both renderers.

### The content pipeline

The pipeline in `reikai/novel/content/` is ported from tsundoku's `shared/` package and taken whole: sanitising, regex find-and-replace (`NovelRegexReplacements`, one `compile` kernel shared with the editor's preview, which also checks a replacement's group references), remove extra spacing, force lowercase, auto-split (`NovelTextSplitter`, which keeps a text file's own line breaks), hide chapter title, raw HTML and block media. `NovelContentConfig.from` reads every chapter-text setting and `NovelContentConfig.changes` watches the same list, so a change rebuilds both readers' text. Every one acts on chapter text, so they are novel-only by mechanism.

Scripts a chapter embeds are stripped unless "Run scripts a chapter embeds" is on. The WebView target prunes the parsed tree (`NovelHtmlUtils.sanitizeForWebView`): frames, plugin elements, `base`, `meta`, `form`, `svg`, `math` go, and every `on*` attribute and `javascript:` URL goes unless scripts may run. The free-form CSS preferences are checked against the shape each can have and replaced by the default when they do not match (`NovelReaderCssValues`), since a restored backup can write any key.

### Native text

`NovelTextViewport` is a `RecyclerView` of chapters, one item each, whose item view is a `ChapterTextBlock`: a stack of `TextView` chunks of at least 6000 characters, cut after a newline, because one view per chapter makes layout, span lookup and selection order-of-chapter. `NovelTextRenderer` turns the pipeline's HTML into spans through `Html.fromHtml` (with `NovelChapterTags` rewriting `hr`, `sup`, `sub`, ruby and in-chapter links into tags it passes), and `NovelTextStyle` applies size, line height (line height times the text size, as CSS does), alignment, the four margins, colours and fonts. `ParagraphShape.needsRedrawFor` decides restyle from redraw: indent and spacing are pixel spans built with the text, so changing them, or the size while they are set or a picture is in the window, owes a redraw; otherwise a size drag restyles the views it already has.

Pictures are fetched by `NovelImageGetter` with the chapter's base URL as Referer, sized from the source's own dimensions, and a tall one is drawn from slices around what is on screen (`PictureBox`, `PictureTiles`, `TileReader`, `TiledPicture`), since a decode is capped at 4096px a side. Its stand-in is decoded small (`previewSampleSize`). A failed picture draws a box with Retry. Bionic reading is `NovelBionicSpans`, the vendored `text-vide` table (bold a word's length minus the index of the first boundary it fits in).

The line at the top holds still through anything that changes heights above it: a seam or failure view arriving, a restyle (`holdingReader`) and pictures landing all correct through `postCorrection`, a pre-draw listener, so no frame shows the reader moved. A chapter joins the list only once its text is set.

Taps are read from an `OnItemTouchListener` above the chunks, with one tap owner per mode. Links and text selection are exclusive by setting (`ln_reader_text_selectable`, off by default): with selection off chunks run `LinkOnlyMovementMethod`; with it on, `ArrowKeyMovementMethod`, and links are dead but the app's own controls (`ReaderControlSpan`, a picture's Retry) still answer through `tapReaderControl`. `SelectableWhileAttached` lifts `viewer_container`'s `blocksDescendants` while selection is on. `ChapterSwipe.kt` holds the chapter-swipe rule both renderers follow: at least 180dp, strictly wider than twice its height, never after a second finger, and a touch that has travelled (`hasTravelled`) is a drag, never a tap or long press (`dragEndsPress`).

### The WebView mode

`NovelWebViewport` loads a document Reikai builds, `NovelWebDocument`, inlining `assets/novel-web/reader.css` and `reader.js` through `NovelWebAssets`; tsundoku contributed the boundary and progress tracking in `reader.js`, the font-override rules in `NovelWebDocument.overrides` and the asset loader, nothing more. Chapters live in `#rk-chapters`; the engine runs from `<head>` ahead of the chapter, and each page carries a document token so only its own ready report opens the viewport's gate (`NovelDocumentGate`). Gestures take only trusted touch events. Fonts travel inline as `data:` URIs (`NovelWebFonts`); a font picked mid-chapter reaches the page through `rkReader.setFontFace`. The WebView mode is where a source's own CSS can be kept (`readerKeepEmbeddedCss`, `readerKeepEmbeddedJs`, use original fonts, source CSS priority), plus user CSS and JS snippets and WebView developer tools.

`reader.js`'s `place` holds the reader's line itself, with Chromium's scroll anchoring off (`overflow-anchor: none`): it keeps the first character of the top line at the same height after any layout change, and every scroll, the reader's or one made for them, moves the held line with the page. Smooth scrolls are relative steps a frame (`glide`), so a chapter added or evicted above mid-animation does not move the target. Scripts in chapters added by scrolling run (`runScripts`) when scripts may run. Report throttling holds a report back rather than dropping it.

### Read aloud

`ReadAloudController` in the model decides what is spoken; both renderers answer `ReadAloudSurface` by one paragraph rule (a non-blank line of the text as shown, whitespace collapsed, ruby readings left out), so a position saved in one names the same text in the other. Highlight sentence, off by default, speaks each sentence as its own utterance and marks it on `onStart`. `SystemTtsEngine` splits an utterance past the engine's maximum input through `TtsUtteranceSplitter`, built on `BreakIterator` so CJK text splits on real boundaries, and checks each `speak` return. Errors abort a paragraph only when they name a piece of it (`SpokenParagraph`).

`NovelTtsService` and `NovelTtsSession` carry the media session, audio focus (`TtsFocusPolicy`: pause on any loss, resume only after a transient one), the noisy-audio pause, the sleep timer (`TtsSleepTimer`, on elapsed realtime) and media buttons. `TtsMediaButtonClaim` loops silence from the app while speech plays, because Android routes media buttons only to an app it hears and the voice plays from the engine's process. The bottom-bar button shows floating controls; hiding them does not stop playback. Following the voice scrolls only when the paragraph is off screen, measured inside what the bars cover (`TextViewport.setObscured`), and the native mark is drawn from the text's own geometry by `ReadAloudBoxDecoration`. Auto-scroll pauses while read aloud plays.

## Key files

- `app/src/main/java/reikai/presentation/reader/NovelReaderViewModel.kt`: the session, `lane`, `landingOf`, `rendererLanded`, `reportTopLine`.
- `app/src/main/java/reikai/presentation/reader/NovelReaderProvider.kt`: the provider, `createViewport`, `attach`, `viewportRebuilds`.
- `app/src/main/java/reikai/presentation/reader/NovelReaderSettings.kt` and `app/src/main/java/reikai/domain/novel/NovelPreferences.kt`: the settings.
- `app/src/main/java/reikai/domain/novel/NovelRenderingMode.kt`: the two modes.
- `app/src/main/java/reikai/novel/source/NovelChapterTextLoader.kt`: the chapter loader.
- `app/src/main/java/reikai/presentation/reader/TextViewport.kt`: `TextViewport`, `ChapterWindow`.
- `app/src/main/java/reikai/presentation/reader/NovelViewportCallbacks.kt`: the shared viewport wiring.
- `app/src/main/java/reikai/presentation/reader/text/NovelWindowReach.kt`, `app/src/main/java/reikai/presentation/reader/text/NovelWindowDiff.kt`: the window rules.
- `app/src/main/java/reikai/presentation/reader/text/NovelLeaveRule.kt`, `app/src/main/java/reikai/presentation/reader/text/NovelOpenLanding.kt`, `app/src/main/java/reikai/presentation/reader/text/NovelResume.kt`, `app/src/main/java/reikai/presentation/reader/text/NovelCompletionLatch.kt`, `app/src/main/java/reikai/presentation/reader/text/ResumeVeil.kt`: reading and landing rules.
- `app/src/main/java/reikai/presentation/reader/text/NovelSeam.kt`: `between`, `drawn`, `end`.
- `app/src/main/java/reikai/novel/content/NovelContentPipeline.kt`, `app/src/main/java/reikai/novel/content/NovelContentConfig.kt`, `app/src/main/java/reikai/novel/content/NovelHtmlUtils.kt` (`sanitizeForWebView`), `app/src/main/java/reikai/novel/content/NovelRegexReplacements.kt`: the pipeline.
- `app/src/main/java/reikai/presentation/reader/NovelTextViewport.kt`: the native viewport, `postCorrection`, `holdingReader`, `startRedraw`.
- `app/src/main/java/reikai/presentation/reader/text/NovelTextRenderer.kt`, `app/src/main/java/reikai/presentation/reader/text/NovelTextStyle.kt`, `app/src/main/java/reikai/presentation/reader/text/ChapterTextBlock.kt`, `app/src/main/java/reikai/presentation/reader/text/ParagraphShape.kt`: native rendering.
- `app/src/main/java/reikai/presentation/reader/text/NovelImageGetter.kt`, `app/src/main/java/reikai/presentation/reader/text/PictureTiles.kt`: pictures.
- `app/src/main/java/reikai/presentation/reader/text/ChapterSwipe.kt`: `chapterSwipeStep`, `hasTravelled`.
- `app/src/main/java/reikai/presentation/reader/NovelWebViewport.kt`, `app/src/main/java/reikai/presentation/reader/web/NovelWebDocument.kt`, `app/src/main/java/reikai/presentation/reader/web/NovelDocumentGate.kt`, `app/src/main/java/reikai/presentation/reader/web/NovelReaderCssValues.kt`: the WebView mode.
- `app/src/main/assets/novel-web/reader.js` and `app/src/main/assets/novel-web/reader.css`: the page engine (`place`, `glide`, `topLine`).
- `app/src/main/java/reikai/presentation/reader/ReadAloudController.kt`, `app/src/main/java/reikai/presentation/reader/ReadAloudSurface.kt`: read aloud's model side.
- `app/src/main/java/reikai/data/novel/tts/NovelTtsService.kt`, `app/src/main/java/reikai/data/novel/tts/NovelTtsSession.kt`, `app/src/main/java/reikai/data/novel/tts/SystemTtsEngine.kt`, `app/src/main/java/reikai/domain/novel/tts/TtsUtteranceSplitter.kt`: the transport and engine.

## Invariants and traps

- **Both renderers follow one rule for everything a reader can observe**, and `TextViewportContractTest` runs the cases against both real viewports: paragraphs, the seam, landing, the end report, swipes, taps and holding the line. Where the page cannot call Kotlin (the swipe threshold, the multi-finger rule), the rule is stated once in Kotlin and handed to the page.
- **Progress must not be reported before the text is measured.** A recycler with no scroll range reports 100%, which at the completion threshold marks the chapter read and retires its download.
- **Restyling a chunk holding `PrecomputedText` copies it into a `SpannableStringBuilder` first**, never `toString()`, which drops every span, and never leaves the `PrecomputedText`, which crashes the long-press drag path.
- **The renderer checks `ChapterTextBlock.discarded`, not `isAttachedToWindow`.** A chapter queued below the reader is never attached until it has a height.
- **A recycler never lays out an item entirely above the viewport only while the screen is full.** With a short chapter and nothing below, the layout manager scrolls a newly joined chapter into view at its placeholder height, which is why a chapter joins only once its text is set.
- **A `TextView` queues its own click before its movement method sees a link**, so `LinkOnlyMovementMethod` cancels that click, or every link tap is also a tap zone.
- **Two of tsundoku's preference reactions rebuild the whole document** (indent, spacing, text selectable), and its observer's hardcoded `drop` counts break when a preference is added. Neither is inherited; settings reach the viewport through `applySettings` and the redraw rule.
- **A rebuild or a mode switch must not re-aim at the session's first chapter.** `load()` reads `pendingChapterId`, which only an explicit open moves.

## Decisions

- **Tsundoku's portable layers, not its viewers or a from-scratch renderer.** Their two viewers are welded to a forked `ReaderActivity` and to novels as manga rows (`Page.text`); a from-scratch renderer has no upstream to sync from. Their pipeline and native renderer have one, credited in [feature-ports.md](../feature-ports.md).
- **The WebView mode is a capability, not a fallback.** A text layout cannot render a source's own CSS faithfully. Its document is Reikai's own, since a port of theirs would carry their chapter queue.
- **The window is three chapters, the engine's own type, not `ViewerChapters`.** A novel chapter is one HTML body with no pages, so it cannot travel as a `ReaderPage`.
- **A failed boundary load takes both a self-clearing cooldown and a manual Retry.** The cooldown alone respawns a request every scroll frame; the Retry alone strands auto-append once a chapter switch destroys its banner.
- **Seamless reading is novel-only and switchable.** Manga's webtoon seam is between image pages read as continuous; a chapter boundary is a place some readers want to stop.
- **The mark-read threshold defaults to the incumbent 97**, not tsundoku's 95 or 99, and a short chapter is read on leaving it rather than on sight.
- **Paragraph spacing defaults to 1.5em and the blank line `Html.fromHtml` inserts is collapsed**, so the two renderers draw the same gap; tsundoku keeps that line and its numbers mean something else.
- **Our TTS transport, tsundoku's control model.** Their controller owns `TextToSpeech` directly, queues a whole chapter and only logs utterance errors; their splitter is ASCII-only.
- **Fonts are TTF or OTF only.** Android builds a typeface from SFNT only while the WebView takes woff2, so accepting woff2 would work in one mode and silently not in the other.
- **Rendering progress to the hundredth is not taken.** The whole-percent cadence is deliberate and no resume lands visibly off.

## Upstream divergences

None in Mihon files of its own beyond the host wiring [reader.md](reader.md) lists: the novel renderers, the pipeline and read aloud are Reikai-owned. The tsundoku port is recorded in [feature-ports.md](../feature-ports.md).

## Extending

- **A setting that changes chapter text**: add its preference to `NovelContentConfig.from` and `changes` together (`NovelContentConfigTest` fails otherwise) and apply it in the pipeline, so both renderers get it.
- **A setting that changes how text looks**: read it into `NovelReaderSettings`, apply it in `NovelTextStyle` and in the page's CSS variables, decide restyle or redraw in `ParagraphShape`, and add a contract case over both renderers.
- **A behaviour the reader can see in the text**: implement it in both viewports and pin it in `TextViewportContractTest`.

## Tests

JVM: `NovelReaderViewModelTest`, `NovelReaderProviderTest`, `NovelReaderSettingsTest`, `NovelWindowReachTest`, `NovelWindowDiffTest`, `NovelLeaveRuleTest`, `NovelOpenLandingTest`, `NovelResumeTest`, `NovelCompletionLatchTest`, `ResumeVeilTest`, `NovelSeamTest`, `ChapterScrollProgressTest`, `ChapterSwipeTest`, `ParagraphShapeTest`, `NovelBionicSpansTest`, `NovelChapterTagsTest`, `NovelTextChunkingTest`, `PictureBoxTest`, `PictureTilesTest`, `TiledPictureTest`, `NovelContentPipelineTest`, `NovelContentConfigTest`, `NovelHtmlSanitizerTest`, `NovelReaderCssValuesTest`, `NovelRegexReplacementsTest`, `NovelTextSplitterTest`, `NovelDocumentGateTest`, `NovelWebViewportGateTest`, `ReadAloudControllerTest`, `TtsUtteranceSplitterTest`, `TtsFocusPolicyTest`, `TtsSleepTimerTest`, `SpokenParagraphTest`, `NovelTtsSessionTest`. Instrumented, over both real renderers: `TextViewportContractTest`, `NovelTextViewportWindowTest`, `RecyclerPrependPositionTest`, `WebViewSeamPositionTest`, `RenderedLinesParityTest`, `NovelWebDocumentTest`.

Run one JVM class with `./gradlew :app:testDebugUnitTest --tests "<FullyQualifiedClassName>"`; run instrumented classes through `adb shell am instrument` rather than `connectedAndroidTest`, which uninstalls the app.

## Related

- User docs: [novel-reader.md](../../novel-reader.md), [novel-reader-settings.md](../../guides/novel-reader-settings.md).
- The host and engine: [reader.md](reader.md).
