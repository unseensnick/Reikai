package reikai.domain.source

import reikai.novel.source.NovelSourceManager
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager

/**
 * Whether a source is installed, rather than a stub kept so its library entries still resolve, per content
 * type. Downloads and reading route a merged series' chapters by these.
 */
suspend fun SourceManager.isInstalled(sourceId: Long): Boolean = getOrStub(sourceId) !is StubSource

/** A novel source answers after the first plugin load. */
suspend fun NovelSourceManager.isInstalled(sourceId: String): Boolean = get(sourceId) != null
