package reikai.presentation.reader

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * Whether the reader's chapter sheet draws a row's download control on a series that is not merged,
 * pinned once over both readers. A source that is gone can start no download, so an idle row of it
 * offers none, as the details list does; a chapter already on disk keeps its control, which deletes.
 */
class ReaderSheetDownloadOfferConformanceTest {

    /** One reader opened on chapter 1 (on disk) of an unmerged series, asked about chapter 2 (not). */
    interface Probe {
        suspend fun offersDownload(scope: TestScope, sourceInstalled: Boolean, onDisk: Boolean): Boolean
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an idle row whose source is gone offers no download`(probe: Probe) = runTest {
        probe.offersDownload(this, sourceInstalled = false, onDisk = false) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a row on disk whose source is gone keeps its control`(probe: Probe) = runTest {
        probe.offersDownload(this, sourceInstalled = false, onDisk = true) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an idle row whose source is installed offers a download`(probe: Probe) = runTest {
        probe.offersDownload(this, sourceInstalled = true, onDisk = false) shouldBe true
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun offersDownload(scope: TestScope, sourceInstalled: Boolean, onDisk: Boolean): Boolean =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = SOURCE, title = "Series")
                harness.chapter(10L, manga, 1.0)
                harness.chapter(20L, manga, 2.0)
                val asked = if (onDisk) 10L else 20L
                harness.open(
                    manga,
                    chapterId = 10L,
                    onDisk = setOf(10L),
                    missingSources = if (sourceInstalled) emptySet() else setOf(SOURCE),
                ) { model, _ ->
                    val provider = MangaReaderProvider(
                        viewModel = model,
                        readerPreferences = ReaderPreferences(InMemoryPreferenceStore()),
                        downloadManager = mockk<DownloadManager>(relaxed = true) {
                            every { queueState } returns MutableStateFlow(emptyList())
                        },
                        titleWords = EnglishChapterTitleWords,
                    )
                    withContext(Dispatchers.Default) { provider.chapterList.rows.first() }
                        .single { it.id == asked }
                        .offersDownload
                }
            }

        private companion object {
            const val SOURCE = 100L
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun offersDownload(scope: TestScope, sourceInstalled: Boolean, onDisk: Boolean): Boolean =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val source = if (sourceInstalled) harness.source("alpha") else FakeNovelSource("gone", "unregistered")
                val novel = harness.novel(source)
                val opened = harness.chapter(novel, 1.0)
                val other = harness.chapter(novel, 2.0)
                harness.download(opened, "<p>On disk</p>")
                val model = harness.open(novel, opened.id)
                scope.advanceUntilIdle()
                val asked = if (onDisk) opened.id else other.id
                model.chapterRows(EnglishChapterTitleWords).first().single { it.id == asked }.offersDownload
            }
    }
}
