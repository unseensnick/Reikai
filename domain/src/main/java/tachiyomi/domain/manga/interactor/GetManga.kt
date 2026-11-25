package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.online.MetadataSource
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class GetManga(
    private val mangaRepository: MangaRepository,
) : MetadataSource.GetMangaId { // RK: EXH delegated sources resolve a manga id by (url, source)

    suspend fun await(id: Long): Manga? {
        return try {
            mangaRepository.getMangaById(id)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    fun subscribe(id: Long): Flow<Manga> {
        return mangaRepository.getMangaByIdAsFlow(id)
    }

    fun subscribe(url: String, sourceId: Long): Flow<Manga?> {
        return mangaRepository.getMangaByUrlAndSourceIdAsFlow(url, sourceId)
    }

    // RK -->

    /** The stored manga among [ids], in one chunked read rather than one per id; a missing row is left out. */
    suspend fun awaitAll(ids: Collection<Long>): List<Manga> = mangaRepository.getMangaByIds(ids.toList())

    suspend fun await(url: String, sourceId: Long): Manga? {
        return mangaRepository.getMangaByUrlAndSourceId(url, sourceId)
    }

    override suspend fun awaitId(url: String, sourceId: Long): Long? {
        return await(url, sourceId)?.id
    }
    // RK <--
}
