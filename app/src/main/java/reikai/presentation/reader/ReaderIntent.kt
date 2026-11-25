package reikai.presentation.reader

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import reikai.data.novel.tts.NovelTtsSession
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType

/**
 * How the reader host names the entry it was asked to open: a type tag beside a raw id, never a bare
 * `Long`. The two id spaces overlap, so a novel id read as a manga id would open the wrong entry
 * instead of failing, and an Intent extra cannot carry the sealed type itself.
 *
 * The manga path keeps writing upstream's `"manga"` extra as well, since `ReaderViewModel` reads that
 * out of its own `SavedStateHandle`; this tag sits beside it rather than replacing it.
 */
private const val EXTRA_ENTRY_TYPE = "entry_type"
private const val EXTRA_ENTRY_ID = "entry_id"

/** A novel launch that reopens read aloud where it is speaking. */
internal const val EXTRA_FOLLOW_SESSION = "follow_session"

/** A manga launch kept to the chapter's own source in a merged series, read from ReaderViewModel's saved state. */
internal const val EXTRA_SOURCE_SCOPED = "source_scoped"

/** The page a manga launch jumps to once, as ReaderViewModel reads it from its saved state. */
internal const val EXTRA_LAUNCH_PAGE = "launch_page"

/**
 * Every novel launch's action. A PendingIntent is matched by its request code and its intent less the extras, so
 * without it a novel's would equal the manga entry's whose id shares its code, and one tap would open the other.
 */
internal const val ACTION_OPEN_NOVEL = "reikai.reader.OPEN_NOVEL"

fun Intent.putEntryId(entryId: EntryId): Intent = apply {
    putExtra(EXTRA_ENTRY_TYPE, entryId.contentType.name)
    putExtra(EXTRA_ENTRY_ID, entryId.rawId)
}

/** Null when the tag is absent or unusable, which the host treats as a launch it cannot serve. */
fun Intent.readEntryId(): EntryId? =
    entryIdOf(getStringExtra(EXTRA_ENTRY_TYPE), getLongExtra(EXTRA_ENTRY_ID, NO_ID))

/** The sentinel upstream already uses for an absent id, kept so both halves read the same. */
internal const val NO_ID = -1L

/**
 * The decision itself, kept out of [Intent] so it can be tested without the framework. [ContentType]
 * is persisted by name elsewhere, so matching on the name is the same contract the store relies on.
 */
internal fun entryIdOf(typeName: String?, rawId: Long): EntryId? {
    if (rawId == NO_ID) return null
    return when (typeName) {
        ContentType.MANGA.name -> EntryId.Manga(rawId)
        ContentType.NOVELS.name -> EntryId.Novel(rawId)
        // ALL is a filter, never an entry, and an unknown tag is a newer build's intent.
        else -> null
    }
}

/**
 * Whether a launch reaching the open reader asks for what it already shows: the same entry in the same
 * scope, and either no chapter or the one on screen. The chapter it opened with is not the test, since
 * the reader moves on from it, and a relaunch naming it again (a notification, a history row) is then a
 * real request. The scope is read once per session, so a launch asking for another one needs a new session.
 * [requestedFollows] is a launch following read aloud, whose chapter the open reader is already following.
 */
internal fun isSameLaunch(
    requestedEntry: EntryId,
    requestedChapter: Long,
    requestedSourceScoped: Boolean,
    openEntry: EntryId?,
    openChapter: Long,
    openSourceScoped: Boolean,
    requestedFollows: Boolean,
): Boolean = requestedEntry == openEntry &&
    requestedSourceScoped == openSourceScoped &&
    (requestedChapter == NO_ID || requestedChapter == openChapter || requestedFollows)

/**
 * Whether a launch [following] read aloud into [entry] follows what [session] reads, in whichever view it
 * reads it: a Continue on one novel while another is spoken asks for its own resume chapter.
 */
internal fun followsReadAloud(
    following: Boolean,
    entry: EntryId,
    session: ReadAloudSessionState,
): Boolean = following && entry is EntryId.Novel && session.series?.touches(entry.rawId) == true

/** The reader on [chapterId] as a resume opens it (Continue, Stats, the details Resume), following read aloud
 *  while it is going; the reader keeps to [chapterId] when read aloud reads another series. */
fun novelResumeIntent(context: Context, novelId: Long, chapterId: Long, sourceScoped: Boolean = false): Intent =
    ReaderActivity.newNovelIntent(context, novelId, chapterId, sourceScoped, followSession = NovelTtsSession.isActive())

/** The entry and scope the open reader reads: a novel reader's own view, which a follow can have changed from
 *  its launch's, else the launch's. */
internal fun openView(
    intentEntry: EntryId?,
    intentSourceScoped: Boolean,
    novelView: Pair<Long, Boolean>?,
): Pair<EntryId?, Boolean> =
    novelView?.let { (id, scoped) -> EntryId.Novel(id) to scoped } ?: (intentEntry to intentSourceScoped)

/** The entry and scope a launch of [entry] would open, by the rule a novel reader opens its own by
 *  ([ReadAloudSessionState.viewFor]), so a follow read aloud redirected compares as the reader it produced. */
internal fun requestedView(
    entry: EntryId,
    sourceScoped: Boolean,
    follows: Boolean,
    session: ReadAloudSessionState,
): Pair<EntryId, Boolean> = when (entry) {
    is EntryId.Novel -> session.viewFor(entry.rawId, sourceScoped, follows).let { (id, scoped) ->
        EntryId.Novel(id) to
            scoped
    }
    else -> entry to sourceScoped
}
