package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelPreferences
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.ChapterTitleFormat
import reikai.domain.reader.ReaderPosition
import reikai.domain.reader.ReaderProgressLook
import reikai.domain.reader.ReaderProgressSize
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * Rules pinned once over both providers. The chapter title follows each reader's own format setting
 * through one shared kernel. The per-entry rotation flag crosses the seam **unresolved**, so 0 keeps
 * meaning "follow this type's default" and the picker's "use default" row shows as selected; the
 * resolved answer beside it goes through `resolveOrientation` against each type's own default. Each
 * probe pins its adapter's answer, not a model that fills the flag before the seam; that gap stays,
 * since no unit test here builds a ViewModel and the repo carries no Robolectric.
 */
class ReaderProviderConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry pinned to an orientation reports that orientation`(probe: ReaderOrientationProbe) = runTest {
        val provider = probe.provider(
            stored = ReaderOrientation.LOCKED_LANDSCAPE.flagValue,
            default = ReaderOrientation.PORTRAIT.flagValue,
        )

        provider.orientation.first() shouldBe ReaderOrientation.LOCKED_LANDSCAPE.flagValue
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry following the default reports zero rather than the default`(probe: ReaderOrientationProbe) =
        runTest {
            val provider = probe.provider(
                stored = ReaderOrientation.DEFAULT.flagValue,
                default = ReaderOrientation.PORTRAIT.flagValue,
            )

            provider.orientation.first() shouldBe ReaderOrientation.DEFAULT.flagValue
        }

    /** What the window rotates to and the picker highlights, from this type's own default setting. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry following the default resolves to its type's default`(probe: ReaderOrientationProbe) = runTest {
        val provider = probe.provider(
            stored = ReaderOrientation.DEFAULT.flagValue,
            default = ReaderOrientation.PORTRAIT.flagValue,
        )

        provider.resolvedOrientation.first() shouldBe ReaderOrientation.PORTRAIT.flagValue
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry pinned to an orientation resolves to the pin`(probe: ReaderOrientationProbe) = runTest {
        val provider = probe.provider(
            stored = ReaderOrientation.LOCKED_LANDSCAPE.flagValue,
            default = ReaderOrientation.PORTRAIT.flagValue,
        )

        provider.resolvedOrientation.first() shouldBe ReaderOrientation.LOCKED_LANDSCAPE.flagValue
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("titleProbes")
    fun `a chapter title format of Number and name titles the bar with both`(probe: ReaderChapterTitleProbe) =
        runTest {
            val provider = probe.provider(ChapterTitleFormat.NUMBER_AND_NAME, name = "The Duel", number = 12.0)

            provider.chrome.first().chapterTitle shouldBe "Ch. 12: The Duel"
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("titleProbes")
    fun `a chapter title format of Number titles the bar by the number alone`(probe: ReaderChapterTitleProbe) =
        runTest {
            val provider = probe.provider(ChapterTitleFormat.NUMBER, name = "The Duel", number = 3.0)

            provider.chrome.first().chapterTitle shouldBe "Chapter 3"
        }

    /** The engine names the entry by its Edit info title, so each bar says whose titles it shows. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("titleProbes")
    fun `the bar's titles belong to the entry the session opened`(probe: ReaderChapterTitleProbe) = runTest {
        val provider = probe.provider(ChapterTitleFormat.NUMBER, name = "The Duel", number = 3.0)

        provider.chrome.first().entry shouldBe probe.entry
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lookProbes")
    fun `the progress line looks as its own reader's setting says`(probe: ReaderProgressLookProbe) = runTest {
        val look = ReaderProgressLook(ReaderProgressSize.HUGE, isBacked = true)

        probe.provider(look).progressLook.first() shouldBe look
    }

    /** Filling is for screens that cannot show the tint, so every other reader keeps the tint. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fillProbes")
    fun `the bar does not fill its buttons by default`(probe: ReaderBarFillProbe) = runTest {
        probe.provider(filled = null).bottomButtonsFilled.first() shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fillProbes")
    fun `the bar fills its buttons when its own reader's setting says`(probe: ReaderBarFillProbe) = runTest {
        probe.provider(filled = true).bottomButtonsFilled.first() shouldBe true
    }

    /** A reader that opens scrolling on its own is a choice made in Settings, never the default. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sessions")
    fun `auto-scroll does not start on open by default`(
        @Suppress("UNUSED_PARAMETER") name: String,
        provider: ReaderProvider,
    ) {
        provider.autoScrollOnOpen.get() shouldBe false
    }

    /** A merged series' pace is learned from every member's chapters, so its length names them all. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("lengthProbes")
    fun `a merged series' chapter length names every member`(probe: ReaderChapterLengthProbe) = runTest {
        val provider = probe.provider(ownerRecorded = true)

        provider.chapterLength.first { it != null }?.seriesIds shouldBe setOf(1L, 2L)
    }

    /** Incognito is asked of the chapter's own source, which differs between a merged series' members. */
    @ParameterizedTest(name = "{0} recorded {1}")
    @MethodSource("recordedProbes")
    fun `a chapter is recorded as its own owner's source says`(probe: ReaderChapterLengthProbe, recorded: Boolean) =
        runTest {
            val provider = probe.provider(ownerRecorded = recorded)

            provider.chapterLength.first { it != null }?.recorded shouldBe recorded
        }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaOrientationProbe(), NovelOrientationProbe())

        @JvmStatic
        fun lengthProbes() = listOf(MangaChapterLengthProbe(), NovelChapterLengthProbe())

        @JvmStatic
        fun recordedProbes() = lengthProbes().flatMap { listOf(Arguments.of(it, true), Arguments.of(it, false)) }

        @JvmStatic
        fun titleProbes() = listOf(MangaChapterTitleProbe(), NovelChapterTitleProbe())

        @JvmStatic
        fun lookProbes() = listOf(MangaProgressLookProbe(), NovelProgressLookProbe())

        @JvmStatic
        fun fillProbes() = listOf(MangaBarFillProbe(), NovelBarFillProbe())

        @JvmStatic
        fun sessions() = listOf(
            Arguments.of(
                "manga",
                MangaReaderProvider(
                    viewModel = mockk(relaxed = true),
                    readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
                    downloadManager = mockk(relaxed = true),
                    titleWords = EnglishChapterTitleWords,
                ),
            ),
            Arguments.of(
                "novel",
                NovelReaderProvider(
                    viewModel = mockk(relaxed = true),
                    novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
                    fontManager = mockk(),
                    imageRequests = mockk(),
                    titleWords = EnglishChapterTitleWords,
                ),
            ),
        )
    }
}

