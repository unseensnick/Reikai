package reikai.domain.entry

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.model.CustomNovelInfo
import tachiyomi.domain.manga.model.CustomMangaInfo

/** Each type runs its own branch of [SetEntryTitle], so every case runs over both. */
class SetEntryTitleTest {

    enum class Type(val id: (Long) -> EntryId) {
        MANGA(EntryId::Manga),
        NOVEL(EntryId::Novel),
    }

    private val savedManga = slot<CustomMangaInfo>()
    private val savedNovel = slot<CustomNovelInfo>()

    // Entry 1 has an override row, entry 2 none.
    private val setTitle = SetEntryTitle(
        getCustomMangaInfo = mockk {
            every { subscribe(1L) } returns flowOf(CustomMangaInfo(1L, title = "Old", author = "A"))
            every { subscribe(2L) } returns flowOf(null)
        },
        setCustomMangaInfo = mockk { coEvery { set(capture(savedManga)) } returns Unit },
        getCustomNovelInfo = mockk {
            every { subscribe(1L) } returns flowOf(CustomNovelInfo(1L, title = "Old", author = "A"))
            every { subscribe(2L) } returns flowOf(null)
        },
        setCustomNovelInfo = mockk { coEvery { set(capture(savedNovel)) } returns Unit },
    )

    /** The stored row's id, title and author, whichever type wrote it. */
    private fun stored(type: Type): Triple<Long, String?, String?> = when (type) {
        Type.MANGA -> savedManga.captured.run { Triple(mangaId, title, author) }
        Type.NOVEL -> savedNovel.captured.run { Triple(novelId, title, author) }
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a new title keeps the entry's other overrides`(type: Type) = runTest {
        setTitle.await(type.id(1L), "New")

        stored(type) shouldBe Triple(1L, "New", "A")
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `it answers the title it had before, for an undo`(type: Type) = runTest {
        setTitle.await(type.id(1L), "New") shouldBe "Old"
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an entry with no overrides yet gets a row holding only the title`(type: Type) = runTest {
        setTitle.await(type.id(2L), "New") shouldBe null

        stored(type) shouldBe Triple(2L, "New", null)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a null title goes back to the source's and keeps the other overrides`(type: Type) = runTest {
        setTitle.await(type.id(1L), null)

        stored(type) shouldBe Triple(1L, null, "A")
    }
}
