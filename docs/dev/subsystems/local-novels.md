# Local files: local novels, import and EPUB export

## Purpose

Lets a reader bring their own books and comics in and take novels out. EPUB books in the `localnovels` storage folder are a novel source of their own, beside manga's Local source; an import screen copies picked or shared files into either folder; and any novel's chapters on the device can be written out as an EPUB.

## How it works

**The EPUB kernel** (`reikai/novel/local/epub/`) is plain Kotlin over an `EpubArchive`, which reads several zip entries in one pass (`ArchiveReader.readEntries`, since libarchive finds an entry only by scanning from the first). `EpubBook.parse` reads the package: metadata (with Calibre's and EPUB 3's series and position), the spine, the cover (EPUB 3 property, EPUB 2 meta, then an image named or id'd as one), and the table of contents, the EPUB 3 nav before the EPUB 2 NCX, and of the NCX only its `navMap`. Tags are matched by local name, since packages spell `opf:item` and `item` alike.

`EpubChapters.list` reads the book as one stream in spine order and cuts it where the contents point (`EpubCut`): a chapter runs from its cut to the next, across files, so a file the contents skip continues the chapter before it and a file holding several chapters is divided at their anchors. Whatever comes before the first entry opens the first chapter. With no contents, each file is a chapter, and a book that is a single file is cut at its top-level headings. A chapter with under 100 characters and no picture joins the next, named for both ("BOOK ONE - CHAPTER I"), and a Gutenberg caption in front of a chapter name is dropped (`cleanTitle`).

`EpubChapters.render` cuts each file the chapter covers (`keepFrom`, `keepUntil`; an anchor that opens its element stands for the element, `lift`), turns an SVG that only frames a picture into an `<img>` (the renderers drop SVG), keeps links only within the chapter, and inlines pictures as `data:` addresses through `inlinedImageAddress`, the cap downloads use. Linked and inline stylesheets come along without fonts, imports, colours or backgrounds, so the reader's theme wins while indents and alignment stay; a stylesheet picture is inlined when the manifest declares it an image (`EpubBook.imageType`). Scripts and event handlers are left to the reader's shared sanitizer (`NovelHtmlUtils`), so a local book follows keep-embedded-JS like any source.

**The source** is `LocalNovelSource`, id `local`, handed to `NovelSourceManager` as a built-in through `ReikaiBindings.providesBuiltInNovelSources`. Each folder under `localnovels` is a series and its EPUBs are volumes in natural name order; a loose EPUB is a series of one. A `details.json` (manga's `MangaDetails` schema) or a `cover.*` picture in the folder (`localCover`, the rule manga's local source reads too) wins over the books; otherwise the first readable book's cover is copied once into app storage. A cover set from the cover viewer is written as that `cover.*` (`LocalNovelSource.writeCover`), in the library or not, as manga's local source does; a loose EPUB has no folder, so it takes an ordinary custom cover. Browse and search read only each book's package, cached per file version; a book's chapter list, which parses every chapter file, is cached only for the few novels being opened.

A chapter's path (`chapterPath`) names the series, the volume file and the chapter's `EpubChapterKey`: the file and anchor its contents entry points at, or the heading it is cut at, never its place in the spine. Each part is percent-encoded, so a `::` in a folder or file name reads back. A book updated with a chapter added partway through keeps every other chapter's path, so the sync hands no read state to the wrong chapter; renaming a volume file gives its chapters new paths and loses their history, as renaming a manga local chapter does. A refresh leaves out only a volume that is protected or not an EPUB; any other failure to open one fails the refresh (`readableVolumes`), since a list missing a volume would delete its chapters' read state. Its web address is blank, which hides WebView, Share and Copy link.

**Language.** A local source's own language is the placeholder `other`, so a local series' language is read from its files and the library, search, group by language, Statistics and export read that instead (`entryLanguage`, `localLanguage` in `domain/.../EntryLanguage.kt`, which keeps a region only where sources split by it). A novel's is written to `local_novel_language` by series path each time `parseNovel` reads it, `details.json` over the EPUB's `dc:language`, and stored as that code, so a three-letter tag reads as its two-letter one and `mul` as Multi (`all`). It is backed up on the novel (`BackupNovel.language`) and restored into the table, since a restored novel with chapters is not read again until a refresh. A manga's rides in its `memo` (`reikai:language`, backed up with it), from ComicInfo `LanguageISO`, and a legacy `details.json` language survives its conversion to `ComicInfo.xml`. A merged series searches and groups each member by its own language, not its source's, since every local series of a type shares one source. `LocalSeriesLanguageMigration` reads every local series in the library of both types once on upgrade, through each local source's own details read, so an existing library gets its languages without a refresh. Export writes the entry's language as the book's (`bookLanguage`), with Multi as `mul`, so a re-imported copy groups under Multi again.

Local is a source state like manga's (`EntrySourceState.Local`): no download controls, no browse link, left out of the migration source list, never disabled, and its chapters count as on the device (the novel `downloadedChapterIds` kernel, as manga's), so the downloaded filter, Downloaded only, search and word count include them. `NovelDownloadedTexts` reads a local chapter's text from its book.

