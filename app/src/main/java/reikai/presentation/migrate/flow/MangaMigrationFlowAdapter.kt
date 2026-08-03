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

/** The manga half of the migration seam: Mihon's smart-search engines, sources and migrate use case. */
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
        sourcePreferences.migrationSources.set(
            keys.map { key ->
                requireNotNull(key.toLongOrNull()) { "manga source keys are numeric ids, got '$key'" }
            },
        )
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
                val sourceName = sourceManager.get(manga.source)?.name ?: "${manga.source}"
                val chapters = getChaptersByMangaId.await(manga.id).size
                PickMember(
                    id = manga.id,
                    title = manga.title,
                    coverData = manga,
                    subtitle = "$sourceName  $chapters",
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
        // The entry's own source stays searchable, but its identical listing is never a target.
        val current = entry.payload as? Manga
        if (current != null && match.url == current.url && sourceKey == entry.sourceKey) return null
        val local = networkToLocalManga(listOf(match)).firstOrNull() ?: return null
        // Chapters, and only chapters: this source's match still has to be ranked against the other
        // sources' matches by chapter count, but a details fetch here would run for every probed
        // source. That top-up belongs to resolve, which runs once, on the target being committed.
        runCatchingCancellable { updateMangaFromRemote(local, fetchChapters = true).getOrThrow() }
        val chapters = getChaptersByMangaId.await(local.id)
        return local.toCandidate(sourceKey).copy(
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

    override suspend fun resolve(candidate: MigrationCandidate): ResolvedTarget? {
        val manga = candidate.handle as? Manga ?: return null
        // A candidate straight from candidates() has no chapters locally, so counts would read as
        // unknown and the commit would migrate onto an unfetched row; a suggestion already has them
        // and skips the fetch. Best-effort: a failure still resolves, and the use case fetches again.
        var chapters = getChaptersByMangaId.await(manga.id)
        val fetched = chapters.isEmpty()
        if (fetched) {
            runCatchingCancellable { updateMangaFromRemote(manga, fetchChapters = true) }
            chapters = getChaptersByMangaId.await(manga.id)
        }
        if (manga.thumbnailUrl == null) {
            runCatchingCancellable { updateMangaFromRemote(manga, fetchDetails = true, manualFetch = true) }
        }
        val refreshed = getManga.await(manga.id) ?: manga
        return ResolvedTarget(
            candidate = refreshed.toCandidate(candidate.sourceKey).copy(
                chapterCount = chapters.size.takeIf { it > 0 },
                latestChapter = chapters.maxOfOrNull { it.chapterNumber }?.takeIf { it >= 0.0 },
                resolved = true,
            ),
            syncedNow = fetched,
        )
    }

    override suspend fun storedCandidate(id: Long): MigrationCandidate? {
        val manga = getManga.await(id) ?: return null
        val chapters = getChaptersByMangaId.await(id)
        return manga.toCandidate("${manga.source}").copy(
            chapterCount = chapters.size.takeIf { it > 0 },
            latestChapter = chapters.maxOfOrNull { it.chapterNumber }?.takeIf { it >= 0.0 },
            resolved = true,
        )
    }

    override fun savedFlags(): Set<MigrationDataFlag> {
        return sourcePreferences.migrationFlags.get().map { MigrationDataFlag.valueOf(it.name) }.toSet()
    }

    override fun persistFlags(flags: Set<MigrationDataFlag>) {
        sourcePreferences.migrationFlags.set(flags.mapTo(HashSet()) { MigrationFlag.valueOf(it.name) })
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
        targetJustSynced: Boolean,
    ) {
        val current = entry.payload as? Manga ?: error("manga entry payload missing")
        val targetManga = target.handle as? Manga ?: error("manga target handle missing")
        // The use case silently no-ops on both of these; surface them as row failures instead of
        // marking a row migrated when nothing happened.
        check(current.id != targetManga.id) { "target is the entry itself" }
        checkNotNull(sourceManager.get(targetManga.source)) { "target source is not installed" }
        migrateManga(
            current,
            targetManga,
            replace,
            flags.mapTo(HashSet()) { MigrationFlag.valueOf(it.name) },
            skipTargetRefresh = targetJustSynced,
        )
    }

    private fun catalogueSource(key: String): CatalogueSource? {
        return key.toLongOrNull()?.let { sourceManager.get(it) } as? CatalogueSource
    }

    private fun Manga.toCandidate(sourceKey: String) = MigrationCandidate(
        sourceKey = sourceKey,
        title = title,
        chapterCount = null,
        key = "$sourceKey:$url",
        cover = toEntryBrowseUi().cover,
        handle = this,
    )
}
