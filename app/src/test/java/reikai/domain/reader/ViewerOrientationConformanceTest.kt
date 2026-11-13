package reikai.domain.reader

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.novel.interactor.SetNovelViewerFlags
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.readerOrientation
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/**
 * An entry's own reader orientation, written through each type's viewer-flag setter and read back the
 * way its reader reads it. The entry starts in portrait with other bits set, so a write that does not
 * clear the old orientation, or clears more than it, reads back wrong.
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

    /** Sets [orientation] on an entry whose viewer flags are [stored]; answers the orientation its reader
     *  reads afterwards and the flags outside the orientation bits. */
    interface Half {
        suspend fun setOrientation(stored: Long, orientation: ReaderOrientation): Pair<Long, Long>
    }

    companion object {
        private const val OTHER_BITS = 0x3L
        private val STORED = OTHER_BITS or ReaderOrientation.PORTRAIT.flagValue.toLong()
        private val NOT_ORIENTATION = ReaderOrientation.MASK.toLong().inv()

        @JvmStatic
        fun halves(): List<Half> = listOf(
            object : Half {
                override suspend fun setOrientation(stored: Long, orientation: ReaderOrientation): Pair<Long, Long> {
                    val written = slot<MangaUpdate>()
                    val repository = mockk<MangaRepository> {
                        coEvery { getMangaById(1L) } returns Manga.create().copy(id = 1L, viewerFlags = stored)
                        coEvery { update(capture(written)) } returns true
                    }
                    SetMangaViewerFlags(repository).awaitSetOrientation(1L, orientation.flagValue.toLong())
                    val flags = written.captured.viewerFlags!!
                    return Manga.create().copy(viewerFlags = flags).readerOrientation to (flags and NOT_ORIENTATION)
                }

                override fun toString() = "manga"
            },
            object : Half {
                override suspend fun setOrientation(stored: Long, orientation: ReaderOrientation): Pair<Long, Long> {
                    // A real database, since the novel write is one masked update there.
                    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                    Database.Schema.create(driver).await()
                    val repository = NovelRepositoryImpl(DatabaseBindings.providesDatabase(driver))
                    val novel = Novel.create().copy(source = "src", url = "novel", viewerFlags = stored)
                    val id = repository.insert(novel)!!
                    SetNovelViewerFlags(repository).awaitSetOrientation(id, orientation.flagValue.toLong())
                    val flags = repository.getById(id)!!.viewerFlags
                    driver.close()
                    return Novel.create().copy(viewerFlags = flags).readerOrientation to (flags and NOT_ORIENTATION)
                }

                override fun toString() = "novel"
            },
        )
    }
}
