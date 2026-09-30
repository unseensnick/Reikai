package reikai.domain.download

import android.app.NotificationManager
import android.content.Context
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadProvider
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * A Delete the user asked for, through both engines' real delete with only the disk faked. The
 * categories kept from removal govern automatic removal alone (MarkReadDeleteConformanceTest pins that
 * side), so a manual delete takes a read chapter in one; a bookmarked chapter still stays unless the
 * user allowed deleting those.
 */
class ManualDeleteConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a manual delete takes a read chapter in a category kept from removal`(half: ManualDeleteHalf) = runTest {
        half.delete(listOf(Target(READ, read = true)), excluded = setOf("5"), categoryIds = listOf(5L)) shouldBe
            setOf(READ)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a manual delete leaves a bookmarked chapter`(half: ManualDeleteHalf) = runTest {
        half.delete(listOf(Target(READ, read = true), Target(BOOKMARKED, bookmarked = true))) shouldBe setOf(READ)
    }

    data class Target(val id: Long, val read: Boolean = false, val bookmarked: Boolean = false)

    companion object {
        const val ENTRY = 1L
        const val READ = 10L
        const val BOOKMARKED = 11L
        val WAIT = 5.seconds

        @JvmStatic
        fun halves() = listOf(MangaManualDeleteHalf(), NovelManualDeleteHalf())

        /** Both deletes run on their engine's own IO scope, so the result is awaited in real time. */
        suspend fun <T> awaitOnRealThreads(deferred: CompletableDeferred<T>): T? =
            withContext(Dispatchers.Default) { withTimeoutOrNull(WAIT) { deferred.await() } }
    }
}

interface ManualDeleteHalf {
    /** Deletes [targets] of an entry filed in [categoryIds] by hand; the ids whose download went. */
    suspend fun delete(
        targets: List<ManualDeleteConformanceTest.Target>,
        excluded: Set<String> = emptySet(),
        categoryIds: List<Long> = emptyList(),
    ): Set<Long>
}

class MangaManualDeleteHalf : ManualDeleteHalf {
    override fun toString() = "manga"

    override suspend fun delete(
        targets: List<ManualDeleteConformanceTest.Target>,
        excluded: Set<String>,
        categoryIds: List<Long>,
    ): Set<Long> {
        val preferences = DownloadPreferences(EmittingPreferenceStore())
        preferences.removeExcludeCategories.set(excluded)
        val manga = Manga.create().copy(id = ManualDeleteConformanceTest.ENTRY, source = 1L)
        val removed = CompletableDeferred<Set<Long>>()
        val cache = mockk<DownloadCache>(relaxed = true) {
            coEvery { removeChapters(any(), any()) } answers
                { removed.complete(firstArg<List<Chapter>>().mapTo(HashSet()) { it.id }) }
        }
        val manager = DownloadManager(
            context = mockk(relaxed = true),
            provider = mockk { every { findChapterDirs(any(), any(), any()) } returns (null to emptyList()) },
            cache = cache,
            getCategories = mockk {
                coEvery { await(manga.id) } returns
                    categoryIds.map { Category(id = it, name = "c$it", order = it, flags = 0L) }
            },
            sourceManager = mockk(),
            downloadPreferences = preferences,
            getManga = mockk(),
            getChapter = mockk(),
            downloader = mockk(relaxed = true) {
                every { isRunning } returns false
                every { queueState } returns MutableStateFlow(emptyList<Download>())
            },
            pendingDeleter = mockk(),
        )

        manager.deleteChapters(
            targets.map {
                Chapter.create().copy(id = it.id, mangaId = manga.id, read = it.read, bookmark = it.bookmarked)
            },
            manga,
            mockk(),
        )

        return ManualDeleteConformanceTest.awaitOnRealThreads(removed).orEmpty()
    }
}

class NovelManualDeleteHalf : ManualDeleteHalf {
    override fun toString() = "novel"

    /** [categoryIds] has nowhere to go: the novel manager holds no category lookup to ask. */
    override suspend fun delete(
        targets: List<ManualDeleteConformanceTest.Target>,
        excluded: Set<String>,
        categoryIds: List<Long>,
    ): Set<Long> {
        val preferences = NovelPreferences(EmittingPreferenceStore())
        preferences.removeExcludeCategories().set(excluded)
        val novel = Novel.create().copy(id = ManualDeleteConformanceTest.ENTRY, source = "src", title = "Novel")
        val removed = ConcurrentHashMap.newKeySet<Long>()
        val done = CompletableDeferred<Unit>()
        val provider = mockk<NovelDownloadProvider>(relaxed = true) {
            every { deleteChapter(any(), any()) } answers { removed += secondArg<NovelChapter>().id }
            // Asked once every chapter has gone, so it marks the delete as finished.
            every { isNovelDirEmpty(any()) } answers { false.also { done.complete(Unit) } }
        }
        val context = mockk<Context>(relaxed = true) {
            every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
            every { getSystemService(NotificationManager::class.java) } returns
                mockk<NotificationManager>(relaxed = true)
        }
        val manager = NovelDownloadManager(
            context = context,
            provider = provider,
            cache = mockk<NovelDownloadCache>(relaxed = true),
            chapterRepo = mockk(relaxed = true),
            novelRepo = mockk<NovelRepository> { coEvery { getById(novel.id) } returns novel },
            sourceManager = mockk(),
            installer = mockk(),
            downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
            sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
            novelPreferences = preferences,
            saver = mockk(),
            securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
            adultChecker = mockk(),
        )

        manager.deleteChapters(targets.map { chapter(it, novel.id) })

        ManualDeleteConformanceTest.awaitOnRealThreads(done)
        return removed
    }

    private fun chapter(target: ManualDeleteConformanceTest.Target, novelId: Long) = NovelChapter(
        id = target.id,
        novelId = novelId,
        url = "u${target.id}",
        name = "Ch ${target.id}",
        read = target.read,
        bookmark = target.bookmarked,
        lastTextProgress = 0L,
        chapterNumber = target.id.toDouble(),
        sourceOrder = target.id,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )
}
