package reikai.data.cache

import eu.kanade.tachiyomi.data.cache.CoverCache
import java.io.File

/**
 * Where a novel's custom cover sat before version 186 namespaced it: the Long-keyed name over the negated
 * id, which only kept it apart from a same-id manga. Read only to move such a file onto its current name.
 */
fun CoverCache.legacyNovelCustomCoverFile(novelId: Long): File = getCustomCoverFile(-novelId)
