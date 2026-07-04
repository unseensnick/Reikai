package exh.md.handlers

import eu.kanade.tachiyomi.source.model.SManga
import exh.md.dto.MangaDto
import exh.md.dto.StatisticsMangaDto
import exh.md.service.MangaDexService
import exh.md.utils.MdUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class MangaHandler(
    private val lang: String,
    private val service: MangaDexService,
) {
    // The input the MetadataSource round-trip parses a title's details from: the manga record, its
    // simple chapter-number list (drives the completed-status heuristic), and its rating statistics.
    suspend fun getMangaDetailsInput(manga: SManga): Triple<MangaDto, List<String>, StatisticsMangaDto?> {
        return coroutineScope {
            val mangaId = MdUtil.getMangaId(manga.url)
            val response = async(Dispatchers.IO) { service.viewManga(mangaId) }
            val simpleChapters = async(Dispatchers.IO) { getSimpleChapters(manga) }
            val statistics = async(Dispatchers.IO) {
                runCatching { service.mangasRating(mangaId) }.getOrNull()?.statistics?.get(mangaId)
            }
            Triple(response.await(), simpleChapters.await(), statistics.await())
        }
    }

    private suspend fun getSimpleChapters(manga: SManga): List<String> {
        return runCatching { service.aggregateChapters(MdUtil.getMangaId(manga.url), lang) }
            .onFailure {
                if (it is CancellationException) throw it
            }
            .map { dto ->
                dto.volumes.values
                    .flatMap { it.chapters.values }
                    .map { it.chapter }
            }
            .getOrElse { emptyList() }
    }
}
