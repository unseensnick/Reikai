package reikai.domain.source

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource

/** [manga]'s page as its extension addresses it, or null when that rule throws, as one may on a path it
 *  did not produce. */
internal fun HttpSource.mangaUrlOrNull(manga: SManga): String? = runCatching { getMangaUrl(manga) }.getOrNull()