**Import** (`LocalImport`, `LocalImportScreen`) serves both types. A picked, opened or shared file is read for a preview: an EPUB through the kernel (fixed-layout books suggest manga), a comic archive for its ComicInfo series and writer and its first page. The kind comes from the name, else the provider's MIME type, else the archive's contents (`EpubBook.looksLikeEpub`, else a page by `ImageUtil.isImage`), and a copy without an extension gets one. A file a provider streams through a pipe is read from a copy in the cache, since it cannot be mapped. A file's name is the provider's, made safe as a file name. Files group by type and series key (`groupKey`), and a group is named after its series, even a lone EPUB volume, so it lands where its siblings do. An Open with or Share takes only `content:` URIs, judged per file by the type or name its provider gives; one that arrives while the import screen is open joins its picks (`ImportHandOff`) rather than replacing them. Import copies each group into the folder whose name matches its series by `groupKey`, or a new one, in `local` or `localnovels` (numbered when a new series gets several files, skipped when a file of that name and size is there), then stores and favorites it through each type's batch add: `insertOpenedNovel` and `NovelLibraryAdder.addToBatch` for novels, `NetworkToLocalManga`, `UpdateMangaFromRemote` and `MangaLibraryAdder.addToBatch` for manga. Categories are asked once per type when the batch rule says so. The copy runs on the app scope.

