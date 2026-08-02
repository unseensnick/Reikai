package reikai.presentation.migrate.flow

import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.data.coil.NovelCover
import reikai.data.novel.refreshNovelFromSource
import reikai.data.novel.toNovel
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.MigrateNovelUseCase
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelMigrationFlag
import reikai.domain.novel.model.hasCustomCover
import reikai.domain.source.GetEnabledNovelSources
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.NovelDownloadManager
import reikai.novel.host.NovelItem
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import reikai.presentation.migrate.PickMember
import tachiyomi.data.Database

/** The adapter-owned candidate handle: the raw search hit, plus the materialised row once
 *  [NovelMigrationFlowAdapter.resolve] has run. */
data class NovelCandidateHandle(
    val item: NovelItem,
    /** The source's site, the cover Referer. */
    val site: String?,
    val resolved: Novel? = null,
    /** True when [NovelMigrationFlowAdapter.resolve] just chapter-synced the row, so the engine can
     *  skip its own refresh (a second identical fetch seconds later); a stored row wrapped by
     *  [NovelMigrationFlowAdapter.storedCandidate] is resolved but NOT synced. */
    val synced: Boolean = false,
)

class NovelMigrationFlowAdapter(
    private val sourceManager: NovelSourceManager,
    private val getEnabledNovelSources: GetEnabledNovelSources,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val novelPreferences: NovelPreferences,
    private val novelRepository: NovelRepository,
    private val chapterRepository: NovelChapterRepository,
    private val database: Database,
    private val coverCache: CoverCache,
    private val downloadManager: NovelDownloadManager,
    private val migrateNovel: MigrateNovelUseCase,
    private val mergeManager: NovelMergeManager,
    private val installer: LnPluginInstaller,
) : MigrationFlowAdapter {

    override val contentType = ContentType.NOVELS
    override val supportsSmartMatch = false
    override val suggestsChapterCounts = false

    override suspend fun prepare() {
        // Best-effort, like every other novel surface: a load failure falls through to empty sources.
        runCatching { installer.ensureLoaded() }
    }

    override fun enabledSources(): List<MigrationSourceUi> {
        return getEnabledNovelSources.get()
            .sortedBy { it.name.lowercase() }
            .map { source ->
                MigrationSourceUi(
                    key = source.id,
                    name = source.name,
                    lang = source.lang,
                    icon = source.iconUrl,
                )
            }
    }

    override fun savedSelection(): List<String> = sourcePreferences.novelMigrationSources.get()

    override fun persistSelection(keys: List<String>) {
        sourcePreferences.novelMigrationSources.set(keys)
    }

    override fun pinnedKeys(): Set<String> = sourcePreferences.pinnedNovelSources.get()

    override suspend fun mergeGroupMembers(ids: List<Long>): List<PickMember> {
        val memberIds = LinkedHashSet<Long>()
        ids.forEach { id ->
            val novel = novelRepository.getById(id) ?: return@forEach
            mergeManager.computeRelatedIds(novel.id).forEach { memberIds += it }
        }
        return memberIds.mapNotNull { id ->
            novelRepository.getById(id)?.let { novel ->
                val source = sourceManager.get(novel.source)
                PickMember(
                    id = novel.id,
                    title = novel.title,
                    coverData = NovelCover(
                        url = novel.thumbnailUrl,
                        site = source?.site,
                        // Library rows: true, so a user-set custom cover shows in the picker.
                        isNovelFavorite = true,
                        lastModified = novel.coverLastModified,
                        novelId = novel.id,
                    ),
                    sourceName = source?.name,
                    chapterCount = chapterRepository.getByNovelId(novel.id).size,
                )
            }
        }
    }

    override fun readTuning(): MigrationTuning = MigrationTuning(
        hideUnmatched = novelPreferences.novelMigrationHideUnmatched().get(),
        hideWithoutUpdates = novelPreferences.novelMigrationHideWithoutUpdates().get(),
    )

    override fun persistTuning(tuning: MigrationTuning) {
        novelPreferences.novelMigrationHideUnmatched().set(tuning.hideUnmatched)
        novelPreferences.novelMigrationHideWithoutUpdates().set(tuning.hideWithoutUpdates)
    }

    override fun sourceDisplayName(sourceKey: String): String {
        return sourceManager.get(sourceKey)?.name
            ?: novelPreferences.seenNovelSources().get()[sourceKey]?.name
            ?: sourceKey
    }

    override fun favorites(sourceKey: String): Flow<List<MigrationFavorite>> {
        val site = sourceManager.get(sourceKey)?.site
        return novelRepository.getLibraryNovelAsFlow().map { list ->
            list.asSequence()
                .map { it.novel }
                .filter { it.source == sourceKey }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                .map { novel ->
                    MigrationFavorite(
                        id = EntryId.Novel(novel.id),
                        title = novel.title,
                        cover = NovelCover(
                            url = novel.thumbnailUrl,
                            site = site,
                            // Library rows: true, so a user-set custom cover shows in the picker.
                            isNovelFavorite = true,
                            lastModified = novel.coverLastModified,
                            novelId = novel.id,
                        ),
                        payload = novel,
                    )
                }
                .toList()
        }
    }

    override suspend fun loadEntries(ids: List<Long>): List<MigrationEntry> {
        return ids.mapNotNull { id ->
            val novel = novelRepository.getById(id) ?: return@mapNotNull null
            val chapters = chapterRepository.getByNovelId(id)
            MigrationEntry(
                id = EntryId.Novel(id),
                title = novel.title,
                sourceKey = novel.source,
                sourceName = sourceManager.get(novel.source)?.name,
                chapterCount = chapters.size,
                latestChapter = chapters.maxOfOrNull { it.chapterNumber }?.takeIf { it >= 0.0 },
                cover = NovelCover(
                    url = novel.thumbnailUrl,
                    site = sourceManager.get(novel.source)?.site,
                    isNovelFavorite = true,
                    lastModified = novel.coverLastModified,
                    novelId = novel.id,
                ),
                payload = novel,
            )
        }
    }

    override suspend fun suggest(
        entry: MigrationEntry,
        sourceKey: String,
        tuning: MigrationTuning,
    ): MigrationCandidate? {
        return candidates(entry, buildQuery(entry.title, tuning.extraQuery), sourceKey).firstOrNull()
    }

    override suspend fun candidates(
        entry: MigrationEntry,
        query: String,
        sourceKey: String,
    ): List<MigrationCandidate> {
        val source = sourceManager.get(sourceKey) ?: return emptyList()
        // Own source is searchable, but the identical listing is never a migration target. Dedupe
        // by path: plugins can repeat a listing in one page, and the path is the lazy-list key.
        val currentPath = (entry.payload as? Novel)?.url.takeIf { sourceKey == entry.sourceKey }
        return source.searchNovels(query, 1)
            .distinctBy { it.path }
            .filterNot { it.path == currentPath }
            .map { item ->
                MigrationCandidate(
                    sourceKey = sourceKey,
                    title = item.name,
                    chapterCount = null,
                    handle = NovelCandidateHandle(item, source.site),
                )
            }
    }

    override suspend fun resolve(candidate: MigrationCandidate): MigrationCandidate? {
        val handle = candidate.handle as? NovelCandidateHandle ?: return null
        if (handle.resolved != null) return candidate
        val source = sourceManager.get(candidate.sourceKey) ?: return null
        val sourceNovel = source.parseNovel(handle.item.path)
        novelRepository.insertOrGet(sourceNovel.toNovel(sourceId = source.id, favorite = false)) ?: return null
        val stored = novelRepository.getByUrlAndSource(handle.item.path, source.id) ?: return null
        refreshNovelFromSource(
            stored,
            source,
            chapterRepository,
            novelRepository,
            database,
            novelDownloadManager = downloadManager,
        )
        val resolved = novelRepository.getByUrlAndSource(handle.item.path, source.id) ?: return null
        val chapters = chapterRepository.getByNovelId(resolved.id)
        return candidate.copy(
            chapterCount = chapters.size,
            latestChapter = chapters.maxOfOrNull { it.chapterNumber }?.takeIf { it >= 0.0 },
            handle = handle.copy(resolved = resolved, synced = true),
        )
    }

    override suspend fun storedCandidate(id: Long): MigrationCandidate? {
        val novel = novelRepository.getById(id) ?: return null
        val chapters = chapterRepository.getByNovelId(id)
        return MigrationCandidate(
            sourceKey = novel.source,
            title = novel.title,
            // A deep-picked row is stored bare (no chapter sync yet); null, not 0, so the compare
            // line shows unknown instead of a lying full-shortfall delta. Migrate refreshes it anyway.
            chapterCount = chapters.size.takeIf { it > 0 },
            latestChapter = chapters.maxOfOrNull { it.chapterNumber }?.takeIf { it >= 0.0 },
            handle = NovelCandidateHandle(
                item = NovelItem(name = novel.title, path = novel.url, cover = novel.thumbnailUrl),
                site = sourceManager.get(novel.source)?.site,
                resolved = novel,
            ),
        )
    }

    override fun savedFlags(): Set<MigrationDataFlag> {
        return NovelMigrationFlag.fromBits(novelPreferences.novelMigrationFlags().get())
            .map { it.toNeutral() }
            .toSet()
    }

    override suspend fun applicableFlags(entries: List<MigrationEntry>): Set<MigrationDataFlag> {
        val novels = entries.mapNotNull { it.payload as? Novel }
        return MigrationDataFlag.entries.filterTo(LinkedHashSet()) { flag ->
            when (flag) {
                MigrationDataFlag.CHAPTER -> true
                MigrationDataFlag.CATEGORY -> true
                MigrationDataFlag.CUSTOM_COVER -> novels.any { it.hasCustomCover(coverCache) }
                MigrationDataFlag.NOTES -> novels.any { it.notes.isNotBlank() }
                // Offered only when something is actually downloaded, matching the manga gate.
                MigrationDataFlag.REMOVE_DOWNLOAD -> novels.any { downloadManager.getDownloadCount(it) > 0 }
            }
        }
    }

    override suspend fun migrate(
        entry: MigrationEntry,
        target: MigrationCandidate,
        replace: Boolean,
        flags: Set<MigrationDataFlag>,
    ) {
        val current = entry.payload as? Novel ?: error("novel entry payload missing")
        val handle = target.handle as? NovelCandidateHandle
        val targetNovel = handle?.resolved ?: error("novel target not resolved")
        // The use case silently no-ops on a self-target; surface it as a row failure instead of
        // marking the row migrated when nothing happened.
        check(current.id != targetNovel.id) { "target is the entry itself" }
        val novelFlags = flags.map { it.toNovelFlag() }.toSet()
        novelPreferences.novelMigrationFlags().set(NovelMigrationFlag.toBits(novelFlags))
        // A resolve()-synced target skips the engine's own refresh: it would repeat the identical
        // chapter fetch seconds later, doubling a batch commit's network cost.
        migrateNovel(current, targetNovel, novelFlags, replace, skipTargetRefresh = handle.synced)
    }

    private fun buildQuery(title: String, extraQuery: String?): String {
        return if (extraQuery.isNullOrBlank()) title else "$title $extraQuery"
    }

    // Name-mapped on purpose: the two enums share concepts but not bit layouts.
    private fun NovelMigrationFlag.toNeutral(): MigrationDataFlag = when (this) {
        NovelMigrationFlag.CHAPTER -> MigrationDataFlag.CHAPTER
        NovelMigrationFlag.CATEGORY -> MigrationDataFlag.CATEGORY
        NovelMigrationFlag.COVER -> MigrationDataFlag.CUSTOM_COVER
        NovelMigrationFlag.NOTES -> MigrationDataFlag.NOTES
        NovelMigrationFlag.REMOVE_DOWNLOAD -> MigrationDataFlag.REMOVE_DOWNLOAD
    }

    private fun MigrationDataFlag.toNovelFlag(): NovelMigrationFlag = when (this) {
        MigrationDataFlag.CHAPTER -> NovelMigrationFlag.CHAPTER
        MigrationDataFlag.CATEGORY -> NovelMigrationFlag.CATEGORY
        MigrationDataFlag.CUSTOM_COVER -> NovelMigrationFlag.COVER
        MigrationDataFlag.NOTES -> NovelMigrationFlag.NOTES
        MigrationDataFlag.REMOVE_DOWNLOAD -> NovelMigrationFlag.REMOVE_DOWNLOAD
    }
}
