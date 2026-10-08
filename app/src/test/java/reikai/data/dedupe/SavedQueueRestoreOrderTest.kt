package reikai.data.dedupe

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadStore
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mihon.core.migration.Migration
import mihon.core.migration.Migrator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownload
import reikai.novel.download.NovelDownloadStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * Both saved download queues are re-pointed by a migration after the upgrade's dedupe, and both engines restore
 * theirs as they are built, which can be before the migrations finish. So each restore waits for them, then
 * hands back the queue in the order it was saved.
 */
class SavedQueueRestoreOrderTest {

    private val gate = CompletableDeferred<Unit>()

    private val context = mockk<Context> {
        every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
    }

    @AfterEach
    fun releaseMigrator() {
        gate.complete(Unit)
        Migrator.release()
    }

    @ParameterizedTest
    @EnumSource(Store::class)
    fun `a saved queue is not read while a migration is still running`(store: Store) = runTest {
        Migrator.initialize(
            old = 1,
            new = 2,
            migrations = listOf(Migration.of(2f) { gate.await().let { true } }),
            onMigrationComplete = {},
        )

        val restored = async { store.restore(context) }
        runCurrent()
        val readEarly = restored.isCompleted
        gate.complete(Unit)
        restored.await()

        readEarly shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Store::class)
    fun `a saved queue restores in the order it was queued, less a chapter that is gone`(store: Store) = runTest {
        store.saveThenRestore(context, queued = listOf(3L, 1L, 2L), gone = 1L) shouldBe listOf(3L, 2L)
    }

    enum class Store {
        MANGA {
            override suspend fun restore(context: Context) {
                DownloadStore(context, mockk(), Json, mockk(), mockk()).restore()
            }

            override suspend fun saveThenRestore(context: Context, queued: List<Long>, gone: Long): List<Long> {
                val manga = Manga.create().copy(id = 1L, source = 1L)
                val source = mockk<HttpSource>(relaxed = true)
                fun chapter(id: Long) = Chapter.create().copy(id = id, mangaId = manga.id)
                val store = DownloadStore(
                    context,
                    mockk<SourceManager> { coEvery { get(1L) } returns source },
                    Json,
                    mockk<GetManga> { coEvery { await(1L) } returns manga },
                    mockk<GetChapter> {
                        coEvery { await(any<Long>()) } answers
                            { firstArg<Long>().takeIf { it != gone }?.let(::chapter) }
                    },
                )
                store.addAll(queued.map { Download(source, manga, chapter(it)) })
                return store.restore().map { it.chapter.id }
            }
        },
        NOVEL {
            override suspend fun restore(context: Context) {
                NovelDownloadStore(context, mockk()).restore()
            }

            override suspend fun saveThenRestore(context: Context, queued: List<Long>, gone: Long): List<Long> {
                val store = NovelDownloadStore(
                    context,
                    mockk<NovelChapterRepository> {
                        coEvery { getById(any()) } answers { firstArg<Long>().takeIf { it != gone }?.let(::chapter) }
                    },
                )
                store.addAll(queued.map { NovelDownload(novelId = 1L, chapterId = it, url = "/c/$it") })
                return store.restore().map { it.chapterId }
            }

            private fun chapter(id: Long) = NovelChapter(
                id = id, novelId = 1L, url = "/c/$id", name = "Ch $id", read = false, bookmark = false,
                lastTextProgress = 0L, chapterNumber = id.toDouble(), sourceOrder = id, dateFetch = 0L,
                dateUpload = 0L, page = "",
            )
        },
        ;

        abstract suspend fun restore(context: Context)

        /** Saves [queued] in that order, then restores with the chapter [gone] deleted; the chapter ids restored. */
        abstract suspend fun saveThenRestore(context: Context, queued: List<Long>, gone: Long): List<Long>
    }
}
