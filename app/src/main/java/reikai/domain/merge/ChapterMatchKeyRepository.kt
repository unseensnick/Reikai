package reikai.domain.merge

/**
 * Storage for the precomputed cross-source chapter identities behind a merged entry's deduplicated
 * unread count. See `data/chapter_match_key.sq` for why the mapping is persisted and why it is kept
 * current by reconciliation rather than by hooking every chapter write.
 */
interface ChapterMatchKeyRepository {

    /** Chapters of merged manga whose key is missing or was derived from a since-changed number. */
    suspend fun getStaleMangaChapters(): List<StaleChapter>

    /** Chapters of merged novels whose key is missing or was derived from a since-changed row. */
    suspend fun getStaleNovelChapters(): List<StaleNovelChapter>

    /** Writes the given keys in one transaction. A null [StaleChapter.matchKey] means "never dedup". */
    suspend fun upsertMangaKeys(keys: List<ResolvedKey>)

    suspend fun upsertNovelKeys(keys: List<ResolvedNovelKey>)

    /** Unread count per merge group, or an absent entry when the whole group is read. */
    suspend fun getMergedUnreadCounts(): Map<Long, Long>

    suspend fun getMergedUnreadCountsNovel(): Map<Long, Long>

    data class StaleChapter(val chapterId: Long, val mangaId: Long, val chapterNumber: Double)

    data class StaleNovelChapter(
        val chapterId: Long,
        val novelId: Long,
        val name: String,
        val chapterNumber: Double,
    )

    data class ResolvedKey(val chapterId: Long, val matchKey: String?, val chapterNumber: Double)

    data class ResolvedNovelKey(
        val chapterId: Long,
        val matchKey: String?,
        val name: String,
        val chapterNumber: Double,
    )
}
