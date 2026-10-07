package reikai.domain.chapter

import dev.zacsweers.metro.Inject
import reikai.domain.library.ContentType
import reikai.domain.merge.ReconcileMergedChapters

/**
 * The chapter the number dialog corrects: [number] as stored, [sourceNumber] only when that is a correction,
 * and [suggestion] the number its out-of-line hint offers ([ChapterNumberHint]), which the dialog opens on.
 */
data class ChapterNumberEdit(
    val type: ContentType,
    val ownerId: Long,
    val url: String,
    val name: String,
    val number: Double,
    val sourceNumber: Double?,
    val suggestion: Double?,
)

/** Correcting a chapter's number, and putting the source's back, the same way for both content types. */
@Inject
class EditChapterNumber(
    private val overrides: ChapterNumberOverrideRepository,
    private val reconcileMergedChapters: ReconcileMergedChapters,
) {

    suspend fun edit(type: ContentType, ownerId: Long, url: String, name: String, number: Double, suggestion: Double?) =
        ChapterNumberEdit(
            type,
            ownerId,
            url,
            name,
            number,
            overrides.getByOwner(type, ownerId)[url]?.sourceNumber,
            suggestion,
        )

    /**
     * Stores [number] on the chapter, or the source's own for null or a number equal to it, so a
     * correction back to the source leaves nothing behind. A renumbered chapter leaves a merged series'
     * stitch stale, so the pass reconciles it.
     */
    suspend fun save(edit: ChapterNumberEdit, number: Double?) {
        reconcileMergedChapters.afterPass {
            if (number == null || number == (edit.sourceNumber ?: edit.number)) {
                overrides.clear(edit.type, edit.ownerId, edit.url)
            } else {
                overrides.set(edit.type, edit.ownerId, edit.url, number)
            }
        }
    }
}
