package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import exh.md.dto.MangaDto
import exh.md.dto.StatisticsMangaDto
import exh.md.handlers.ApiMangaParser
import exh.md.handlers.MangaHandler
import exh.md.service.MangaDexService
import exh.md.utils.MdLang
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.source.DelegatedHttpSource
import kotlin.reflect.KClass

/**
 * MangaDex enhanced source (MVP). Wraps the installed MangaDex extension and enriches title details
 * with MangaDex metadata (namespaced tags, cross-tracker ids, rating). Chapters, pages, browse and
 * search delegate to the stock extension, the same way [EightMuses] does. Login, follows sync, the
 * MDList tracker, similar-manga and the external-aggregator page handlers arrive in later phases.
 */
class MangaDex(delegate: HttpSource, val context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<MangaDexSearchMetadata, Triple<MangaDto, List<String>, StatisticsMangaDto?>>,
    NamespaceSource {

    override val lang: String = delegate.lang

    private val mdLang by lazy {
        MdLang.fromExt(lang) ?: MdLang.ENGLISH
    }

    private val mangadexService by lazy { MangaDexService(client) }
    private val apiMangaParser by lazy { ApiMangaParser(mdLang.lang) }
    private val mangaHandler by lazy { MangaHandler(mdLang.lang, mangadexService) }

    override val metaClass: KClass<MangaDexSearchMetadata> = MangaDexSearchMetadata::class

    override fun newMetaInstance() = MangaDexSearchMetadata()

    override suspend fun parseIntoMetadata(
        metadata: MangaDexSearchMetadata,
        input: Triple<MangaDto, List<String>, StatisticsMangaDto?>,
    ) {
        apiMangaParser.parseIntoMetadata(
            metadata,
            input.first,
            input.second,
            input.third,
            // Per-language prefs (cover quality, title-language preference, data-saver, blocked
            // groups, ...) get their SharedPreferences plumbing and settings UI in Phase 5. Phase 1
            // uses the defaults: full-quality cover, prefer the extension-language title.
            coverQuality = "",
            preferExtensionLangTitle = true,
        )
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            parseToManga(manga, mangaHandler.getMangaDetailsInput(manga))
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) {
            delegate.getMangaUpdate(manga, chapters, fetchDetails = false, fetchChapters = true).chapters
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }
}
