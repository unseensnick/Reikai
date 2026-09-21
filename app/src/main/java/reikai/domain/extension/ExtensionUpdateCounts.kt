package reikai.domain.extension

import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.novel.NovelPreferences

/**
 * Pending extension updates per content type, which the Browse badges read. Novels have two kinds of
 * extension, the LNReader plugins and the apks, and every badge counts both.
 */
@Inject
class ExtensionUpdateCounts(
    sourcePreferences: SourcePreferences,
    novelPreferences: NovelPreferences,
    extensionManager: ExtensionManager,
) {
    val manga: Flow<Int> = sourcePreferences.extensionUpdatesCount.changes()

    val novel: Flow<Int> = combine(
        novelPreferences.pluginUpdatesCount().changes(),
        extensionManager.novelUpdatesCount,
    ) { plugins, apks -> plugins + apks }

    val total: Flow<Int> = combine(manga, novel) { manga, novel -> manga + novel }
}