**Export** (`EpubWriter`, `NovelEpubExportWorker`) writes EPUB 3 with an EPUB 2 NCX beside the nav. `mimetype` is stored first; each chapter is well-formed XHTML with its `data:` pictures written once each as files, web pictures and anything that runs dropped, and a heading added only when the chapter does not open with one. The metadata is what the import reads back: title, author, the artist as an illustrator contributor, the status in a `reikai:status` meta, genres as subjects and the description as paragraphs, so a source novel exported and imported keeps its details. The worker exports the chapters on the device in reading order (`NovelDownloadedTexts.chaptersOf`), a run of them if asked, to one file or, for a library selection, one file per novel in a folder. Chapter text comes through `NovelDownloadedTexts.storedHtml`, the read search and word count use, and chapter markup through the reader's own prune (`NovelHtmlUtils.sanitizeForRender`) before the book's extra drops. The cover is loaded the way the library draws it (custom, a local series' `cover.*`, cached or fetched) and written as JPEG, or PNG when it has transparency. A file is written from empty (`openTruncatingOutputStream`, since a document provider takes plain "w" as write in place) and deleted when the write fails or is cancelled, so no half book is left behind.

## Key files

- `app/src/main/java/reikai/novel/local/epub/EpubBook.kt`: `EpubBook.parse`, `EpubArchive`, `EpubException`.
- `app/src/main/java/reikai/novel/local/epub/EpubChapters.kt`: `EpubChapters.list`, `render`, `cleanTitle`.
- `app/src/main/java/reikai/novel/local/LocalNovelSource.kt`: `LocalNovelSource`, `chapterPath`, `readableVolumes`.
- `app/src/main/java/reikai/data/local/LocalImport.kt`: `LocalImport.preview`, `import`, `groupKey`.
- `app/src/main/java/reikai/presentation/local/LocalImportScreen.kt`: `LocalImportScreen`.
- `app/src/main/java/reikai/presentation/local/LocalImportViewModel.kt`: `LocalImportViewModel`, the category ask per type.
- `app/src/main/java/reikai/presentation/local/ImportIntents.kt`: `importedFiles`, read by `MainActivity`.
- `app/src/main/java/reikai/novel/export/EpubWriter.kt`: `EpubWriter`, `EpubExportBook`.
- `app/src/main/java/reikai/novel/export/NovelEpubExportWorker.kt`: `NovelEpubExportWorker.start`.
- `app/src/main/java/reikai/novel/export/EpubExportFiles.kt`: `writeOrDelete`, `coverPicture`.
- `app/src/main/java/reikai/presentation/novel/details/NovelExportDialog.kt`: `NovelExportDialog`.

## Invariants and traps

- **A regex here must compile on Android's ICU engine, not only the JVM's.** A bare `}` that Java accepts made `EpubChapters` fail to initialise on a device while every JVM test passed; escape every brace.
- **Only an encrypted chapter file means DRM.** Most books carrying `encryption.xml` only obfuscate fonts.
- **The category dialog dismisses before it confirms.** Answers already given survive a dismiss; a new import resets them.
- **A test chapter needs real length.** Under 100 characters it is read as a part title and joined to the next.
- **The local source's text arrives decoded.** The EPUB reader decodes its XML and HTML already, so entity decoding happens in each scraping adapter (`withDecodedDetails`, `withDecodedText` for a chapter), never in the shared `toNovel` or `toNovelChapter`, or a description or chapter title that spells `&amp;` loses it.
- **Local chapters are on the device but have no download file.** Anything that reads stored text for a local chapter goes through the source, never `getChapterText`.

## Decisions

- **Files are copied in, not linked.** A picked document's permission does not outlive the app; void if a persisted tree grant is used instead.
- **A book is read from its zip each time, not unpacked.** Deleting the file removes the series and nothing is stored twice; void if reading a chapter from a large book becomes slow.
- **EPUB export is novels only.** Manga's offline copy is the CBZ Mihon already writes, and an image-only EPUB of a comic serves few readers; void if users ask for comic EPUBs.
- **Export writes only what is on the device.** Fetching during an export would meet rate limits and challenges with nobody watching; the dialog offers the download instead.

## Upstream divergences

`// RK` patches: `ArchiveReader.readEntries`; `Archive.isSupported(extension)`, so the import names a picked archive by the local source's own list; `ImageUtil.isImage` reads an extension in any case and takes `.jpeg`, so the import's name-only page test knows the common names; `StorageManager.getLocalNovelsDirectory`; `MainActivity` (the import intent, and handing files to an open import screen); `AndroidManifest.xml` (VIEW and SEND filters); `LibraryToolbar` and `LibraryTab` (Import files, batch export); `MangaBottomActionMenu` (Export as EPUB); `Notifications` (export ids). See [upstream-sync.md](../upstream-sync.md).

## Extending

A new book format: give `LocalImport.kindOf` its name, type and contents test, and a source that reads it. A new metadata field: read it in `EpubBook.readMetadata` and map it in `LocalNovelSource.parseNovel`.

## Tests

`EpubBookTest` (contents, metadata, DRM, namespaces), `EpubChaptersTest` (cuts, joins, rendering), `EpubWriterTest` (structure, XML, round trip through the kernel), `EpubExportFilesTest` (truncating write, cleanup, cover encoding), `LocalNovelChapterPathTest`, `LocalNovelChapterIdentityTest` (an added chapter moves no read state), `LocalNovelVolumesTest`, `LocalImportGroupKeyTest`, `LocalImportSeriesTest` (series names, page names), `LocalImportTest` (folder matching, a refused add, both types), `ImportIntentsTest` (which shared files are taken, provider names and sizes), `LocalImportViewModelTest`, `ImportListTest`, `ImportGroupCardTest`. Language: `EntryLanguageTest`, `LocalMangaLanguageTest`, `LocalRowLanguageTest` (both row builders, merged members), `LocalNovelLanguageRepositoryTest`, `LocalNovelLanguageBackupTest`, `LocalSeriesLanguageMigrationTest`. Run the EPUB, import and export tests with `./gradlew :app:testDebugUnitTest --tests "reikai.novel.local.*" --tests "reikai.novel.export.*" --tests "reikai.data.local.*" --tests "reikai.presentation.local.*"`. The language tests sit elsewhere: `EntryLanguageTest` under `:domain:test`, `LocalNovelLanguageRepositoryTest` under `:data:testDebugUnitTest`, and the other four in `:app` by class name (`reikai.domain.library.LocalMangaLanguageTest`, `reikai.presentation.library.LocalRowLanguageTest`, `reikai.data.backup.LocalNovelLanguageBackupTest`, `mihon.core.migration.migrations.LocalSeriesLanguageMigrationTest`).

## Related

User doc: [local source](../../guides/local-source/index.md).
