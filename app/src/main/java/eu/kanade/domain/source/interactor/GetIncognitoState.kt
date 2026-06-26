package eu.kanade.domain.source.interactor

import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import exh.source.EH_PACKAGE
import exh.source.eHentaiSourceIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

class GetIncognitoState(
    private val basePreferences: BasePreferences,
    private val sourcePreferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
) {
    fun await(sourceId: Long?): Boolean {
        if (basePreferences.incognitoMode.get()) return true
        if (sourceId == null) return false
        // RK: the built-in E-Hentai sources have no installed extension, so map them to EH_PACKAGE.
        val extensionPackage = if (sourceId in eHentaiSourceIds) {
            EH_PACKAGE
        } else {
            extensionManager.getExtensionPackage(sourceId) ?: return false
        }

        return extensionPackage in sourcePreferences.incognitoExtensions.get()
    }

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
