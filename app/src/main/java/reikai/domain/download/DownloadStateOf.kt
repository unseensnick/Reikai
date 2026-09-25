package reikai.domain.download

import eu.kanade.tachiyomi.data.download.model.Download

/**
 * A chapter's download state as every Reikai row reads it, for both content types: a queued
 * download's own state wins, then the on-disk index. [isOnDisk] runs only when nothing is queued,
 * since reading the index is the costly half. Mihon's own rows keep their copy in upstream shape.
 */
inline fun downloadStateOf(queued: Download.State?, isOnDisk: () -> Boolean): Download.State = when {
    queued != null -> queued
    isOnDisk() -> Download.State.DOWNLOADED
    else -> Download.State.NOT_DOWNLOADED
}
