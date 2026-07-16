package reikai.domain.novel.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.repository.CustomNovelInfoRepository

class SetCustomNovelInfoTest {

    private class FakeRepository : CustomNovelInfoRepository {
        val stored = mutableMapOf<Long, CustomNovelInfo>()
        val deleted = mutableListOf<Long>()
        override suspend fun getAll(): List<CustomNovelInfo> = stored.values.toList()
        override fun getByNovelIdAsFlow(novelId: Long): Flow<CustomNovelInfo?> = flowOf(stored[novelId])
        override fun getAllAsFlow(): Flow<List<CustomNovelInfo>> = flowOf(stored.values.toList())
        override suspend fun set(info: CustomNovelInfo) {
            stored[info.novelId] = info
        }
        override suspend fun delete(novelId: Long) {
            deleted += novelId
            stored.remove(novelId)
        }
    }

    @Test
    fun `a non-empty override is persisted`() = runTest {
        val repo = FakeRepository()
        SetCustomNovelInfo(repo).set(CustomNovelInfo(novelId = 1L, title = "T"))
        repo.stored[1L]?.title shouldBe "T"
    }

    @Test
    fun `an all-null override clears the row instead of writing it`() = runTest {
        val repo = FakeRepository()
        repo.stored[1L] = CustomNovelInfo(novelId = 1L, title = "Old")
        SetCustomNovelInfo(repo).set(CustomNovelInfo(novelId = 1L))
        repo.deleted shouldBe listOf(1L)
    }
}
