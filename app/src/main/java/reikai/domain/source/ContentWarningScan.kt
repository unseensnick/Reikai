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
 * Re-scans whenever an input (the content-warning settings, what counts as trusted) differs from the one
 * the last finished scan used, read through [scanned]. Compared against the scan rather than the previous
 * emission, because a write landing while the first scan runs (the upgrade carry, a backup restore, a
 * store added) is already current when this subscribes. Decided inside collectLatest, not in a filter
 * ahead of it: a value that arrives while the reload it started still waits for the scan lock cancels
 * that reload, then compares against the real scan.
 */
suspend fun <T> Flow<T>.reloadWhenScanStale(
    scanned: () -> T?,
    reload: suspend () -> Unit,
) {
    collectLatest { if (it != scanned()) reload() }
}
