package reikai.presentation.migrate.flow

import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import mihon.domain.manga.model.toDomainManga
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.manga.MangaMergeManager
import reikai.presentation.browse.toEntryBrowseUi
import reikai.presentation.migrate.PickMember
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.Source
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
    private val mergeManager: MangaMergeManager,
    private val getFavorites: GetFavorites,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
) : MigrationFlowAdapter {

    override val contentType = ContentType.MANGA
    override val supportsSmartMatch = true
    override val suggestsChapterCounts = true

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
                    icon = Source(
                        id = source.id,
                        lang = source.lang,
                        name = source.name,
                        supportsLatest = false,
                        isStub = false,
                    ),
                )
            }
    }

    override fun savedSelection(): List<String> = sourcePreferences.migrationSources.get().map { "$it" }

    override fun persistSelection(keys: List<String>) {
        sourcePreferences.migrationSources.set(keys.mapNotNull { it.toLongOrNull() })
    }

    override fun pinnedKeys(): Set<String> = sourcePreferences.pinnedSources.get()

    override suspend fun mergeGroupMembers(ids: List<Long>): List<PickMember> {
        val memberIds = LinkedHashSet<Long>()
        ids.forEach { id ->
            val manga = getManga.await(id) ?: return@forEach
            mergeManager.computeRelatedIds(manga.id).forEach { memberIds += it }
        }
        return memberIds.mapNotNull { id ->
            getManga.await(id)?.let { manga ->
                PickMember(
                    id = manga.id,
                    title = manga.title,
                    coverData = manga,
                    sourceName = sourceManager.get(manga.source)?.name,
                    chapterCount = getChaptersByMangaId.await(manga.id).size,
                )
            }
        }
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

    override fun sourceDisplayName(sourceKey: String): String {
        return sourceKey.toLongOrNull()?.let { sourceManager.getOrStub(it).name } ?: sourceKey
    }

    override fun favorites(sourceKey: String): Flow<List<MigrationFavorite>> {
        val sourceId = sourceKey.toLongOrNull() ?: return flowOf(emptyList())
        return getFavorites.subscribe(sourceId).map { list ->
            list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                .map { manga ->
                    MigrationFavorite(
                        id = EntryId.Manga(manga.id),
                        title = manga.title,
                        cover = manga,
                        payload = manga,
                    )
                }
        }
    }

    override suspend fun loadEntries(ids: List<Long>): List<MigrationEntry> {
        return ids.mapNotNull { id ->
            val manga = getManga.await(id) ?: return@mapNotNull null
            val chapters = getChaptersByMangaId.await(id)
            MigrationEntry(
                id = EntryId.Manga(id),
                title = manga.title,
                sourceKey = "${manga.source}",
                sourceName = sourceManager.get(manga.source)?.name,
                chapterCount = chapters.size,
                latestChapter = chapters.maxOfOrNull { it.chapterNumber }?.takeIf { it >= 0.0 },
                cover = manga.toEntryBrowseUi().cover,
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
        // Own source is searchable, but the identical listing is never a migration target.
        val current = entry.payload as? Manga
        if (current != null && match.url == current.url && sourceKey == entry.sourceKey) return null
        val local = networkToLocalManga(listOf(match)).firstOrNull() ?: return null
        // Fetch the target's chapters now so counts are real before commit (prioritize-by-chapters,
        // hide-without-updates, and the row's count line all read them); best-effort like upstream.
        runCatchingCancellable { updateMangaFromRemote(local, fetchChapters = true).getOrThrow() }
        if (local.thumbnailUrl == null) {
            runCatchingCancellable { updateMangaFromRemote(local, fetchDetails = true, manualFetch = true) }
        }
        // Re-read the row so a just-fetched cover/details reach the candidate, not the pre-fetch snapshot.
        val refreshed = getManga.await(local.id) ?: local
        val chapters = getChaptersByMangaId.await(local.id)
        return refreshed.toCandidate(sourceKey).copy(
            chapterCount = chapters.size,
            latestChapter = chapters.maxOfOrNull { it.chapterNumber }?.takeIf { it >= 0.0 },
        )
    }

    override suspend fun candidates(
        entry: MigrationEntry,
        query: String,
        sourceKey: String,
    ): List<MigrationCandidate> {
        val source = catalogueSource(sourceKey) ?: return emptyList()
        val currentUrl = (entry.payload as? Manga)?.url.takeIf { sourceKey == entry.sourceKey }
        val found = source.getSearchManga(1, query, source.getFilterList()).mangas
            .map { it.toDomainManga(source.id) }
            .distinctBy { it.url }
            .filterNot { it.url == currentUrl }
        return networkToLocalManga(found).map { it.toCandidate(sourceKey) }
    }

    override suspend fun resolve(candidate: MigrationCandidate): MigrationCandidate? {
        // The manga target is already a local row (networkToLocalManga at search time); its chapters
        // are fetched inside MigrateMangaUseCase at commit, so resolving only fills the local counts.
        val manga = candidate.handle as? Manga ?: return null
        val chapters = getChaptersByMangaId.await(manga.id)
        return candidate.copy(
            chapterCount = chapters.size,
            latestChapter = chapters.maxOfOrNull { it.chapterNumber }?.takeIf { it >= 0.0 },
        )
    }

    override suspend fun storedCandidate(id: Long): MigrationCandidate? {
        val manga = getManga.await(id) ?: return null
        val chapters = getChaptersByMangaId.await(id)
        return manga.toCandidate("${manga.source}").copy(
            chapterCount = chapters.size,
            latestChapter = chapters.maxOfOrNull { it.chapterNumber }?.takeIf { it >= 0.0 },
        )
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
        // The use case silently no-ops when the target source is gone; surface that as a row failure.
        checkNotNull(sourceManager.get(targetManga.source)) { "target source is not installed" }
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
