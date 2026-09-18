package reikai.domain.library

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import reikai.data.novel.NovelStatusCode
import reikai.domain.novel.model.LibraryNovel
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_HAS_UNREAD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_NON_READ
import tachiyomi.domain.library.service.LibraryPreferences.Companion.MANGA_OUTSIDE_RELEASE_PERIOD
import tachiyomi.i18n.MR

/** Why a library update leaves an entry out, one per Smart update rule. */
enum class SmartUpdateSkip(val reason: StringResource) {
    NOT_ALWAYS_UPDATE(MR.strings.skipped_reason_not_always_update),
    COMPLETED(MR.strings.skipped_reason_completed),
    NOT_CAUGHT_UP(MR.strings.skipped_reason_not_caught_up),
    NOT_STARTED(MR.strings.skipped_reason_not_started),
    NOT_IN_RELEASE_PERIOD(MR.strings.skipped_reason_not_in_release_period),
}

/** What the Smart update rules read off one library entry, whichever content type it is. */
data class SmartUpdateFacts(
    val fetchesOnce: Boolean,
    val isCompleted: Boolean,
    val totalChapters: Long,
    val unreadCount: Long,
    val hasStarted: Boolean,
    val nextUpdate: Long,
)

/**
 * The first rule that skips an entry, in the order upstream's manga job checks them, or null when it is
 * fetched. Both library update jobs call this, so an upstream change to that job's `when` lands here.
 */
fun smartUpdateSkip(facts: SmartUpdateFacts, restrictions: Set<String>, fetchWindowEnd: Long): SmartUpdateSkip? =
    when {
        facts.fetchesOnce && facts.totalChapters > 0L -> SmartUpdateSkip.NOT_ALWAYS_UPDATE
        else -> smartUpdateProgressSkip(facts, restrictions)
            ?: SmartUpdateSkip.NOT_IN_RELEASE_PERIOD.takeIf {
                MANGA_OUTSIDE_RELEASE_PERIOD in restrictions && facts.nextUpdate > fetchWindowEnd
            }
    }

/**
 * The restrictions that read status and reading progress, which Statistics counts on their own as
 * upstream's does. An entry with no chapters yet is never unstarted.
 */
fun smartUpdateProgressSkip(facts: SmartUpdateFacts, restrictions: Set<String>): SmartUpdateSkip? = when {
    MANGA_NON_COMPLETED in restrictions && facts.isCompleted -> SmartUpdateSkip.COMPLETED
    MANGA_HAS_UNREAD in restrictions && facts.unreadCount != 0L -> SmartUpdateSkip.NOT_CAUGHT_UP
    MANGA_NON_READ in restrictions && facts.totalChapters > 0L && !facts.hasStarted -> SmartUpdateSkip.NOT_STARTED
    else -> null
}

fun LibraryManga.smartUpdateFacts() = SmartUpdateFacts(
    fetchesOnce = manga.updateStrategy == UpdateStrategy.ONLY_FETCH_ONCE,
    isCompleted = manga.status.toInt() == SManga.COMPLETED,
    totalChapters = totalChapters,
    unreadCount = unreadCount,
    hasStarted = hasStarted,
    nextUpdate = manga.nextUpdate,
)

fun LibraryNovel.smartUpdateFacts() = SmartUpdateFacts(
    fetchesOnce = novel.updateStrategy == UpdateStrategy.ONLY_FETCH_ONCE,
    isCompleted = novel.status.toInt() == NovelStatusCode.COMPLETED,
    totalChapters = totalChapters,
    unreadCount = unreadCount,
    hasStarted = hasStarted,
    nextUpdate = novel.nextUpdate,
)
