package reikai.presentation.migrate.flow

import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.domain.manga.model.toDomainManga
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

class MangaMigrationFlowAdapter(
    private val sourceManager: SourceManager,
    private val sourcePreferences: SourcePreferences,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val networkToLocalManga: NetworkToLocalManga,
    private val coverCache: CoverCache,
    private val downloadManager: DownloadManager,
    private val migrateManga: MigrateMangaUseCase,
) : MigrationFlowAdapter {

    override val contentType = ContentType.MANGA
    override val supportsSmartMatch = true

    override fun enabledSources(): List<MigrationSourceUi> {
        val languages = sourcePreferences.enabledLanguages.get()
        val disabled = sourcePreferences.disabledSources.get()
        return sourceManager.getAll()
            .filterIsInstance<HttpSource>()
            .filter { it.lang in languages && "${it.id}" !in disabled }
            .sortedBy { "${it.name.lowercase()} (${it.lang})" }
            .map { source ->
                MigrationSourceUi(
                    key = "${source.id}",
                    name = source.name,
                    lang = source.lang,
                    icon = source,
                )
            }
    }

    override fun savedSelection(): List<String> = sourcePreferences.migrationSources.get().map { "$it" }

    override fun persistSelection(keys: List<String>) {
        sourcePreferences.migrationSources.set(keys.mapNotNull { it.toLongOrNull() })
    }

    override fun readTuning(): MigrationTuning = MigrationTuning(
        deepSearch = sourcePreferences.migrationDeepSearchMode.get(),
        prioritizeByChapters = sourcePreferences.migrationPrioritizeByChapters.get(),
        hideUnmatched = sourcePreferences.migrationHideUnmatched.get(),
        hideWithoutUpdates = sourcePreferences.migrationHideWithoutUpdates.get(),
    )

    override fun persistTuning(tuning: MigrationTuning) {
        sourcePreferences.migrationDeepSearchMode.set(tuning.deepSearch)
        sourcePreferences.migrationPrioritizeByChapters.set(tuning.prioritizeByChapters)
        sourcePreferences.migrationHideUnmatched.set(tuning.hideUnmatched)
        sourcePreferences.migrationHideWithoutUpdates.set(tuning.hideWithoutUpdates)
    }

    override suspend fun loadEntries(ids: List<Long>): List<MigrationEntry> {
        return ids.mapNotNull { id ->
            val manga = getManga.await(id) ?: return@mapNotNull null
            MigrationEntry(
                id = EntryId.Manga(id),
                title = manga.title,
                sourceKey = "${manga.source}",
                sourceName = sourceManager.get(manga.source)?.name,
                chapterCount = getChaptersByMangaId.await(id).size,
                payload = manga,
            )
        }
    }

    override suspend fun suggest(
        entry: MigrationEntry,
        sourceKey: String,
        tuning: MigrationTuning,
    ): MigrationCandidate? {
        val source = catalogueSource(sourceKey) ?: return null
        val engine = SmartSourceSearchEngine(tuning.extraQuery)
        val match = if (tuning.deepSearch) {
            engine.deepSearch(source, entry.title)
        } else {
            engine.regularSearch(source, entry.title)
        } ?: return null
        val local = networkToLocalManga(listOf(match)).firstOrNull() ?: return null
        return local.toCandidate(sourceKey)
    }

    override suspend fun candidates(
        entry: MigrationEntry,
        query: String,
        sourceKey: String,
    ): List<MigrationCandidate> {
        val source = catalogueSource(sourceKey) ?: return emptyList()
        val found = source.getSearchManga(1, query, source.getFilterList()).mangas
            .map { it.toDomainManga(source.id) }
            .distinctBy { it.url }
        return networkToLocalManga(found).map { it.toCandidate(sourceKey) }
    }

    override suspend fun resolve(candidate: MigrationCandidate): MigrationCandidate? {
        // The manga target is already a local row (networkToLocalManga at search time); its chapters
        // are fetched inside MigrateMangaUseCase at commit, so resolving only fills the local count.
        val manga = candidate.handle as? Manga ?: return null
        return candidate.copy(chapterCount = getChaptersByMangaId.await(manga.id).size)
    }

    override fun savedFlags(): Set<MigrationDataFlag> {
        return sourcePreferences.migrationFlags.get().map { MigrationDataFlag.valueOf(it.name) }.toSet()
    }

    override suspend fun applicableFlags(entries: List<MigrationEntry>): Set<MigrationDataFlag> {
        val mangas = entries.mapNotNull { it.payload as? Manga }
        return MigrationDataFlag.entries.filterTo(LinkedHashSet()) { flag ->
            when (flag) {
                MigrationDataFlag.CHAPTER -> true
                MigrationDataFlag.CATEGORY -> true
                MigrationDataFlag.CUSTOM_COVER -> mangas.any { it.hasCustomCover(coverCache) }
                MigrationDataFlag.NOTES -> mangas.any { it.notes.isNotBlank() }
                MigrationDataFlag.REMOVE_DOWNLOAD -> mangas.any { downloadManager.getDownloadCount(it) > 0 }
            }
        }
    }

    override suspend fun migrate(
        entry: MigrationEntry,
        target: MigrationCandidate,
        replace: Boolean,
        flags: Set<MigrationDataFlag>,
    ) {
        val current = entry.payload as? Manga ?: error("manga entry payload missing")
        val targetManga = target.handle as? Manga ?: error("manga target handle missing")
        sourcePreferences.migrationFlags.set(flags.map { MigrationFlag.valueOf(it.name) }.toSet())
        migrateManga(current, targetManga, replace)
    }

    private fun catalogueSource(key: String): CatalogueSource? {
        return key.toLongOrNull()?.let { sourceManager.get(it) } as? CatalogueSource
    }

    private fun Manga.toCandidate(sourceKey: String) = MigrationCandidate(
        sourceKey = sourceKey,
        title = title,
        chapterCount = null,
        handle = this,
    )
}
