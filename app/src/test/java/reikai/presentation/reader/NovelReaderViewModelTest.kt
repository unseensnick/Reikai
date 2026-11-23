package reikai.presentation.reader

import androidx.lifecycle.SavedStateHandle
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.util.storage.DiskUtil
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.chapter.hiddenChapterKey
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.reader.ChapterProgress
import reikai.novel.content.NovelCodeSnippet
import reikai.novel.content.NovelSnippets
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The novel reader's session rules that only show once a real model is driven, over [NovelReaderViewModelHarness]. */
class NovelReaderViewModelTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    private fun readerTest(block: suspend TestScope.(NovelReaderViewModelHarness) -> Unit) = runTest {
        NovelReaderViewModelHarness.create(testScheduler).use { block(it) }
    }

    /** Reports reach the next chapter by crossing the threshold, which a threshold lowered below the
     *  reader's place never does, so only the setting changing can bring it in. */
    @Test
    fun `lowering Add the next chapter at below the reader's place adds the next chapter`() = readerTest { harness ->
        val source = harness.source("src")
        val novel = harness.novel(source)
        val opened = harness.chapter(novel, 1.0, progressPercent = 50)
        val next = harness.chapter(novel, 2.0)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()

        harness.novelPreferences.readerAutoLoadNextAt().set(30)
        advanceUntilIdle()

        model.window.value.chapters.map { it.chapterId } shouldBe listOf(opened.id, next.id)
    }

    /** A novel with no reading mode of its own reads in whatever the setting is now, not what it was at open. */
    @Test
    fun `a novel on Default follows the reading-mode setting as it changes`() = readerTest { harness ->
        harness.novelPreferences.readerPageLayout().set(NovelPageLayout.SCROLL)
        val novel = harness.novel(harness.source("src"))
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()

        harness.novelPreferences.readerPageLayout().set(NovelPageLayout.PAGED_RTL)
        advanceUntilIdle()

        model.effectivePageLayout.value shouldBe NovelPageLayout.PAGED_RTL
    }

    @Test
    fun `a novel's own reading mode wins over a changing setting`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()
        model.setPageLayout(NovelPageLayout.PAGED_LTR)

        harness.novelPreferences.readerPageLayout().set(NovelPageLayout.PAGED_RTL)
        advanceUntilIdle()

        model.effectivePageLayout.value shouldBe NovelPageLayout.PAGED_LTR
    }

    /** A source-scoped session lists one member's chapters only, and the group is still a merged one. */
    @Test
    fun `a merged novel opened from one source names that source on every chapter`() = readerTest { harness ->
        val first = harness.novel(harness.source("alpha", "Alpha Source"))
        val second = harness.novel(harness.source("beta", "Beta Source"))
        harness.chapter(first, 1.0)
        val opened = harness.chapter(second, 1.0)
        harness.chapter(second, 2.0)
        harness.merge(first, second)
        val model = harness.open(second, opened.id, sourceScoped = true)
        advanceUntilIdle()

        model.chapterRows(EnglishChapterTitleWords).first().map { it.subtitle }.distinct() shouldBe
            listOf("Beta Source")
    }

    /** The opened chapter is on disk, the next is not, the one after is. */
    @Test
    fun `with Downloaded only on the reader steps over a chapter not on disk`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        harness.chapter(novel, 2.0)
        val third = harness.chapter(novel, 3.0)
        harness.download(opened, "one")
        harness.download(third, "three")
        harness.downloadedOnly.set(true)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()

        model.chapterRows(EnglishChapterTitleWords).first().map { it.id } shouldBe listOf(opened.id, third.id)
    }

    /** Hidden on the details list, so the reader pages past it as the resume does. */
    @Test
    fun `the reader steps over a chapter the user hid`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val hidden = harness.chapter(novel, 2.0)
        val third = harness.chapter(novel, 3.0)
        harness.novelPreferences.hiddenChapters().set(setOf(hiddenChapterKey("src", hidden.url)))
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()

        model.chapterRows(EnglishChapterTitleWords).first().map { it.id } shouldBe listOf(opened.id, third.id)
    }

    /** The browser may already be closed when the save lands, so the reader hears of it from the fetcher. */
    @Test
    fun `a page saved as the open chapter in the browser is shown`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()
        harness.download(opened, "<p>From the page</p>")

        harness.pageSaves.emit(opened.id)
        advanceUntilIdle()

        model.chapter.value?.html.orEmpty() shouldContain "From the page"
    }

    /** A paged WebView holds its opening veil on this, since the chapter's arrival above moves the page. */
    @Test
    fun `the window says the chapter before is joining while it loads`() = readerTest { harness ->
        val source = harness.source("src")
        val novel = harness.novel(source)
        val previous = harness.chapter(novel, 1.0)
        val opened = harness.chapter(novel, 2.0)
        source.holding[previous.url] = CompletableDeferred()
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()

        model.window.first { it.chapters.any { chapter -> chapter.chapterId == opened.id } }.previousJoining shouldBe
            true
    }

    @Test
    fun `the window says the chapter before has stopped joining once it is in`() = readerTest { harness ->
        val source = harness.source("src")
        val novel = harness.novel(source)
        val previous = harness.chapter(novel, 1.0)
        val opened = harness.chapter(novel, 2.0)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()

        model.window.first { it.chapters.any { chapter -> chapter.chapterId == previous.id } }.previousJoining shouldBe
            false
    }

    /** A chapter that will not load never joins, so a renderer waiting on it would wait for nothing. */
    @Test
    fun `the window says the chapter before is not joining once it failed`() = readerTest { harness ->
        val source = harness.source("src")
        val novel = harness.novel(source)
        val previous = harness.chapter(novel, 1.0)
        val opened = harness.chapter(novel, 2.0)
        source.failing += previous.url
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()

        model.window.first { it.failedPrevious != null }.previousJoining shouldBe false
    }

    /** The browser's page is how a chapter the source cannot serve gets read, so its save opens it. */
    @Test
    fun `a page saved for a chapter that failed to open opens it`() = readerTest { harness ->
        val source = harness.source("src")
        val novel = harness.novel(source)
        val opened = harness.chapter(novel, 1.0)
        val failed = harness.chapter(novel, 2.0)
        source.failing += failed.url
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()
        model.open(failed.id)
        advanceUntilIdle()
        harness.download(failed, "<p>Saved from the page</p>")

        harness.pageSaves.emit(failed.id)
        advanceUntilIdle()

        model.chapter.value?.html.orEmpty() shouldContain "Saved from the page"
    }

    /** Under the switch the reader's own list holds nothing left to fetch, so download-ahead walks past it. */
    @Test
    fun `with Downloaded only on download-ahead still queues the next chapter not on disk`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val second = harness.chapter(novel, 2.0)
        harness.download(opened, "one")
        harness.downloadedOnly.set(true)
        harness.novelPreferences.autoDownloadWhileReading().set(1)
        harness.open(novel, opened.id)
        advanceUntilIdle()

        coVerify {
            harness.downloadManager.downloadChapters(
                match { chapters ->
                    chapters.map { it.id } ==
                        listOf(second.id)
                },
            )
        }
    }

    /** Incognito keeps history out, not downloads, as Mihon's reader downloads ahead in it too. */
    @Test
    fun `in incognito download-ahead still queues the next chapter`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val second = harness.chapter(novel, 2.0)
        harness.incognito.set(true)
        harness.novelPreferences.autoDownloadWhileReading().set(1)
        harness.open(novel, opened.id)
        advanceUntilIdle()

        coVerify {
            harness.downloadManager.downloadChapters(
                match { chapters ->
                    chapters.map { it.id } ==
                        listOf(second.id)
                },
            )
        }
    }

    /** The window holds the chapter above the open one, so its position arrives while the open
     *  chapter, from the public member, is still the current one. */
    @Test
    fun `a seamless window saves no position for a neighbour from an incognito source`() = readerTest { harness ->
        val public = harness.source("alpha")
        val private = harness.source("beta")
        val host = harness.novel(public)
        val member = harness.novel(private)
        val previous = harness.chapter(member, 1.0)
        val opened = harness.chapter(host, 2.0)
        harness.merge(host, member)
        harness.incognito(private)
        val model = harness.open(host, opened.id)
        advanceUntilIdle()
        model.rendererLanded(model.window.value.generation)

        // An earlier chapter's first report is where the landing put the reader; a second that moved is theirs.
        model.saveProgress(previous.id, ChapterProgress.Percent.ofWhole(40))
        model.saveProgress(previous.id, ChapterProgress.Percent.ofWhole(45))
        advanceUntilIdle()

        harness.progressOf(previous) shouldBe 0L
    }

    /** Past the mark-read threshold every scroll frame reports a new place, and each written at once
     *  would wake every screen watching the chapter list. */
    @Test
    fun `scrolling on past a chapter's finish writes the finish at once and holds the rest`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()
        model.rendererLanded(model.window.value.generation)
        model.reportProgress(opened.id, ChapterProgress.Percent.ofWhole(50))
        model.reportProgress(opened.id, ChapterProgress.Percent.ofWhole(98))
        runCurrent()

        model.reportProgress(opened.id, ChapterProgress.Percent.ofWhole(99))
        runCurrent()

        harness.progressOf(opened) shouldBe 9_800L
    }

    /** Reading the novel's own renderer is no change to it, so the opening chapter is not rendered twice. */
    @Test
    fun `a novel kept on its own renderer opens its first chapter once`() = readerTest { harness ->
        // Immediate, as the device's main thread is, so the model's collectors start before its first read.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val novel = harness.novel(harness.source("src"))
        harness.updateNovel(novel) { viewerFlags = NovelRenderingMode.flagOf(NovelRenderingMode.WEBVIEW) }
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)

        advanceUntilIdle()

        model.window.value.generation shouldBe 1
    }

    /** A chapter is finished once a session, and finishing is what reaches the trackers, so unmarking frees it. */
    @Test
    fun `a chapter unmarked from the sheet is finished again when read to its end`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        harness.chapter(novel, 2.0)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()
        model.rendererLanded(model.window.value.generation)
        model.saveProgress(opened.id, ChapterProgress.Percent.ofWhole(40))
        model.saveProgress(opened.id, ChapterProgress.Percent.END)
        advanceUntilIdle()
        model.setChapterRead(listOf(opened.id), read = false)
        advanceUntilIdle()

        model.saveProgress(opened.id, ChapterProgress.Percent.ofWhole(50))
        model.saveProgress(opened.id, ChapterProgress.Percent.END)
        advanceUntilIdle()

        harness.isRead(opened) shouldBe true
    }

    /** Incognito keeps the position out of the database, so it reopens from the host's state, as manga's page does. */
    @Test
    fun `an incognito chapter reopens after a process kill where it was`() = readerTest { harness ->
        harness.incognito.set(true)
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val savedState = SavedStateHandle()
        val model = harness.open(novel, opened.id, savedState = savedState)
        advanceUntilIdle()
        model.rendererLanded(model.window.value.generation)
        model.saveProgress(opened.id, ChapterProgress.Percent.ofWhole(40))
        model.saveProgress(opened.id, ChapterProgress.Percent.ofWhole(60))
        advanceUntilIdle()

        val restored = harness.open(novel, opened.id, savedState = savedState)
        advanceUntilIdle()

        restored.chapter.value?.progress shouldBe ChapterProgress.Percent.ofWhole(60)
    }

    /** The host's state is a snapshot taken when the reader stopped, so speech playing on behind it moves only the database. */
    @Test
    fun `a recorded chapter reopens after a process kill where the database has it`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val savedState = SavedStateHandle()
        val model = harness.open(novel, opened.id, savedState = savedState)
        advanceUntilIdle()
        model.rendererLanded(model.window.value.generation)
        model.saveProgress(opened.id, ChapterProgress.Percent.ofWhole(40))
        advanceUntilIdle()
        val snapshot = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) })
        model.saveSpokenPosition(opened.id, 0.7)
        advanceUntilIdle()

        val restored = harness.open(novel, opened.id, savedState = snapshot)
        advanceUntilIdle()

        restored.chapter.value?.progress shouldBe ChapterProgress.Percent.ofWhole(70)
    }

    /** Read aloud opens the next chapter behind the snapshot, and History is what holds where it got to. */
    @Test
    fun `a recorded novel reopens after a process kill on the chapter opened while away`() =
        readTimeTest { harness, clock ->
            val novel = harness.novel(harness.source("src"))
            val opened = harness.chapter(novel, 1.0)
            val next = harness.chapter(novel, 2.0, progressPercent = 50)
            val savedState = SavedStateHandle()
            val model = harness.open(novel, opened.id, savedState = savedState)
            model.awaitIdleOn(opened)
            val snapshot = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) })
            model.saveHistory()
            advanceUntilIdle()
            clock.advance(10.minutes)
            model.nextChapter()
            model.awaitIdleOn(next)
            advanceUntilIdle()

            val restored = harness.open(novel, opened.id, savedState = snapshot)
            restored.loadState.first { it == ReaderLoadState.Idle }

            restored.chapter.value?.chapterId shouldBe next.id
        }

    @Test
    fun `a novel opened on a chapter opens on it whatever History read last`() = readTimeTest { harness, clock ->
        val novel = harness.novel(harness.source("src"))
        val first = harness.chapter(novel, 1.0)
        val later = harness.chapter(novel, 2.0)
        val model = harness.open(novel, later.id)
        model.awaitIdleOn(later)
        clock.advance(10.minutes)
        model.saveHistory()
        advanceUntilIdle()
        harness.leave()

        val reopened = harness.open(novel, first.id)
        reopened.loadState.first { it == ReaderLoadState.Idle }

        reopened.chapter.value?.chapterId shouldBe first.id
    }

    /** Incognito stamps no History, so the snapshot is the only record of the chapter being read. */
    @Test
    fun `an incognito novel reopens after a process kill on the snapshot's chapter`() =
        readTimeTest { harness, clock ->
            val novel = harness.novel(harness.source("src"))
            val first = harness.chapter(novel, 1.0)
            val opened = harness.chapter(novel, 2.0)
            val model = harness.open(novel, first.id)
            model.awaitIdleOn(first)
            model.saveHistory()
            advanceUntilIdle()
            harness.leave()
            harness.incognito.set(true)
            clock.advance(10.minutes)
            val savedState = SavedStateHandle()
            val private = harness.open(novel, opened.id, savedState = savedState)
            private.awaitIdleOn(opened)

            val restored = harness.open(novel, first.id, savedState = savedState)
            restored.loadState.first { it == ReaderLoadState.Idle }

            restored.chapter.value?.chapterId shouldBe opened.id
        }

    @Test
    fun `an incognito chapter reopens after a process kill at the spoken paragraph`() = readerTest { harness ->
        harness.incognito.set(true)
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val savedState = SavedStateHandle()
        val model = harness.open(novel, opened.id, savedState = savedState)
        advanceUntilIdle()
        model.rendererLanded(model.window.value.generation)
        model.saveProgress(opened.id, ChapterProgress.Percent.ofWhole(40))
        model.saveSpokenPosition(opened.id, 0.7)
        advanceUntilIdle()

        val restored = harness.open(novel, opened.id, savedState = savedState)
        advanceUntilIdle()

        restored.chapter.value?.progress shouldBe ChapterProgress.Percent.ofWhole(70)
    }

    @Test
    fun `an incognito chapter spoken to its end reopens after a process kill at the end`() = readerTest { harness ->
        harness.incognito.set(true)
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        harness.chapter(novel, 2.0)
        val savedState = SavedStateHandle()
        val model = harness.open(novel, opened.id, savedState = savedState)
        advanceUntilIdle()
        model.rendererLanded(model.window.value.generation)
        model.saveProgress(opened.id, ChapterProgress.Percent.ofWhole(40))
        model.finishSpokenChapter(opened.id)
        advanceUntilIdle()

        val restored = harness.open(novel, opened.id, savedState = savedState)
        advanceUntilIdle()

        restored.chapter.value?.progress shouldBe ChapterProgress.Percent.END
    }

    @Test
    fun `an incognito renderer rebuilt on the screen's place reopens at the spoken paragraph`() =
        readerTest { harness ->
            harness.incognito.set(true)
            harness.novelPreferences.readerTtsKeepInView().set(false)
            val novel = harness.novel(harness.source("src"))
            val chapter = harness.chapter(novel, 1.0)
            val savedState = SavedStateHandle()
            val model = harness.open(novel, chapter.id, savedState = savedState)
            advanceUntilIdle()
            model.rendererLanded(model.window.value.generation)
            model.saveProgress(chapter.id, ChapterProgress.Percent.ofWhole(20))
            model.saveSpokenPosition(chapter.id, 0.4)
            advanceUntilIdle()
            model.rendererLanded(model.window.value.generation)
            model.saveProgress(chapter.id, ChapterProgress.Percent.ofWhole(20))
            advanceUntilIdle()

            val restored = harness.open(novel, chapter.id, savedState = savedState)
            advanceUntilIdle()

            restored.chapter.value?.progress shouldBe ChapterProgress.Percent.ofWhole(40)
        }

    /** Mark read on skip is off by default, so the step on to the next chapter marks nothing. */
    @Test
    fun `a chapter spoken to its end is finished`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        harness.chapter(novel, 2.0)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()

        model.finishSpokenChapter(opened.id)
        advanceUntilIdle()

        harness.isRead(opened) shouldBe true
    }

    @Test
    fun `a paragraph saved just before the end does not take the chapter back from it`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()

        model.saveSpokenPosition(opened.id, 0.4)
        model.finishSpokenChapter(opened.id)
        advanceUntilIdle()

        harness.progressOf(opened) shouldBe ChapterProgress.Percent.FULL
    }

    @Test
    fun `the spoken paragraph is saved with keep in view off`() = readerTest { harness ->
        harness.novelPreferences.readerTtsKeepInView().set(false)
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()

        model.saveSpokenPosition(opened.id, 0.4)
        advanceUntilIdle()

        harness.progressOf(opened) shouldBe 4_000L
    }

    /** The screen sits at 20% while speech, with Keep in view off, has saved 40%. */
    private suspend fun TestScope.spokenAwayFromScreen(
        harness: NovelReaderViewModelHarness,
    ): Pair<NovelReaderViewModel, SeededChapter> {
        harness.novelPreferences.readerTtsKeepInView().set(false)
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()
        model.rendererLanded(model.window.value.generation)
        model.saveProgress(opened.id, ChapterProgress.Percent.ofWhole(10))
        model.saveProgress(opened.id, ChapterProgress.Percent.ofWhole(20))
        model.saveSpokenPosition(opened.id, 0.4)
        advanceUntilIdle()
        return model to opened
    }

    @Test
    fun `a renderer rebuilt on the screen's place keeps the spoken paragraph saved`() = readerTest { harness ->
        val (model, chapter) = spokenAwayFromScreen(harness)

        model.rendererLanded(model.window.value.generation)
        model.saveProgress(chapter.id, ChapterProgress.Percent.ofWhole(20))
        advanceUntilIdle()

        harness.progressOf(chapter) shouldBe 4_000L
    }

    @Test
    fun `the reader moving after a rebuild saves their place over the spoken paragraph`() = readerTest { harness ->
        val (model, chapter) = spokenAwayFromScreen(harness)
        model.rendererLanded(model.window.value.generation)
        model.saveProgress(chapter.id, ChapterProgress.Percent.ofWhole(20))

        model.saveProgress(chapter.id, ChapterProgress.Percent.ofWhole(30))
        advanceUntilIdle()

        harness.progressOf(chapter) shouldBe 3_000L
    }

    @Test
    fun `reopening lands on the spoken paragraph`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val model = harness.open(novel, opened.id)
        advanceUntilIdle()
        model.saveSpokenPosition(opened.id, 0.4)
        advanceUntilIdle()

        val reopened = harness.open(novel, opened.id)
        advanceUntilIdle()

        reopened.window.value.chapters.first().progress shouldBe ChapterProgress.Percent(4_000L)
    }

    /** Both members carry the same chapters, so the stitch draws every one from the member that leads. */
    @Test
    fun `a merged novel whose chapters all come from one source still names it`() = readerTest { harness ->
        val first = harness.novel(harness.source("alpha", "Alpha Source"))
        val second = harness.novel(harness.source("beta", "Beta Source"))
        val opened = harness.chapter(first, 1.0)
        harness.chapter(first, 2.0)
        harness.chapter(second, 1.0)
        harness.chapter(second, 2.0)
        harness.merge(first, second)
        val model = harness.open(first, opened.id)
        advanceUntilIdle()

        model.chapterRows(EnglishChapterTitleWords).first().map { it.subtitle }.distinct() shouldBe
            listOf("Alpha Source")
    }

    /** The source reload failed; the setting change is a different open and reads the download. */
    @Test
    fun `a failed reload from the source does not stop a settings change reading the download`() =
        readerTest { harness ->
            val source = harness.source("src")
            val novel = harness.novel(source)
            val first = harness.chapter(novel, 1.0)
            harness.download(first, "<p>From the download</p>")
            val model = harness.open(novel, first.id)
            advanceUntilIdle()
            source.failing += first.url
            model.reloadChapter(fromSource = true)
            advanceUntilIdle()

            harness.novelPreferences.readerForceLowercase().set(true)
            advanceUntilIdle()

            model.loadState.value shouldBe ReaderLoadState.Idle
        }

    /** Seamless off, so no warm refills the dropped cache and only the open reads the chapter. */
    @Test
    fun `a failed reload from the source does not follow the reader back to that chapter`() = readerTest { harness ->
        harness.novelPreferences.readerSeamlessChapters().set(false)
        val source = harness.source("src")
        val novel = harness.novel(source)
        val first = harness.chapter(novel, 1.0)
        val second = harness.chapter(novel, 2.0)
        harness.download(first, "<p>Chapter one on disk</p>")
        harness.download(second, "<p>Chapter two on disk</p>")
        val model = harness.open(novel, first.id)
        advanceUntilIdle()
        source.failing += first.url
        model.reloadChapter(fromSource = true)
        advanceUntilIdle()

        model.nextChapter()
        advanceUntilIdle()
        model.previousChapter()
        advanceUntilIdle()

        model.chapter.value?.html.orEmpty() shouldContain "Chapter one on disk"
    }

    /** The provider maps an emission that can arrive after the session moved to another source's chapter. */
    @Test
    fun `a chapter of another source of a merged novel gets that source's web address`() = readerTest { harness ->
        val first = harness.novel(harness.source("alpha"))
        val second = harness.novel(harness.source("beta"))
        val opened = harness.chapter(first, 1.0)
        val other = harness.chapter(second, 2.0)
        harness.merge(first, second)
        val model = harness.open(first, opened.id)
        advanceUntilIdle()
        val earlier = model.chapter.value!!

        model.open(other.id)
        advanceUntilIdle()

        model.webUrlFor(earlier) shouldBe "https://alpha.example" + opened.url
    }

    /** A chapter read from disk never resolves its source to open, so the web actions resolve it. */
    @Test
    fun `a downloaded chapter gets its source's web address`() = readerTest { harness ->
        val novel = harness.novel(harness.source("alpha"))
        val chapter = harness.chapter(novel, 1.0)
        harness.download(chapter, "<p>Chapter one on disk</p>")
        val model = harness.open(novel, chapter.id)
        advanceUntilIdle()

        model.webUrlFor(model.chapter.value!!) shouldBe "https://alpha.example" + chapter.url
    }

    /** Asked by id, as the failed-chapter actions do, while the session reads the other source's chapter. */
    @Test
    fun `a chapter asked for by id gets its own source's web address`() = readerTest { harness ->
        val first = harness.novel(harness.source("alpha"))
        val second = harness.novel(harness.source("beta"))
        val opened = harness.chapter(first, 1.0)
        val other = harness.chapter(second, 2.0)
        harness.merge(first, second)
        val model = harness.open(first, opened.id)
        advanceUntilIdle()

        model.webUrlOf(other.id) shouldBe "https://beta.example" + other.url
    }

    /** Read before anything is advanced, so it is the seed value a first render would be built with. */
    @Test
    fun `a snippet saved before the reader opens is in its first settings`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val chapter = harness.chapter(novel, 1.0)
        harness.novelPreferences.readerCssSnippets()
            .set(NovelSnippets.encode(listOf(NovelCodeSnippet(title = "t", code = "p { color: red; }"))))

        val model = harness.open(novel, chapter.id)

        model.settings.value.webSnippets.css shouldBe "p { color: red; }"
    }

    @Test
    fun `a setting changed while reading reaches the live settings`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val chapter = harness.chapter(novel, 1.0)
        val model = harness.open(novel, chapter.id)
        advanceUntilIdle()

        harness.novelPreferences.readerTtsKeepInView().set(false)
        advanceUntilIdle()

        model.settings.value.ttsKeepInView shouldBe false
    }

    /** The novel's own orientation is not a preference, so the key comparison cannot see it go unwatched. */
    @Test
    fun `an orientation picked in the reader reaches the live settings`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val chapter = harness.chapter(novel, 1.0)
        val model = harness.open(novel, chapter.id)
        advanceUntilIdle()

        model.setOrientation(ReaderOrientation.LOCKED_LANDSCAPE.flagValue)
        advanceUntilIdle()

        model.settings.value.orientation shouldBe ReaderOrientation.LOCKED_LANDSCAPE.flagValue
    }

    /** The reader sits paused under the in-app browser when its save reloads the chapter. */
    @Test
    fun `a chapter reloaded while the reader is away does not count the time away as read`() =
        readTimeTest { harness, clock ->
            val novel = harness.novel(harness.source("src"))
            val opened = harness.chapter(novel, 1.0)
            val model = harness.open(novel, opened.id)
            model.awaitIdleOn(opened)
            model.saveHistory()
            advanceUntilIdle()
            harness.download(opened, "<p>From the page</p>")
            harness.pageSaves.emit(opened.id)
            model.chapter.first { it?.html.orEmpty().contains("From the page") }
            model.loadState.first { it == ReaderLoadState.Idle }

            clock.advance(1.hours)
            model.restartReadTimer()
            clock.advance(10.minutes)
            model.saveHistory()
            advanceUntilIdle()

            harness.readTimeOf(novel, opened) shouldBe 10.minutes.inWholeMilliseconds
        }

    /** A chapter-text setting reloads the chapter on screen, which is still the same read. */
    @Test
    fun `a chapter reloaded on screen keeps the time read before the reload`() = readTimeTest { harness, clock ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val model = harness.open(novel, opened.id)
        model.awaitIdleOn(opened)
        clock.advance(10.minutes)

        harness.download(opened, "<p>Reloaded</p>")
        model.reloadChapter(fromSource = false)
        model.chapter.first { it?.html.orEmpty().contains("Reloaded") }
        model.loadState.first { it == ReaderLoadState.Idle }
        clock.advance(5.minutes)
        model.saveHistory()
        advanceUntilIdle()

        harness.readTimeOf(novel, opened) shouldBe 15.minutes.inWholeMilliseconds
    }

    @Test
    fun `a chapter opened on screen counts the time it is read`() = readTimeTest { harness, clock ->
        val novel = harness.novel(harness.source("src"))
        val opened = harness.chapter(novel, 1.0)
        val model = harness.open(novel, opened.id)
        model.awaitIdleOn(opened)

        clock.advance(10.minutes)
        model.saveHistory()
        advanceUntilIdle()

        harness.readTimeOf(novel, opened) shouldBe 10.minutes.inWholeMilliseconds
    }

    /** Waits for [chapter] to be the one loaded and its load to have committed. */
    private suspend fun NovelReaderViewModel.awaitIdleOn(chapter: SeededChapter) {
        this.chapter.first { it?.chapterId == chapter.id }
        loadState.first { it == ReaderLoadState.Idle }
    }

    /** [readerTest] with the wall clock the read time is taken from under the test's hand. */
    private fun readTimeTest(block: suspend TestScope.(NovelReaderViewModelHarness, TestClock) -> Unit) =
        readerTest { harness ->
            val clock = TestClock()
            mockkObject(Clock.System)
            try {
                every { Clock.System.now() } answers { clock.now }
                block(harness, clock)
            } finally {
                unmockkObject(Clock.System)
            }
        }

    /**
     * As manga's drops its page images: otherwise the chapter's pictures come straight back from the cache.
     * The download holds its pictures inlined, as a real one does, so only the source's text names them.
     */
    @Test
    fun `a reload from the source drops the chapter's cached pictures`() = readerTest { harness ->
        val source = harness.source("src")
        val novel = harness.novel(source)
        val first = harness.chapter(novel, 1.0)
        harness.download(first, """<p><img src="data:image/png;base64,AAAA"></p>""")
        source.texts[first.url] = """<p><img src="https://a.test/plate.png"></p>"""
        val model = harness.open(novel, first.id)
        advanceUntilIdle()

        model.reloadChapter(fromSource = true)
        advanceUntilIdle()

        verify { harness.pictureCache.evict(match { "https://a.test/plate.png" in it }, any(), source.id) }
    }

    /** A text change while a step loads starts that load over, and the step still marks the chapter it left. */
    @Test
    fun `a step overtaken by a text change still marks the chapter it left read`() = readerTest { harness ->
        harness.novelPreferences.readerMarkReadOnSkip().set(true)
        val source = harness.source("src")
        val novel = harness.novel(source)
        val first = harness.chapter(novel, 1.0)
        val second = harness.chapter(novel, 2.0)
        // The neighbour's warm fails, so the step's own load is the next to ask for it.
        source.failing += second.url
        val model = harness.open(novel, first.id)
        advanceUntilIdle()
        model.awaitIdleOn(first)
        model.chapterNeighbours.first { it.next == second.id }
        source.failing -= second.url
        val gate = CompletableDeferred<Unit>()
        source.holding[second.url] = gate
        source.chaptersAsked.clear()

        model.nextChapter()
        advanceUntilIdle()
        check(second.url in source.chaptersAsked) { "The step never asked for the next chapter" }
        model.setRenderingMode(NovelRenderingMode.WEBVIEW)
        advanceUntilIdle()
        gate.complete(Unit)
        model.awaitIdleOn(second)
        advanceUntilIdle()

        harness.isRead(first) shouldBe true
    }

    /** Only the latest pick matters, as in manga: the older write landing last would restore its choice. */
    @Test
    fun `the later of two quick rotation picks is stored on every source of a merged novel`() =
        readerTest { harness ->
            val first = ReaderOrientation.LOCKED_PORTRAIT.flagValue.toLong()
            val later = ReaderOrientation.REVERSE_PORTRAIT.flagValue.toLong()
            val owner = harness.novel(harness.source("alpha"))
            val sibling = harness.novel(harness.source("beta"))
            harness.merge(owner, sibling)
            harness.slowFlagWrite = { id, flag -> id == sibling && flag == first }
            val model = harness.open(owner, harness.chapter(owner, 1.0).id)
            advanceUntilIdle()

            harness.settleAppWrites {
                model.setOrientation(first.toInt())
                model.setOrientation(later.toInt())
            }

            listOf(owner, sibling).map { harness.viewerFlagsOf(it) and ReaderOrientation.MASK.toLong() } shouldBe
                listOf(later, later)
        }

    @Test
    fun `a chapter's picture set as cover becomes the novel's own cover`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()

        val result = model.setPictureAsCover { byteArrayOf(1, 2, 3) }

        (result to harness.customCovers[novel]?.toList()) shouldBe (SetAsCoverResult.Success to listOf<Byte>(1, 2, 3))
    }

    /** Save pages into separate folders is the manga reader's setting, read by both readers. */
    @Test
    fun `a chapter's picture saves into the novel's own folder when pages save into separate folders`() =
        readerTest { harness ->
            harness.readerPreferences.folderPerManga.set(true)
            val novel = harness.novel(harness.source("src"), title = "A Novel: Part 1")
            val model = harness.open(novel, harness.chapter(novel, 1.0).id)
            advanceUntilIdle()

            model.pictureFolder() shouldBe DiskUtil.buildValidFilename("A Novel: Part 1")
        }

    /** As a manga page's: the cover would go with the entry, so the reader is asked to add it first. */
    @Test
    fun `a picture of a novel outside the library is not made its cover`() = readerTest { harness ->
        val novel = harness.novel(harness.source("src"), inLibrary = false)
        val model = harness.open(novel, harness.chapter(novel, 1.0).id)
        advanceUntilIdle()

        val result = model.setPictureAsCover { byteArrayOf(1) }

        (result to harness.customCovers[novel]) shouldBe (SetAsCoverResult.AddToLibraryFirst to null)
    }

    private class TestClock {
        @Volatile
        var now: Instant = Instant.fromEpochMilliseconds(1_000_000_000_000L)
            private set

        fun advance(by: Duration) {
            now += by
        }
    }
}
