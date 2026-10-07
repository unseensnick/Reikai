package reikai.domain.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.source.interactor.UpdateMangaFromRemote
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.data.novel.NovelStatusCode
import reikai.data.novel.refreshNovelFromSource
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.novel.model.Novel
import reikai.novel.host.ChapterItem
import reikai.novel.host.SourceNovel
import reikai.novel.source.NovelSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * A refresh keeps a stored detail its source sends nothing for, for both types, so a source that stops sending one
 * (or a parse that misses it) never wipes what is known.
 */
class UnsentDetailConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a sent author replaces the stored one`(half: RefreshHalf) = runTest {
        half.refreshed(Sent()).author shouldBe "Sent author"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a blank author keeps the stored one`(half: RefreshHalf) = runTest {
        half.refreshed(Sent(author = " ")).author shouldBe STORED.author
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `a blank description keeps the stored one`(half: RefreshHalf) = runTest {
        half.refreshed(Sent(description = "")).description shouldBe STORED.description
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `no genres keep the stored ones`(half: RefreshHalf) = runTest {
        half.refreshed(Sent(genres = "")).genres shouldBe STORED.genres
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `an unknown status keeps the stored one`(half: RefreshHalf) = runTest {
        half.refreshed(Sent(status = SManga.UNKNOWN)).status shouldBe STORED.status
    }

    companion object {
        val STORED = Details("Stored author", "Stored synopsis", listOf("Stored genre"), SManga.ONGOING.toLong())

        @JvmStatic
        fun halves() = listOf(MangaRefreshHalf(), NovelRefreshHalf())
    }
}

/** What a source sends for an entry; each default is a value the stored entry does not hold. */
data class Sent(
    val author: String? = "Sent author",
    val description: String? = "Sent synopsis",
    val genres: String? = "Sent genre",
    val status: Int = SManga.COMPLETED,
)

data class Details(val author: String?, val description: String?, val genres: List<String>?, val status: Long)

interface RefreshHalf {
    /** Stores an entry holding [UnsentDetailConformanceTest.STORED], refreshes it from a source sending [sent]. */
    suspend fun refreshed(sent: Sent): Details
}

private suspend fun database(): Database {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    Database.Schema.create(driver).await()
    return DatabaseBindings.providesDatabase(driver)
}

class MangaRefreshHalf : RefreshHalf {
    override fun toString() = "manga"

    override suspend fun refreshed(sent: Sent): Details {
        val stored = UnsentDetailConformanceTest.STORED
        val database = database()
        val mangas = MangaRepositoryImpl(database)
        val manga = mangas.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = 1L,
                    url = "/entry",
                    title = "Entry",
                    author = stored.author,
                    description = stored.description,
                    genre = stored.genres,
                    status = stored.status,
                ),
            ),
        ).single()
        val remote = SManga.create().apply {
            url = "/entry"
            title = "Entry"
            author = sent.author
            description = sent.description
            genre = sent.genres
            status = sent.status
            initialized = true
        }
        val source = mockk<Source> {
            every { id } returns 1L
            coEvery { getMangaUpdate(any(), any(), any(), any()) } returns SMangaUpdate(remote, emptyList())
        }
        val update = UpdateMangaFromRemote(
            sourceManager = mockk(),
            chapterRepository = ChapterRepositoryImpl(database),
            mangaRepository = mangas,
            syncChaptersWithSource = mockk { coEvery { await(any(), any(), any(), any(), any()) } returns emptyList() },
            coverCache = mockk(relaxed = true),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            downloadManager = mockk(relaxed = true),
        )

        update(source, manga, fetchDetails = true).getOrThrow()

        return mangas.getMangaById(manga.id).let { Details(it.author, it.description, it.genre, it.status) }
    }
}

class NovelRefreshHalf : RefreshHalf {
    override fun toString() = "novel"

    override suspend fun refreshed(sent: Sent): Details {
        val stored = UnsentDetailConformanceTest.STORED
        val database = database()
        val novels = NovelRepositoryImpl(database)
        val novelId = novels.insert(
            Novel.create().copy(
                source = "src",
                url = "/entry",
                title = "Entry",
                author = stored.author,
                description = stored.description,
                genre = stored.genres,
                status = stored.status,
                favoriteAt = 0L,
            ),
        )!!
        val source = mockk<NovelSource> {
            every { id } returns "src"
            coEvery { parseNovel("/entry") } returns SourceNovel(
                path = "/entry",
                name = "Entry",
                author = sent.author,
                summary = sent.description,
                genres = sent.genres,
                status = NovelStatusCode.toSourceString(sent.status),
                chapters = listOf(ChapterItem("Chapter 1", "/entry/1")),
            )
        }

        refreshNovelFromSource(
            novels.getById(novelId)!!,
            source,
            NovelChapterRepositoryImpl(database),
            novels,
            LibraryPreferences(InMemoryPreferenceStore()),
            NoChapterNumberOverrides,
            coverCache = mockk(relaxed = true),
        )

        return novels.getById(novelId)!!.let { Details(it.author, it.description, it.genre, it.status) }
    }
}
