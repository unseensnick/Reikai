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
) : MigrationFlowAdapter {

    override val contentType = ContentType.NOVELS
    override val supportsSmartMatch = false

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
                        isNovelFavorite = false,
                        lastModified = novel.coverLastModified,
                        novelId = novel.id,
                    ),
                    subtitle = listOfNotNull(source?.name, "${chapterRepository.getByNovelId(novel.id).size} ch")
                        .joinToString(" · "),
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
                            isNovelFavorite = false,
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
            MigrationEntry(
                id = EntryId.Novel(id),
                title = novel.title,
                sourceKey = novel.source,
                sourceName = sourceManager.get(novel.source)?.name,
                chapterCount = chapterRepository.getByNovelId(id).size,
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
        return source.searchNovels(query, 1).map { item ->
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
        return candidate.copy(
            chapterCount = chapterRepository.getByNovelId(resolved.id).size,
            handle = handle.copy(resolved = resolved),
        )
    }

    override suspend fun storedCandidate(id: Long): MigrationCandidate? {
        val novel = novelRepository.getById(id) ?: return null
        return MigrationCandidate(
            sourceKey = novel.source,
            title = novel.title,
            chapterCount = chapterRepository.getByNovelId(id).size,
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
                MigrationDataFlag.REMOVE_DOWNLOAD -> true
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
        val novelFlags = flags.map { it.toNovelFlag() }.toSet()
        novelPreferences.novelMigrationFlags().set(NovelMigrationFlag.toBits(novelFlags))
        migrateNovel(current, targetNovel, novelFlags, replace)
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
