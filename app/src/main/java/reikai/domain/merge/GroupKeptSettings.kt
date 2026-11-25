package reikai.domain.merge

/**
 * Every setting a merged series keeps once, in one list both types build from their own halves, so a setting
 * added here reaches every merge and re-add of both. A merge hands the settings owner's to the members joining
 * ([adoptOwner]); a re-add gives a returning member the group's chapter settings and reader flags ([rejoin]),
 * while its categories come from the add itself, which resolves through the adders' landingCategories.
 */
class GroupKeptSettings(
    private val chapterSettings: GroupChapterSettings<*>,
    private val viewerFlags: GroupChapterSettings<*>,
    private val categories: GroupCategories,
) {

    /** After a merge, with the group's library members in order, the settings owner first. */
    suspend fun adoptOwner(memberIds: List<Long>) {
        chapterSettings.adoptOwnerSetting(memberIds)
        viewerFlags.adoptOwnerSetting(memberIds)
        categories.adoptOwnerCategories(memberIds)
    }

    /** [add], after which a series back in the library takes its group's chapter settings and reader flags. */
    suspend fun <T> rejoin(id: Long, mergeManager: EntryMergeManager, add: suspend () -> T): T =
        chapterSettings.rejoin(id, mergeManager) { viewerFlags.rejoin(id, mergeManager, add) }
}
