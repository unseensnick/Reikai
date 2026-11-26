package reikai.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.chapter.ChapterNumberOverride
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.library.StoredType
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterNumberOverrideRepositoryImpl(
    private val database: Database,
) : ChapterNumberOverrideRepository {

    private val queries = database.chapter_number_overrideQueries

    override suspend fun getByOwner(type: StoredType, ownerId: Long): Map<String, ChapterNumberOverride> =
        when (type) {
            StoredType.NOVELS -> queries.getNovelOverrides(ownerId, ::ChapterNumberOverride)
            StoredType.MANGA -> queries.getMangaOverrides(ownerId, ::ChapterNumberOverride)
        }.awaitAsList().associateBy { it.url }

    override suspend fun getByOwners(
        type: StoredType,
        ownerIds: List<Long>,
    ): Map<Long, Map<String, ChapterNumberOverride>> {
        val mapper = { ownerId: Long, url: String, number: Double, sourceNumber: Double ->
            ownerId to ChapterNumberOverride(url, number, sourceNumber)
        }
        return when (type) {
            StoredType.NOVELS -> queries.getNovelOverridesOf(ownerIds, mapper)
            StoredType.MANGA -> queries.getMangaOverridesOf(ownerIds, mapper)
        }.awaitAsList().groupBy({ it.first }, { it.second }).mapValues { (_, it) -> it.associateBy { it.url } }
    }

    override suspend fun set(type: StoredType, ownerId: Long, url: String, number: Double) {
        database.transaction {
            when (type) {
                StoredType.NOVELS -> {
                    queries.setNovelOverride(number, ownerId, url)
                    queries.applyNovelOverrides(ownerId)
                }
                StoredType.MANGA -> {
                    queries.setMangaOverride(number, ownerId, url)
                    queries.applyMangaOverrides(ownerId)
                }
            }
        }
    }

    override suspend fun clear(type: StoredType, ownerId: Long, url: String) {
        database.transaction {
            when (type) {
                StoredType.NOVELS -> {
                    queries.clearNovelChapterNumber(ownerId, url)
                    queries.deleteNovel(ownerId, url)
                }
                StoredType.MANGA -> {
                    queries.clearMangaChapterNumber(ownerId, url)
                    queries.deleteManga(ownerId, url)
                }
            }
        }
    }

    override suspend fun restore(type: StoredType, ownerId: Long, overrides: List<ChapterNumberOverride>) {
        if (overrides.isEmpty()) return
        database.transaction {
            upsert(type, ownerId, overrides)
            when (type) {
                StoredType.NOVELS -> queries.applyNovelOverrides(ownerId)
                StoredType.MANGA -> queries.applyMangaOverrides(ownerId)
            }
        }
    }

    override suspend fun updateSourceNumbers(type: StoredType, ownerId: Long, overrides: List<ChapterNumberOverride>) {
        if (overrides.isEmpty()) return
        database.transaction { upsert(type, ownerId, overrides) }
    }

    private suspend fun upsert(type: StoredType, ownerId: Long, overrides: List<ChapterNumberOverride>) {
        overrides.forEach {
            when (type) {
                StoredType.NOVELS -> queries.upsertNovel(ownerId, it.url, it.number, it.sourceNumber)
                StoredType.MANGA -> queries.upsertManga(ownerId, it.url, it.number, it.sourceNumber)
            }
        }
    }
}
