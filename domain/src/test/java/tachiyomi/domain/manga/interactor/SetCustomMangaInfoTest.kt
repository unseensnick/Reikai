package tachiyomi.domain.manga.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository

class SetCustomMangaInfoTest {

    private class FakeRepository : CustomMangaInfoRepository {
        val stored = mutableMapOf<Long, CustomMangaInfo>()
        val deleted = mutableListOf<Long>()
        override suspend fun getAll(): List<CustomMangaInfo> = stored.values.toList()
        override fun getByMangaIdAsFlow(mangaId: Long): Flow<CustomMangaInfo?> = flowOf(stored[mangaId])
        override fun getAllAsFlow(): Flow<List<CustomMangaInfo>> = flowOf(stored.values.toList())
        override suspend fun set(info: CustomMangaInfo) {
            stored[info.mangaId] = info
        }
        override suspend fun delete(mangaId: Long) {
            deleted += mangaId
            stored.remove(mangaId)
        }
    }

    @Test
    fun `a non-empty override is persisted`() = runTest {
        val repo = FakeRepository()
        SetCustomMangaInfo(repo).set(CustomMangaInfo(mangaId = 1L, title = "T"))
        repo.stored[1L]?.title shouldBe "T"
    }

    @Test
    fun `an all-null override clears the row instead of writing it`() = runTest {
        val repo = FakeRepository()
        repo.stored[1L] = CustomMangaInfo(mangaId = 1L, title = "Old")
        SetCustomMangaInfo(repo).set(CustomMangaInfo(mangaId = 1L))
        repo.deleted shouldBe listOf(1L)
    }
}