/** One content type's provider, with that reader's own progress look set. */
interface ReaderProgressLookProbe {
    fun provider(look: ReaderProgressLook): ReaderProvider
}

class MangaProgressLookProbe : ReaderProgressLookProbe {

    override fun toString() = "manga"

    override fun provider(look: ReaderProgressLook): ReaderProvider = MangaReaderProvider(
        viewModel = mockk(relaxed = true),
        readerPreferences = ReaderPreferences(EmittingPreferenceStore()).apply {
            progressSize.set(look.size)
            progressBacked.set(look.isBacked)
        },
        downloadManager = mockk(relaxed = true),
        titleWords = EnglishChapterTitleWords,
    )
}

class NovelProgressLookProbe : ReaderProgressLookProbe {

    override fun toString() = "novel"

    override fun provider(look: ReaderProgressLook): ReaderProvider = NovelReaderProvider(
        viewModel = mockk(relaxed = true),
        novelPreferences = NovelPreferences(EmittingPreferenceStore()).apply {
            readerProgressSize().set(look.size)
            readerProgressBacked().set(look.isBacked)
        },
        fontManager = mockk(),
        imageRequests = mockk(),
        titleWords = EnglishChapterTitleWords,
    )
}

/** One content type's provider, built so that its stored flag and its default are told apart. */
interface ReaderOrientationProbe {

    /**
     * [stored] is the entry's own flag, [default] the value this type would resolve a 0 to. They are
     * always different, so a provider that resolved would answer [default] and fail.
     */
    fun provider(stored: Int, default: Int): ReaderProvider
}

class MangaOrientationProbe : ReaderOrientationProbe {

    override fun toString() = "manga"

    override fun provider(stored: Int, default: Int): ReaderProvider {
        val manga = Manga.create().copy(id = 1L, source = 1L, url = "/1", viewerFlags = stored.toLong())
        return MangaReaderProvider(
            viewModel = mockk(relaxed = true) {
                every { state } returns MutableStateFlow(ReaderViewModel.State(manga = manga))
            },
            // Emitting, because the resolved answer combines the default's changes, which InMemory's never yield.
            readerPreferences = ReaderPreferences(EmittingPreferenceStore()).apply {
                defaultOrientationType.set(default)
            },
            downloadManager = mockk(relaxed = true),
            titleWords = EnglishChapterTitleWords,
        )
    }
}

class NovelOrientationProbe : ReaderOrientationProbe {

    override fun toString() = "novel"

    // The settings object is stubbed rather than built: it carries 28 fields and only the entry's own
    // flag decides the rule, so a mock states which one the provider is required to read.
    override fun provider(stored: Int, default: Int): ReaderProvider =
        NovelReaderProvider(
            viewModel = mockk(relaxed = true) {
                every { settings } returns MutableStateFlow(
                    mockk<NovelReaderSettings>(relaxed = true) { every { orientation } returns stored },
                )
            },
            novelPreferences = NovelPreferences(EmittingPreferenceStore()).apply {
                readerDefaultOrientation().set(default)
            },
            fontManager = mockk(),
            imageRequests = mockk(),
            titleWords = EnglishChapterTitleWords,
        )
}

/** One content type's provider showing one chapter, with that reader's own title format set. */
interface ReaderChapterTitleProbe {
    val entry: EntryId

    fun provider(format: ChapterTitleFormat, name: String, number: Double): ReaderProvider
}

class MangaChapterTitleProbe : ReaderChapterTitleProbe {

    override val entry = EntryId.Manga(1L)

    override fun toString() = "manga"

