package reikai.domain.extension

import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.includes
import reikai.domain.novel.NovelPreferences

/** What the update notice covers: novel apks share it with manga extensions, and a hidden type leaves it. */
fun <T> extensionsInNotice(shown: ContentType, manga: Collection<T>, novels: Collection<T>): List<T> = buildList {
    if (shown.includes(ContentType.MANGA)) addAll(manga)
    if (shown.includes(ContentType.NOVELS)) addAll(novels)
}

/**
 * Pending extension updates per content type, which the Browse badges read. Novels have two kinds of
 * extension, the LNReader plugins and the apks, and every badge counts both.
 */
@Inject
class ExtensionUpdateCounts(
    sourcePreferences: SourcePreferences,
    novelPreferences: NovelPreferences,
    extensionManager: ExtensionManager,
    reikaiLibraryPreferences: ReikaiLibraryPreferences,
) {
    private val shown = reikaiLibraryPreferences.shownContent.changes()

    val manga: Flow<Int> = combine(sourcePreferences.extensionUpdatesCount.changes(), shown) { count, shown ->
        if (shown.includes(ContentType.MANGA)) count else 0
    }

    val novel: Flow<Int> = combine(
        novelPreferences.pendingPluginUpdates().changes(),
        extensionManager.novelUpdatesCount,
        shown,
    ) { plugins, apks, shown -> if (shown.includes(ContentType.NOVELS)) plugins.size + apks else 0 }

    val total: Flow<Int> = combine(manga, novel) { manga, novel -> manga + novel }
}
