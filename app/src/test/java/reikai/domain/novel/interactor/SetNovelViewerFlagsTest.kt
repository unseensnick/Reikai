package reikai.domain.novel.interactor

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelRenderingMode
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.readerOrientation
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * Each of a novel's own reader choices, written to the database and read back the way the reader reads
 * it, with the other two already set, so a write that clears or keeps the wrong bits reads back wrong.
 */
class SetNovelViewerFlagsTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: NovelRepository

    @BeforeEach
    fun setUp() = runTest {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        repository = NovelRepositoryImpl(DatabaseBindings.providesDatabase(driver))
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a reading mode keeps the novel's rotation and chapter display`() = runTest {
        val id = novel(ReaderOrientation.LOCKED_LANDSCAPE, NovelRenderingMode.WEBVIEW, null)

        SetNovelViewerFlags(repository).awaitSetPageLayout(id, NovelPageLayout.PAGED_RTL)

        stored(id) shouldBe
            Triple(ReaderOrientation.LOCKED_LANDSCAPE, NovelRenderingMode.WEBVIEW, NovelPageLayout.PAGED_RTL)
    }

    @Test
    fun `a chapter display keeps the novel's rotation and reading mode`() = runTest {
        val id = novel(ReaderOrientation.LOCKED_LANDSCAPE, NovelRenderingMode.WEBVIEW, NovelPageLayout.PAGED_LTR)

        SetNovelViewerFlags(repository).awaitSetRenderingMode(id, NovelRenderingMode.NATIVE)

        stored(id) shouldBe
            Triple(ReaderOrientation.LOCKED_LANDSCAPE, NovelRenderingMode.NATIVE, NovelPageLayout.PAGED_LTR)
    }

    @Test
    fun `a rotation keeps the novel's chapter display and reading mode`() = runTest {
        val id = novel(ReaderOrientation.PORTRAIT, NovelRenderingMode.WEBVIEW, NovelPageLayout.PAGED_RTL)

        SetNovelViewerFlags(repository).awaitSetOrientation(id, ReaderOrientation.LOCKED_LANDSCAPE.flagValue.toLong())

        stored(id) shouldBe
            Triple(ReaderOrientation.LOCKED_LANDSCAPE, NovelRenderingMode.WEBVIEW, NovelPageLayout.PAGED_RTL)
    }

    /** Two quick taps on different rows of the sheet, each written from its own launch. */
    @Test
    fun `two choices written at once both stay`() = runTest {
        val id = novel(ReaderOrientation.DEFAULT, null, null)
        val setFlags = SetNovelViewerFlags(SuspendingReads(repository))

        launch { setFlags.awaitSetPageLayout(id, NovelPageLayout.PAGED_RTL) }
        launch { setFlags.awaitSetRenderingMode(id, NovelRenderingMode.WEBVIEW) }
        testScheduler.advanceUntilIdle()

        stored(id) shouldBe
            Triple(ReaderOrientation.DEFAULT, NovelRenderingMode.WEBVIEW, NovelPageLayout.PAGED_RTL)
    }

    private suspend fun novel(
        orientation: ReaderOrientation,
        mode: NovelRenderingMode?,
        layout: NovelPageLayout?,
    ): Long {
        val flags = orientation.flagValue.toLong() or NovelRenderingMode.flagOf(mode) or NovelPageLayout.flagOf(layout)
        val novel = Novel.create().copy(source = "src", url = "novel", title = "Novel", viewerFlags = flags)
        return repository.insert(novel)!!
    }

    private suspend fun stored(id: Long): Triple<ReaderOrientation, NovelRenderingMode?, NovelPageLayout?> {
        val flags = repository.getById(id)!!.viewerFlags
        return Triple(
            ReaderOrientation.fromPreference(Novel.create().copy(viewerFlags = flags).readerOrientation.toInt()),
            NovelRenderingMode.ofFlags(flags),
            NovelPageLayout.ofFlags(flags),
        )
    }

    /** A read that lets another write run before the caller acts on it, as a real launch can. */
    private class SuspendingReads(private val real: NovelRepository) : NovelRepository by real {
        override suspend fun getById(id: Long): Novel? = real.getById(id).also { yield() }
    }
}