    override fun provider(format: ChapterTitleFormat, name: String, number: Double): ReaderProvider {
        val chapter = Chapter.create().copy(id = 1L, mangaId = 1L, name = name, chapterNumber = number)
        val state = ReaderViewModel.State(
            manga = Manga.create().copy(id = 1L, title = "Series"),
            viewerChapters = ViewerChapters(ReaderChapter(chapter), null, null),
        )
        val preferences = ReaderPreferences(EmittingPreferenceStore()).apply { chapterTitleFormat.set(format) }
        return MangaReaderProvider(
            viewModel = mockk(relaxed = true) { every { this@mockk.state } returns MutableStateFlow(state) },
            readerPreferences = preferences,
            downloadManager = mockk(relaxed = true),
            titleWords = EnglishChapterTitleWords,
        )
    }
}

class NovelChapterTitleProbe : ReaderChapterTitleProbe {

    override val entry = EntryId.Novel(1L)

    override fun toString() = "novel"

    override fun provider(format: ChapterTitleFormat, name: String, number: Double): ReaderProvider {
        val chapter = mockk<NovelReaderViewModel.LoadedChapter> {
            every { title } returns name
            every { chapterNumber } returns number
        }
        val preferences = NovelPreferences(EmittingPreferenceStore()).apply { readerChapterTitleFormat().set(format) }
        return NovelReaderProvider(
            viewModel = mockk(relaxed = true) {
                every { novelId } returns 1L
                every { entryTitle } returns MutableStateFlow("Series")
                every { this@mockk.chapter } returns MutableStateFlow(chapter)
            },
            novelPreferences = preferences,
            fontManager = mockk(),
            imageRequests = mockk(),
            titleWords = EnglishChapterTitleWords,
        )
    }
}

/**
 * One content type's provider open on series 1, merged with series 2, showing a chapter series 2 owns.
 * Only the owner answers [ownerRecorded]; the opened series answers the opposite, so a provider asking
 * the wrong one fails.
 */
interface ReaderChapterLengthProbe {
    fun provider(ownerRecorded: Boolean): ReaderProvider
}

class MangaChapterLengthProbe : ReaderChapterLengthProbe {

    override fun toString() = "manga"

    override fun provider(ownerRecorded: Boolean): ReaderProvider {
        val chapter = Chapter.create().copy(id = 5L, mangaId = 2L)
        val state = ReaderViewModel.State(
            manga = Manga.create().copy(id = 1L),
            viewerChapters = ViewerChapters(ReaderChapter(chapter), null, null),
            position = ReaderPosition(5L, ChapterProgress.Pages(0, 10)),
        )
        return MangaReaderProvider(
            viewModel = mockk(relaxed = true) {
                every { this@mockk.state } returns MutableStateFlow(state)
                every { seriesIds } returns setOf(1L, 2L)
                coEvery { isRecorded(2L) } returns ownerRecorded
                coEvery { isRecorded(1L) } returns !ownerRecorded
            },
            readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
            downloadManager = mockk(relaxed = true),
            titleWords = EnglishChapterTitleWords,
        )
    }
}

class NovelChapterLengthProbe : ReaderChapterLengthProbe {

    override fun toString() = "novel"

    override fun provider(ownerRecorded: Boolean): ReaderProvider {
        val chapter = mockk<NovelReaderViewModel.LoadedChapter> {
            every { chapterId } returns 5L
            every { novelId } returns 2L
            every { html } returns "<p>one two three</p>"
        }
        return NovelReaderProvider(
            viewModel = mockk(relaxed = true) {
                every { novelId } returns 1L
                every { memberIds } returns listOf(1L, 2L)
                every { this@mockk.chapter } returns MutableStateFlow(chapter)
                coEvery { isRecorded(2L) } returns ownerRecorded
                coEvery { isRecorded(1L) } returns !ownerRecorded
            },
            novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
            fontManager = mockk(),
            imageRequests = mockk(),
            titleWords = EnglishChapterTitleWords,
        )
    }
}

/** One content type's provider, with that reader's own bar fill set, or left alone when null. */
interface ReaderBarFillProbe {
    fun provider(filled: Boolean?): ReaderProvider
}

class MangaBarFillProbe : ReaderBarFillProbe {

    override fun toString() = "manga"

    override fun provider(filled: Boolean?): ReaderProvider = MangaReaderProvider(
        viewModel = mockk(relaxed = true),
        readerPreferences = ReaderPreferences(EmittingPreferenceStore()).apply {
            filled?.let(readerBottomButtonsFilled::set)
        },
        downloadManager = mockk(relaxed = true),
        titleWords = EnglishChapterTitleWords,
    )
}

class NovelBarFillProbe : ReaderBarFillProbe {

    override fun toString() = "novel"

    override fun provider(filled: Boolean?): ReaderProvider = NovelReaderProvider(
        viewModel = mockk(relaxed = true),
        novelPreferences = NovelPreferences(EmittingPreferenceStore()).apply {
            filled?.let(readerBottomButtonsFilled()::set)
        },
        fontManager = mockk(),
        imageRequests = mockk(),
        titleWords = EnglishChapterTitleWords,
    )
}
