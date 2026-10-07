package reikai.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.chapter.ChapterNumberOverride
import reikai.domain.chapter.ChapterNumberOverrideRepository
import reikai.domain.library.ContentType
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterNumberOverrideRepositoryImpl(
    private val database: Database,
) : ChapterNumberOverrideRepository {

    private val queries = database.chapter_number_overrideQueries

    override suspend fun getByOwner(type: ContentType, ownerId: Long): Map<String, ChapterNumberOverride> =
        when (type) {
            ContentType.NOVELS -> queries.getNovelOverrides(ownerId, ::ChapterNumberOverride)
            else -> queries.getMangaOverrides(ownerId, ::ChapterNumberOverride)
        }.awaitAsList().associateBy { it.url }

    override suspend fun set(type: ContentType, ownerId: Long, url: String, number: Double) {
        database.transaction {
            when (type) {
                ContentType.NOVELS -> {
                    queries.setNovelOverride(number, ownerId, url)
                    queries.applyNovelOverrides(ownerId)
                }
                else -> {
                    queries.setMangaOverride(number, ownerId, url)
                    queries.applyMangaOverrides(ownerId)
                }
            }
        }
    }

    override suspend fun clear(type: ContentType, ownerId: Long, url: String) {
        database.transaction {
            when (type) {
                ContentType.NOVELS -> {
                    queries.clearNovelChapterNumber(ownerId, url)
                    queries.deleteNovel(ownerId, url)
                }
                else -> {
                    queries.clearMangaChapterNumber(ownerId, url)
                    queries.deleteManga(ownerId, url)
                }
            }
        }
    }

    override suspend fun restore(type: ContentType, ownerId: Long, overrides: List<ChapterNumberOverride>) {
        if (overrides.isEmpty()) return
        database.transaction {
            upsert(type, ownerId, overrides)
            when (type) {
                ContentType.NOVELS -> queries.applyNovelOverrides(ownerId)
                else -> queries.applyMangaOverrides(ownerId)
            }
        }
    }

    override suspend fun updateSourceNumbers(type: ContentType, ownerId: Long, overrides: List<ChapterNumberOverride>) {
        if (overrides.isEmpty()) return
        database.transaction { upsert(type, ownerId, overrides) }
    }

    private suspend fun upsert(type: ContentType, ownerId: Long, overrides: List<ChapterNumberOverride>) {
        overrides.forEach {
            when (type) {
                ContentType.NOVELS -> queries.upsertNovel(ownerId, it.url, it.number, it.sourceNumber)
                else -> queries.upsertManga(ownerId, it.url, it.number, it.sourceNumber)
            }
        }
    }
}
