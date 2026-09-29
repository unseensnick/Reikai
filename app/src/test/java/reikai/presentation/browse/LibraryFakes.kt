package reikai.presentation.browse

import eu.kanade.domain.manga.interactor.UpdateManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import reikai.domain.category.GetNovelCategories
import reikai.domain.db.PassThroughTransactions
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

fun libraryCategory(id: Long) = Category(id = id, name = "category $id", order = 0L, flags = 0L)

/**
 * A manga library in memory behind the real [MangaLibraryAdder] and [UpdateManga], so a test adds the
 * way the app does and reads back what landed. Every read sees the table as it is now, as the
 * database would, which is what a stale list snapshot is tested against.
 */
class FakeMangaLibrary(userCategories: List<Category> = emptyList(), defaultCategoryId: Int = -1) {

    private val table = MutableStateFlow<Map<Long, Manga>>(emptyMap())
    val rows: Map<Long, Manga> get() = table.value
    val favorites: Flow<List<Manga>> = table.map { rows -> rows.values.filter { it.favorite } }
    val updates = mutableListOf<MangaUpdate>()
    val favoriteWrites = mutableListOf<Long>()
    val filed = mutableMapOf<Long, List<Long>>()
    val chapterDefaultsStamped = mutableSetOf<Long>()
    val trackersBound = mutableSetOf<Long>()

    /** Stores [id] in or out of the library, filed under [categories], and answers the stored row. */
    fun put(id: Long, favorite: Boolean, categories: List<Long> = emptyList()): Manga {
        filed[id] = categories
        return insert(
            Manga.create().copy(
                id = id,
                url = "/$id",
                source = SOURCE_ID,
                favoriteAt = 100L.takeIf {
                    favorite
                },
            ),
        )
    }

    /** Stores [row] as it is, the way a source result reaches the table. */
    fun insert(row: Manga): Manga = row.also { table.update { rows -> rows + (row.id to row) } }

    val libraryPreferences: LibraryPreferences = mockk(relaxed = true) {
        every { defaultCategory } returns mockk { every { get() } returns defaultCategoryId }
    }

    private val repository = mockk<MangaRepository> {
        coEvery { update(any()) } answers {
            val update = firstArg<MangaUpdate>()
            val row = rows[update.id] ?: return@answers false
            updates += update
            if (update.isSet(MangaUpdate::favoriteAt)) {
                favoriteWrites += update.id
                insert(row.copy(favoriteAt = update.favoriteAt))
            }
            true
        }
    }

    private val updateManga =
        UpdateManga(mangaRepository = repository, fetchInterval = mockk(), sourceTracker = mockk(relaxed = true))

    val getCategories: GetCategories = mockk {
        every { subscribe() } returns flowOf(userCategories)
        coEvery { await() } returns userCategories
        coEvery { await(any<Long>()) } answers { filed[firstArg()].orEmpty().map(::libraryCategory) }
    }

    val adder = MangaLibraryAdder(
        sourceManager = mockk(relaxed = true),
        coverCache = mockk(relaxed = true),
        libraryPreferences = libraryPreferences,
        getCategories = getCategories,
        getDuplicateLibraryManga = mockk(relaxed = true),
        getManga = mockk { coEvery { await(any<Long>()) } answers { rows[firstArg()] } },
        setMangaCategories = mockk { coEvery { await(any(), any()) } answers { filed[firstArg()] = secondArg() } },
        setMangaDefaultChapterFlags = mockk {
            coEvery { await(any()) } answers { chapterDefaultsStamped += firstArg<Manga>().id }
        },
        updateManga = updateManga,
        autoBindOnAdd = mockk { every { manga(any(), any()) } answers { trackersBound += firstArg<Manga>().id } },
        mergeManager = mockk(relaxed = true),
        transactions = PassThroughTransactions,
        reikaiLibraryPreferences = mockk { every { categorySortOrder } returns mockk { every { get() } returns 0 } },
        sourceTracker = mockk(relaxed = true),
    )

    companion object {
        const val SOURCE_ID = 99L
    }
}

/** The novel half of [FakeMangaLibrary]: the real [NovelLibraryAdder] and [UpdateNovel] over rows in memory. */
class FakeNovelLibrary(userCategories: List<Category> = emptyList(), defaultCategoryId: Int = -1) {

    val rows = mutableMapOf<Long, Novel>()
    val favoriteWrites = mutableListOf<Long>()
    val filed = mutableMapOf<Long, List<Long>>()

    fun put(id: Long, path: String, favorite: Boolean, categories: List<Long> = emptyList()): Novel {
        val row = Novel.create().copy(id = id, source = SOURCE_ID, url = path, favoriteAt = 100L.takeIf { favorite })
        rows[id] = row
        filed[id] = categories
        return row
    }

    val novelPreferences: NovelPreferences = mockk(relaxed = true) {
        every { defaultNovelCategory() } returns mockk { every { get() } returns defaultCategoryId }
    }

    private val repository = mockk<NovelRepository> {
        coEvery { getById(any()) } answers { rows[firstArg()] }
        coEvery { getByUrlAndSource(any(), any()) } answers {
            rows.values.firstOrNull { it.url == firstArg<String>() && it.source == secondArg<String>() }
        }
        coEvery { insertOrGet(any()) } answers {
            val novel = firstArg<Novel>()
            rows.values.firstOrNull { it.url == novel.url && it.source == novel.source }
                ?: novel.copy(id = (rows.keys.maxOrNull() ?: 0L) + 1).also { rows[it.id] = it }
        }
        coEvery { update(any<NovelUpdate>()) } answers {
            val update = firstArg<NovelUpdate>()
            val row = rows[update.id] ?: return@answers false
            if (update.isSet(NovelUpdate::favoriteAt)) {
                favoriteWrites += update.id
                rows[update.id] = row.copy(favoriteAt = update.favoriteAt)
            }
            true
        }
    }

    val adder = NovelLibraryAdder(
        novelRepository = repository,
        manager = mockk(relaxed = true),
        getNovelCategories = mockk<GetNovelCategories> {
            coEvery { await() } returns userCategories
            coEvery { awaitByNovelId(any()) } answers { filed[firstArg()].orEmpty().map(::libraryCategory) }
        },
        setNovelCategories = mockk { coEvery { await(any(), any()) } answers { filed[firstArg()!!] = secondArg() } },
        updateNovel = UpdateNovel(novelRepository = repository, sourceTracker = mockk(relaxed = true)),
        novelPreferences = novelPreferences,
        mergeManager = mockk(relaxed = true),
        transactions = PassThroughTransactions,
        reikaiLibraryPreferences = mockk { every { categorySortOrder } returns mockk { every { get() } returns 0 } },
        autoBindOnAdd = mockk(relaxed = true),
        removeNovelsFromLibrary = mockk(relaxed = true),
    )

    companion object {
        const val SOURCE_ID = "src"
    }
}
