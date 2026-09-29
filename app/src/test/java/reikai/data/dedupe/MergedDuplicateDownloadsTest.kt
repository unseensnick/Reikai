package reikai.data.dedupe

import android.content.Context
import android.content.SharedPreferences
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.DownloadStore
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.dedupe.MergedDuplicate
import reikai.domain.dedupe.MergedDuplicateChapter
import reikai.domain.download.QueuedChapter
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadProvider
import reikai.novel.download.NovelDownloadStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager

/**
 * What a merged-away copy left outside the database under its title (a download folder) or its ids (a queued
 * download), carried to the survivor for both types by one path. Storage is a fake tree whose rename behaves
 * as Android's document provider does: it never overwrites, it picks a free name instead.
 */
class MergedDuplicateDownloadsTest {

    private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())

    private val mangaRoot = FakeDir("downloads", parent = null)
    private val novelRoot = FakeDir("novel_downloads", parent = null)

    private val storageManager = mockk<StorageManager> {
        every { getDownloadsDirectory() } returns mangaRoot.file
        every { getNovelDownloadsDirectory() } returns novelRoot.file
        every { changes } returns MutableSharedFlow()
    }
    private val downloadProvider = DownloadProvider(mockk(), storageManager, libraryPreferences)
    private val novelProvider = NovelDownloadProvider(storageManager, downloadProvider, libraryPreferences)

    private val sourceManager = mockk<SourceManager> {
        coEvery { getOrStub(SOURCE_ID) } returns StubSource(id = SOURCE_ID, lang = "en", name = "Src")
    }
    private val getManga = mockk<GetManga> {
        coEvery { await(SURVIVOR) } returns Manga.create().copy(id = SURVIVOR, source = SOURCE_ID, title = KEPT)
    }
    private val novelRepository = mockk<NovelRepository> {
        coEvery { getById(SURVIVOR) } returns Novel.create().copy(id = SURVIVOR, source = NOVEL_SOURCE, title = KEPT)
    }

    private val existingChapters = mutableSetOf<Long>()
    private val getChapter = mockk<GetChapter> {
        coEvery { await(any<Long>()) } answers { if (firstArg<Long>() in existingChapters) mockk() else null }
    }
    private val novelChapterRepository = mockk<NovelChapterRepository> {
        coEvery { getById(any()) } answers { if (firstArg<Long>() in existingChapters) mockk() else null }
    }

    private val preferences = mutableMapOf<String, SharedPreferences>()
    private val context = mockk<Context> {
        every { getSharedPreferences(any(), any()) } answers
            { preferences.getOrPut(firstArg()) { FakeSharedPreferences() } }
    }
    private val downloadStore = DownloadStore(context, sourceManager, Json, getManga, getChapter)
    private val novelStore = NovelDownloadStore(context, novelChapterRepository)

    private val downloadCache = mockk<DownloadCache>(relaxed = true)
    private val novelCache = mockk<NovelDownloadCache>(relaxed = true)

    private val downloads = MergedDuplicateDownloads(
        context = context,
        downloadProvider = downloadProvider,
        downloadCache = downloadCache,
        downloadStore = downloadStore,
        sourceManager = sourceManager,
        getManga = getManga,
        getChapter = getChapter,
        novelProvider = novelProvider,
        novelCache = novelCache,
        novelRepository = novelRepository,
        novelChapterRepository = novelChapterRepository,
    )

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a merged-away copy's downloads move to the survivor's title when it has none of its own`(type: Type) =
        runTest {
            val chapter = sourceDir(type).dir(OLD).dir("Chapter 1")

            downloads.carry(listOf(duplicate(type, OLD)), emptyList())

            sourceDir(type).child(KEPT)?.findFile("Chapter 1") shouldBeSameInstanceAs chapter.file
        }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `copies that shared a title leave their one folder as it was`(type: Type) = runTest {
        sourceDir(type).dir(KEPT).dir("Chapter 1")

        downloads.carry(listOf(duplicate(type, KEPT)), emptyList())

        sourceDir(type).names() shouldBe listOf(KEPT)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the survivor's own downloads are kept when a merged-away copy has a folder too`(type: Type) = runTest {
        val kept = sourceDir(type).dir(KEPT).dir("Chapter 1")
        sourceDir(type).dir(OLD).dir("Chapter 1")

        downloads.carry(listOf(duplicate(type, OLD)), emptyList())

        sourceDir(type).child(KEPT)?.findFile("Chapter 1") shouldBeSameInstanceAs kept.file
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a merged-away copy's folder is left under its own name when the survivor has one`(type: Type) = runTest {
        sourceDir(type).dir(KEPT).dir("Chapter 1")
        sourceDir(type).dir(OLD).dir("Chapter 2")

        downloads.carry(listOf(duplicate(type, OLD)), emptyList())

        sourceDir(type).names() shouldContainExactlyInAnyOrder listOf(KEPT, OLD)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `the download index is rebuilt once a folder moved`(type: Type) = runTest {
        sourceDir(type).dir(OLD).dir("Chapter 1")

        downloads.carry(listOf(duplicate(type, OLD)), emptyList())

        when (type) {
            Type.MANGA -> verify { downloadCache.invalidateCache() }
            Type.NOVEL -> verify { novelCache.invalidate() }
        }
    }

    /** Only manga looks its source up, and that waits for extensions to load, so it must not happen for nothing. */
    @Test
    fun `a manga whose copies shared a title does not wait for its source`() = runTest {
        downloads.carry(listOf(duplicate(Type.MANGA, KEPT)), emptyList())

        coVerify(exactly = 0) { sourceManager.getOrStub(any()) }
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a queued chapter of a merged-away copy is queued for the survivor`(type: Type) = runTest {
        existingChapters += 10L
        saveQueue(type, QueuedChapter(DISCARDED, 10L, 0))

        downloads.carry(listOf(duplicate(type, OLD)), emptyList())

        savedQueue(type) shouldBe listOf(QueuedChapter(SURVIVOR, 10L, 0))
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a queued chapter merged into the survivor's with the same url follows it`(type: Type) = runTest {
        existingChapters += 20L
        saveQueue(type, QueuedChapter(DISCARDED, 11L, 0))

        downloads.carry(listOf(duplicate(type, OLD)), listOf(MergedDuplicateChapter(type.contentType, 11L, 20L)))

        savedQueue(type) shouldBe listOf(QueuedChapter(SURVIVOR, 20L, 0))
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a queued chapter with no row left is dropped`(type: Type) = runTest {
        saveQueue(type, QueuedChapter(DISCARDED, 12L, 0))

        downloads.carry(listOf(duplicate(type, OLD)), emptyList())

        savedQueue(type) shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a chapter queued on both copies is queued once, where it stood first`(type: Type) = runTest {
        existingChapters += 20L
        saveQueue(type, QueuedChapter(SURVIVOR, 20L, 1), QueuedChapter(DISCARDED, 11L, 0))

        downloads.carry(listOf(duplicate(type, OLD)), listOf(MergedDuplicateChapter(type.contentType, 11L, 20L)))

        savedQueue(type) shouldBe listOf(QueuedChapter(SURVIVOR, 20L, 0))
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a merged-away copy of the other type leaves this queue alone`(type: Type) = runTest {
        existingChapters += 10L
        saveQueue(type, QueuedChapter(DISCARDED, 10L, 0))

        downloads.carry(listOf(duplicate(type.other, OLD)), emptyList())

        savedQueue(type) shouldBe listOf(QueuedChapter(DISCARDED, 10L, 0))
    }

    private fun duplicate(type: Type, discardedTitle: String) =
        MergedDuplicate(type.contentType, DISCARDED, SURVIVOR, discardedTitle)

    private fun sourceDir(type: Type): FakeDir = when (type) {
        Type.MANGA -> mangaRoot.dir("Src (EN)")
        Type.NOVEL -> novelRoot.dir(NOVEL_SOURCE)
    }

    private fun saveQueue(type: Type, vararg rows: QueuedChapter) = when (type) {
        Type.MANGA -> downloadStore.replacePersisted(emptyList(), rows.toList())
        Type.NOVEL -> novelStore.replacePersisted(emptyList(), rows.toList())
    }

    private fun savedQueue(type: Type): List<QueuedChapter> = when (type) {
        Type.MANGA -> downloadStore.persisted()
        Type.NOVEL -> novelStore.persisted()
    }.sortedBy { it.order }

    enum class Type(val contentType: ContentType) {
        MANGA(ContentType.MANGA),
        NOVEL(ContentType.NOVELS),
        ;

        val other get() = if (this == MANGA) NOVEL else MANGA
    }

    /** A directory of the fake tree; [file] is the UniFile the code under test sees. */
    private class FakeDir(name: String, private val parent: FakeDir?) {
        var name = name
            private set
        private val children = linkedMapOf<String, FakeDir>()

        val file: UniFile = mockk {
            every { this@mockk.name } answers { this@FakeDir.name }
            every { isDirectory } returns true
            every { exists() } returns true
            every { findFile(any()) } answers { children[firstArg()]?.file }
            every { listFiles() } answers { children.values.map { it.file }.toTypedArray() }
            every { createDirectory(any()) } answers { dir(firstArg()).file }
            every { renameTo(any()) } answers { rename(firstArg()) }
        }

        fun dir(name: String): FakeDir = children.getOrPut(name) { FakeDir(name, this) }

        fun child(name: String): UniFile? = children[name]?.file

        fun names(): List<String> = children.keys.toList()

        // FileSystemProvider.renameDocument never replaces a sibling: it takes the first free "name (n)"
        private fun rename(requested: String): Boolean {
            val siblings = parent?.children ?: return false
            val target = generateSequence(0) { it + 1 }
                .map { if (it == 0) requested else "$requested ($it)" }
                .first { it !in siblings || siblings[it] === this }
            siblings.remove(name)
            name = target
            siblings[target] = this
            return true
        }
    }

    private companion object {
        const val DISCARDED = 3L
        const val SURVIVOR = 7L
        const val SOURCE_ID = 1L
        const val NOVEL_SOURCE = "src"
        const val OLD = "Old Title"
        const val KEPT = "Kept Title"
    }
}
