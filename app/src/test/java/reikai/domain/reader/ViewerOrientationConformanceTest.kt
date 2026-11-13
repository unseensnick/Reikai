package reikai.domain.reader

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.SetNovelViewerFlags
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.readerOrientation
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * An entry's own reader orientation, written through each type's viewer-flag setter on a real database and
 * read back the way its reader reads it. The entry starts in portrait with other bits set, so a write that
 * does not clear the old orientation, or clears more than it, reads back wrong; and two choices written from
 * separate launches, as two quick taps in the reader's sheet are, must both stay.
 */
class ViewerOrientationConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `the orientation set on an entry is the one its reader reads back`(half: Half) = runTest {
        half.setOrientation(STORED, ReaderOrientation.LOCKED_LANDSCAPE).first shouldBe
            ReaderOrientation.LOCKED_LANDSCAPE.flagValue.toLong()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `setting an orientation keeps the entry's other viewer flags`(half: Half) = runTest {
        half.setOrientation(STORED, ReaderOrientation.LOCKED_LANDSCAPE).second shouldBe OTHER_BITS
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `an orientation and a reading mode written at once both stay`(half: Half) = runTest {
        half.setWithReadingModeAtOnce(this, ReaderOrientation.LOCKED_LANDSCAPE) shouldBe
            (ReaderOrientation.LOCKED_LANDSCAPE.flagValue.toLong() to true)
    }

    /** One content type's viewer-flag setter over a fresh in-memory database. */
    interface Half {
        /** Sets [orientation] on an entry whose viewer flags are [stored]; answers the orientation its reader
         *  reads afterwards and the flags outside the orientation bits. */
        suspend fun setOrientation(stored: Long, orientation: ReaderOrientation): Pair<Long, Long>

        /** Sets [orientation] and a paged reading mode from two launches whose reads interleave; answers the
         *  orientation read back and whether the reading mode stayed. */
        suspend fun setWithReadingModeAtOnce(scope: TestScope, orientation: ReaderOrientation): Pair<Long, Boolean>
    }

    companion object {
        private const val OTHER_BITS = 0x3L
        private val STORED = OTHER_BITS or ReaderOrientation.PORTRAIT.flagValue.toLong()
        private val NOT_ORIENTATION = ReaderOrientation.MASK.toLong().inv()

        private suspend fun database(): Pair<JdbcSqliteDriver, Database> {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            return driver to DatabaseBindings.providesDatabase(driver)
        }

        private suspend fun MangaRepository.manga(flags: Long): Long =
            insertNetworkManga(listOf(Manga.create().copy(source = 1L, url = "manga", viewerFlags = flags)))
                .single().id

        /** A read that lets another write run before the caller acts on it, as a real launch can. */
        private class MangaSuspendingReads(private val real: MangaRepository) : MangaRepository by real {
            override suspend fun getMangaById(id: Long): Manga = real.getMangaById(id).also { yield() }
        }

        private class NovelSuspendingReads(private val real: NovelRepository) : NovelRepository by real {
            override suspend fun getById(id: Long): Novel? = real.getById(id).also { yield() }
        }

        @JvmStatic
        fun halves(): List<Half> = listOf(
            object : Half {
                override suspend fun setOrientation(stored: Long, orientation: ReaderOrientation): Pair<Long, Long> {
                    val (driver, database) = database()
                    val repository = MangaRepositoryImpl(database)
                    val id = repository.manga(stored)
                    SetMangaViewerFlags(repository).awaitSetOrientation(id, orientation.flagValue.toLong())
                    val flags = repository.getMangaById(id).viewerFlags
                    driver.close()
                    return Manga.create().copy(viewerFlags = flags).readerOrientation to (flags and NOT_ORIENTATION)
                }

                override suspend fun setWithReadingModeAtOnce(
                    scope: TestScope,
                    orientation: ReaderOrientation,
                ): Pair<Long, Boolean> {
                    val (driver, database) = database()
                    val repository = MangaRepositoryImpl(database)
                    val id = repository.manga(0L)
                    val setFlags = SetMangaViewerFlags(MangaSuspendingReads(repository))
                    scope.launch { setFlags.awaitSetOrientation(id, orientation.flagValue.toLong()) }
                    scope.launch { setFlags.awaitSetReadingMode(id, ReadingMode.RIGHT_TO_LEFT.flagValue.toLong()) }
                    scope.testScheduler.advanceUntilIdle()
                    val manga = repository.getMangaById(id)
                    driver.close()
                    return manga.readerOrientation to
                        (manga.readingMode == ReadingMode.RIGHT_TO_LEFT.flagValue.toLong())
                }

                override fun toString() = "manga"
            },
            object : Half {
                override suspend fun setOrientation(stored: Long, orientation: ReaderOrientation): Pair<Long, Long> {
                    val (driver, database) = database()
                    val repository = NovelRepositoryImpl(database)
                    val novel = Novel.create().copy(source = "src", url = "novel", viewerFlags = stored)
                    val id = repository.insert(novel)!!
                    SetNovelViewerFlags(repository).awaitSetOrientation(id, orientation.flagValue.toLong())
                    val flags = repository.getById(id)!!.viewerFlags
                    driver.close()
                    return Novel.create().copy(viewerFlags = flags).readerOrientation to (flags and NOT_ORIENTATION)
                }

                override suspend fun setWithReadingModeAtOnce(
                    scope: TestScope,
                    orientation: ReaderOrientation,
                ): Pair<Long, Boolean> {
                    val (driver, database) = database()
                    val repository = NovelRepositoryImpl(database)
                    val id = repository.insert(Novel.create().copy(source = "src", url = "novel"))!!
                    val setFlags = SetNovelViewerFlags(NovelSuspendingReads(repository))
                    scope.launch { setFlags.awaitSetOrientation(id, orientation.flagValue.toLong()) }
                    scope.launch { setFlags.awaitSetPageLayout(id, NovelPageLayout.PAGED_RTL) }
                    scope.testScheduler.advanceUntilIdle()
                    val flags = repository.getById(id)!!.viewerFlags
                    driver.close()
                    return Novel.create().copy(viewerFlags = flags).readerOrientation to
                        (NovelPageLayout.ofFlags(flags) == NovelPageLayout.PAGED_RTL)
                }

                override fun toString() = "novel"
            },
        )
    }
}
