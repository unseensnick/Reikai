package reikai.domain.entry

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.interactor.SetCustomNovelInfo
import reikai.domain.novel.repository.CustomNovelInfoRepository
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository

/** Edit info's Reset all over both content types: every override goes, the custom cover with them. */
class ResetEntryInfoConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("ids")
    fun `reset drops the entry's overrides and no other type's`(id: EntryId) = runTest {
        val reset = Reset()

        reset.kernel.await(id)

        reset.overridesDropped shouldBe listOf(id)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ids")
    fun `reset clears the entry's custom cover`(id: EntryId) = runTest {
        val reset = Reset()

        reset.kernel.await(id)

        reset.coversCleared shouldBe listOf(id)
    }

    companion object {
        @JvmStatic
        fun ids() = listOf(EntryId.Manga(5L), EntryId.Novel(5L))
    }
}

/** The real kernel and setters, recording what reaches each type's override table and the cover kernel. */
private class Reset {
    val overridesDropped = mutableListOf<EntryId>()
    val coversCleared = mutableListOf<EntryId>()

    private val mangaInfo = mockk<CustomMangaInfoRepository> {
        coEvery { delete(any()) } answers { overridesDropped += EntryId.Manga(firstArg()) }
    }

    private val novelInfo = mockk<CustomNovelInfoRepository> {
        coEvery { delete(any()) } answers { overridesDropped += EntryId.Novel(firstArg()) }
    }

    val kernel = ResetEntryInfo(
        setCustomMangaInfo = SetCustomMangaInfo(mangaInfo),
        setCustomNovelInfo = SetCustomNovelInfo(novelInfo),
        clearCustomCover = mockk { coEvery { await(any()) } answers { coversCleared += firstArg<EntryId>() } },
    )
}
