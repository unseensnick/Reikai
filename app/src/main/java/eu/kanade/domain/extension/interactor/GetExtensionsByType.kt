package eu.kanade.domain.extension.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import mihon.domain.extension.model.ContentWarning

@Inject
class GetExtensionsByType(
    private val preferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
) {

    // RK --> one partition for both apk kinds, over each kind's own lists
    fun subscribe(kind: Extension.Kind = Extension.Kind.MANGA): Flow<Extensions> {
        val enabledContentWarnings = preferences.enabledContentWarnings.get()
        val isManga = kind == Extension.Kind.MANGA

        return combine(
            preferences.enabledLanguages.changes(),
            if (isManga) extensionManager.loadedExtensionsFlow else extensionManager.loadedNovelExtensionsFlow,
            if (isManga) extensionManager.notLoadedExtensionsFlow else extensionManager.notLoadedNovelExtensionsFlow,
            if (isManga) extensionManager.availableExtensionsFlow else extensionManager.availableNovelExtensionsFlow,
        ) { enabledLanguages, loaded, notLoaded, available ->
            // The language filter is the manga list's own, so it has nothing to say about novels.
            partitionExtensions(
                if (isManga) enabledLanguages else null,
                enabledContentWarnings,
                loaded,
                notLoaded,
                available,
            )
        }
    }
    // RK <--
}

// RK --> the interactor's partition, lifted out so both kinds share it; null languages keep every language
internal fun partitionExtensions(
    enabledLanguages: Set<String>?,
    enabledContentWarnings: Set<ContentWarning>,
    installed: List<Extension.Loaded>,
    failed: List<Extension.NotLoaded>,
    offered: List<Extension.Available>,
): Extensions {
    val byName = compareBy<Extension, String>(String.CASE_INSENSITIVE_ORDER) { it.name }

    val (loadedUpdates, loaded) = installed.partition { it.hasUpdate }
    val (notLoadedUpdates, notLoaded) = failed.partition { it.hasUpdate }

    val updates = (loadedUpdates + notLoadedUpdates).sortedWith(byName)

    val available = offered
        .filter { extension ->
            installed.none { it.pkgName == extension.pkgName } &&
                failed.none { it.pkgName == extension.pkgName } &&
                extension.contentWarning in enabledContentWarnings
        }
        .flatMap { ext ->
            ext.sources.filter { enabledLanguages == null || it.lang in enabledLanguages }
                .map {
                    ext.copy(
                        name = it.name,
                        lang = it.lang,
                        pkgName = "${ext.pkgName}-${it.id}",
                        sources = listOf(it),
                    )
                }
        }
        .sortedWith(byName)

    val updateVersions = updates.mapNotNull { update ->
        offered.find { it.pkgName == update.pkgName }?.let { update.pkgName to it.versionName }
    }.toMap()

    return Extensions(
        updates = updates,
        loaded = loaded.sortedWith(compareBy<Extension.Loaded> { !it.isObsolete }.then(byName)),
        available = available,
        notLoaded = notLoaded.sortedWith(byName),
        updateVersions = updateVersions,
    )
}
// RK <--
