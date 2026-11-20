package reikai.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey

/**
 * Gives each bottom-bar tab a ViewModel store of its own that survives switching away, so the Updates, History
 * and Recents tabs keep separate feed models. Upstream's tabs share Home's store, and lifecycle's own decorator
 * clears a tab's store as a switch pops it. The stores live in Home's entry, under a plain provider, since the
 * remembered one clears every store when a page pushed over Home takes it out of composition. Such a store
 * clears only when asked, so the [dropped] tabs, those no longer in the bar, are cleared here, or their models
 * would keep collecting while Home lives. Tabs share Home's saved-state registry, which only readers read.
 */
@Composable
fun rememberTabViewModelStoresDecorator(dropped: List<NavKey>): NavEntryDecorator<NavKey> {
    val parent = checkNotNull(LocalViewModelStoreOwner.current) { "Home has no ViewModel store" }
    val provider = remember(parent) { ViewModelStoreProvider(parent, "reikai.tabViewModelStores") }
    LaunchedEffect(provider, dropped) {
        // A store is keyed by its entry's content key, which a NavEntry derives from the route alone.
        dropped.forEach { tab -> provider.clearKey(NavEntry(tab) {}.contentKey) }
    }
    return remember(provider) {
        NavEntryDecorator { entry ->
            val owner = rememberViewModelStoreOwner(entry.contentKey, provider)
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { entry.Content() }
        }
    }
}
