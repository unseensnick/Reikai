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
import reikai.novel.source.SmartNovelSearchEngine
import reikai.presentation.migrate.PickMember
import tachiyomi.data.Database
import tachiyomi.domain.chapter.service.ChapterRecognition

/**
 * The adapter-owned novel candidate: the raw search hit, the source's site (the cover Referer), and
 * the stored row once [NovelMigrationFlowAdapter.resolve] has materialised it. A hit is only a
 * plugin's search-result entry until then, which is why novel candidates start unresolved.
 */
data class NovelCandidateHandle(
    val item: NovelItem,
    val site: String?,
    val stored: Novel? = null,
)

/** The novel half of the migration seam: the plugin sources, novel repositories and migrate engine. */
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

    /** Suggestions are title-matched (see [SmartNovelSearchEngine]), but the two options built on top
     *  of the smart-search engines, deep search and prioritize-by-chapters, have no novel equivalent. */
    override val matchStrategy = MatchStrategy.BestTitleMatch

    override suspend fun prepare() {
        // Best-effort, like every other novel surface: a load failure falls through to empty sources.
        runCatchingCancellable { installer.ensureLoaded() }
    }

    override fun enabledSources(): List<MigrationSourceUi> {
        return getEnabledNovelSources.get()
            .sortedBy { it.name.lowercase() }
            .map { source ->
                MigrationSourceUi(
                    key = source.id,
                    name = source.name,
                    lang = source.lang,
                    icon = MigrationSourceIcon.NovelUrl(source.iconUrl),
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
                val chapters = chapterRepository.getByNovelId(novel.id).size
                PickMember(
                    id = novel.id,
                    title = novel.title,
                    coverData = novel.toCover(source?.site),
                    subtitle = "${source?.name ?: novel.source}  $chapters",
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
                        cover = novel.toCover(site),
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
            val source = sourceManager.get(novel.source)
            MigrationEntry(
                id = EntryId.Novel(id),
                title = novel.title,
                sourceKey = novel.source,
                sourceName = source?.name,
                chapterCount = chapters.size,
                latestChapter = chapters.maxOfOrNull { it.chapterNumber }?.takeIf { it >= 0.0 },
                cover = novel.toCover(source?.site),
                payload = novel,
            )
        }
    }

    override suspend fun suggest(
        entry: MigrationEntry,
        sourceKey: String,
        tuning: MigrationTuning,
    ): MigrationCandidate? {
        val source = sourceManager.get(sourceKey) ?: return null
        // Score against the title alone and thread the extra query into the search string only,
        // matching manga: a term the user added to find the entry is not part of the entry's title.
        // Score the raw hit list, then drop the entry's own listing from the winner, exactly as manga
        // does. Filtering first would be worse than not filtering: the engine skips title scoring
        // altogether when a source returns a single candidate, so a plugin repeating one wrong
        // listing would dedupe down to that one hit and have it accepted unscored.
        val match = SmartNovelSearchEngine(tuning.extraQuery).bestMatch(entry.title) { query ->
            source.searchNovels(query, 1)
        } ?: return null
        val currentPath = (entry.payload as? Novel)?.url.takeIf { sourceKey == entry.sourceKey }
        if (match.path == currentPath) return null
        return match.toCandidate(sourceKey, source.site)
    }

    override suspend fun candidates(
        entry: MigrationEntry,
        query: String,
        sourceKey: String,
    ): List<MigrationCandidate> {
        val source = sourceManager.get(sourceKey) ?: return emptyList()
        return source.searchNovels(query, 1)
            .usableHits(entry, sourceKey)
            .map { it.toCandidate(sourceKey, source.site) }
    }

    /**
     * The entry's own source stays searchable, but its identical listing is never a target. Dedupe
     * by path: a plugin can repeat a listing within one page, and the path is the key.
     */
    private fun List<NovelItem>.usableHits(entry: MigrationEntry, sourceKey: String): List<NovelItem> {
        val currentPath = (entry.payload as? Novel)?.url.takeIf { sourceKey == entry.sourceKey }
        return distinctBy { it.path }.filterNot { it.path == currentPath }
    }

    /**
     * A search hit has no stored row, so being in the library is a lookup, not a field. It is read
     * for the marker only and deliberately does NOT populate the handle's `stored`: that field is
     * what decides whether a commit still owes this candidate a materialising [resolve].
     */
    private suspend fun NovelItem.toCandidate(sourceKey: String, site: String?) = MigrationCandidate(
        sourceKey = sourceKey,
        title = name,
        chapterCount = null,
        key = "$sourceKey:$path",
        cover = NovelCover(
            url = cover,
            site = site,
            isNovelFavorite = false,
            lastModified = 0L,
        ),
        inLibrary = novelRepository.getByUrlAndSource(path, sourceKey)?.favorite == true,
        handle = NovelCandidateHandle(this, site),
    )

    override suspend fun resolve(candidate: MigrationCandidate): ResolvedTarget? {
        val handle = candidate.handle as? NovelCandidateHandle ?: return null
        // The handle says whether this was already materialised: a stored row means resolve has run.
        if (handle.stored != null) return ResolvedTarget(candidate, syncedNow = false)
        val source = sourceManager.get(candidate.sourceKey) ?: return null
        // The search hit already carries everything a row needs to exist (title, path, cover), so it
        // is stored straight from that; the refresh below is the one call that parses the source, and
        // it fills in the details and chapters. Parsing here as well would double every accept.
        val base = Novel.create().copy(
            source = source.id,
            url = handle.item.path,
            title = handle.item.name,
            thumbnailUrl = handle.item.cover,
        )
        val stored = novelRepository.insertOrGet(base) ?: return null
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
        return ResolvedTarget(
            candidate = candidate.copy(
                title = resolved.title,
                // Null, not 0, for an empty list, matching every other candidate builder.
                chapterCount = chapters.size.takeIf { it > 0 },
                latestChapter = chapters.maxOfOrNull { it.chapterNumber }?.takeIf { it >= 0.0 },
                cover = resolved.toCover(source.site, favorite = false),
                handle = handle.copy(stored = resolved),
            ),
            // A refresh that stored nothing is not a sync (a soft-error page can parse as an empty
            // chapter list without throwing); claiming it would make the engine skip its
            // compensating refresh and migrate onto an empty row. Mirrors the manga guard.
            syncedNow = chapters.isNotEmpty(),
        )
    }

    override suspend fun peekCounts(candidate: MigrationCandidate): MigrationCandidate? {
        val handle = candidate.handle as? NovelCandidateHandle ?: return null
        val source = sourceManager.get(candidate.sourceKey) ?: return null
        val parsed = source.parseNovel(handle.item.path)
        // A paged source's first page undercounts; unknown is more honest than a floor.
        if (parsed.totalPages > 1) return null
        val chapters = parsed.chapters.orEmpty()
        if (chapters.isEmpty()) return null
        // Mirrors NovelChapterSync's numbering so the peeked latest matches what a commit stores.
        val latest = chapters.maxOf {
            ChapterRecognition.parseChapterNumber(handle.item.name, it.name, it.chapterNumber?.takeIf { n -> n > 0.0 })
        }
        return candidate.copy(
            chapterCount = chapters.size,
            latestChapter = latest.takeIf { it >= 0.0 },
        )
    }

    override suspend fun storedCandidate(id: Long): MigrationCandidate? {
        val novel = novelRepository.getById(id) ?: return null
        val chapters = chapterRepository.getByNovelId(id)
        val site = sourceManager.get(novel.source)?.site
        return MigrationCandidate(
            sourceKey = novel.source,
            title = novel.title,
            // A browsed row may be stored without a chapter sync; null rather than 0, so the compare
            // line reads unknown instead of a full shortfall. The engine refreshes it at migrate.
            chapterCount = chapters.size.takeIf { it > 0 },
            latestChapter = chapters.maxOfOrNull { it.chapterNumber }?.takeIf { it >= 0.0 },
            key = "${novel.source}:${novel.url}",
            cover = novel.toCover(site, favorite = novel.favorite),
            inLibrary = novel.favorite,
            handle = NovelCandidateHandle(
                item = NovelItem(name = novel.title, path = novel.url, cover = novel.thumbnailUrl),
                site = site,
                stored = novel,
            ),
        )
    }

    override fun savedFlags(): Set<MigrationDataFlag> {
        return NovelMigrationFlag.fromBits(novelPreferences.novelMigrationFlags().get())
            .map { it.toNeutral() }
            .toSet()
    }

    override fun persistFlags(flags: Set<MigrationDataFlag>) {
        novelPreferences.novelMigrationFlags().set(
            NovelMigrationFlag.toBits(
                flags.mapTo(HashSet()) {
                    it.toNovelFlag()
                },
            ),
        )
    }

    override suspend fun applicableFlags(entries: List<MigrationEntry>): Set<MigrationDataFlag> {
        val novels = entries.mapNotNull { it.payload as? Novel }
        return MigrationDataFlag.entries.filterTo(LinkedHashSet()) { flag ->
            when (flag) {
                MigrationDataFlag.CHAPTER -> true
                MigrationDataFlag.CATEGORY -> true
                MigrationDataFlag.CUSTOM_COVER -> novels.any { it.hasCustomCover(coverCache) }
                MigrationDataFlag.NOTES -> novels.any { it.notes.isNotBlank() }
                MigrationDataFlag.REMOVE_DOWNLOAD -> novels.any { downloadManager.getDownloadCount(it) > 0 }
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
        val current = entry.payload as? Novel ?: error("novel entry payload missing")
        val handle = target.handle as? NovelCandidateHandle
        val targetNovel = handle?.stored ?: error("novel target not resolved")
        // The use case silently no-ops on both of these; surface them as row failures instead of
        // marking a row migrated when nothing happened.
        check(current.id != targetNovel.id) { "target is the entry itself" }
        checkNotNull(sourceManager.get(targetNovel.source)) { "target source is not installed" }
        migrateNovel(
            current,
            targetNovel,
            flags.mapTo(HashSet()) { it.toNovelFlag() },
            replace,
            skipTargetRefresh = targetJustSynced,
        )
    }

    private fun Novel.toCover(site: String?, favorite: Boolean = true) = NovelCover(
        url = thumbnailUrl,
        site = site,
        isNovelFavorite = favorite,
        lastModified = coverLastModified,
        novelId = id,
    )

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
