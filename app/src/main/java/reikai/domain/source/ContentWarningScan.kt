package reikai.domain.source

import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import mihon.domain.extension.model.ContentWarning

/** The content-warning settings an installed-extension scan judges every extension against. */
data class ContentWarningScan(
    val enabled: Set<ContentWarning>,
    val applyToInstalled: Boolean,
)

fun SourcePreferences.contentWarningScan() =
    ContentWarningScan(enabledContentWarnings.get(), applyContentWarningsToInstalled.get())

fun SourcePreferences.contentWarningScanChanges(): Flow<ContentWarningScan> =
    combine(enabledContentWarnings.changes(), applyContentWarningsToInstalled.changes(), ::ContentWarningScan)

/**
 * Re-scans whenever the settings differ from the ones the last finished scan used, read through [scanned].
 * Compared against the scan rather than the previous emission, because a write landing while the
 * first scan runs (the upgrade carry, a backup restore) is already current when this subscribes.
 * Decided inside collectLatest, not in a filter ahead of it: a value that arrives while the reload
 * it started still waits for the scan lock cancels that reload, then compares against the real scan.
 */
suspend fun Flow<ContentWarningScan>.reloadWhenScanStale(
    scanned: () -> ContentWarningScan?,
    reload: suspend () -> Unit,
) {
    collectLatest { if (it != scanned()) reload() }
}
