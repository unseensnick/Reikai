package eu.kanade.domain.source.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import exh.source.EH_PACKAGE
import exh.source.eHentaiSourceIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import reikai.domain.source.SourceKey // RK
import reikai.novel.source.isNovelAppSourceId // RK

@Inject
class GetIncognitoState(
    private val basePreferences: BasePreferences,
    private val sourcePreferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
) {
    suspend fun await(sourceId: Long?): Boolean {
        if (basePreferences.incognitoMode.get()) return true
        if (sourceId == null) return false
        // RK: the package lookup moved into extensionPackage, which the SourceKey overloads share.
        val extensionPackage = extensionPackage(sourceId) ?: return false

        return extensionPackage in sourcePreferences.incognitoExtensions.get()
    }

    // RK --> incognito per source for both content types, keyed by SourceKey
    suspend fun await(source: SourceKey?): Boolean = when (source) {
        is SourceKey.Novel ->
            basePreferences.incognitoMode.get() || incognitoKey(source) in sourcePreferences.incognitoExtensions.get()
        is SourceKey.Manga -> await(source.id)
        null -> await(null as Long?)
    }

    fun subscribe(source: SourceKey?): Flow<Boolean> = when (source) {
        is SourceKey.Novel -> combine(
            basePreferences.incognitoMode.changes(),
            sourcePreferences.incognitoExtensions.changes(),
            novelIncognitoKey(source),
        ) { incognito, incognitoExtensions, key -> incognito || key in incognitoExtensions }
            .distinctUntilChanged()
        is SourceKey.Manga -> subscribe(source.id)
        null -> subscribe(null as Long?)
    }

    /**
     * The entry [source] is stored under in the incognito set, or null when nothing can be: a manga
     * source is switched with its whole extension, as upstream does, so one without an installed
     * extension (local, stub) has none. A novel apk's source goes with its apk the same way. A plugin
     * is its own extension and has no package, so it is stored in its SourceKey form, which no package
     * name can take.
     */
    suspend fun incognitoKey(source: SourceKey): String? = when (source) {
        is SourceKey.Manga -> extensionPackage(source.id)
        is SourceKey.Novel -> novelIncognitoKey(source).first()
    }

    private fun novelIncognitoKey(source: SourceKey.Novel): Flow<String?> {
        if (!isNovelAppSourceId(source.id)) return flowOf(source.serialize())
        return extensionManager.getNovelExtensionPackageAsFlow(source.id)
    }

    // The built-in E-Hentai sources have no installed extension, so they map to EH_PACKAGE.
    private suspend fun extensionPackage(sourceId: Long): String? =
        if (sourceId in eHentaiSourceIds) EH_PACKAGE else extensionManager.getExtensionPackage(sourceId)
    // RK <--

    fun subscribe(sourceId: Long?): Flow<Boolean> {
        if (sourceId == null) return basePreferences.incognitoMode.changes()

        // RK: EH sources resolve to EH_PACKAGE (no installed extension to look up).
        val packageFlow = if (sourceId in eHentaiSourceIds) {
            flowOf(EH_PACKAGE)
        } else {
            extensionManager.getExtensionPackageAsFlow(sourceId)
        }
        return combine(
            basePreferences.incognitoMode.changes(),
            sourcePreferences.incognitoExtensions.changes(),
            packageFlow,
        ) { incognito, incognitoExtensions, extensionPackage ->
            incognito || (extensionPackage in incognitoExtensions)
        }
            .distinctUntilChanged()
    }
}
