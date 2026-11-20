package reikai.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.ViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey

/**
 * Gives each bottom-bar tab a ViewModel store of its own that survives switching away, so the Updates, History
 * and Recents tabs keep separate feed models. Upstream's tabs share Home's store, and lifecycle's own decorator
 * clears a tab's store as a switch pops it. The stores live in
 * Home's entry, and a plain provider is used because the remembered one clears every store when Home leaves
 * composition, which it does whenever a page is pushed over it. Tabs share Home's saved-state registry,
 * which only the reader models read.
 */
@Composable
fun rememberTabViewModelStoresDecorator(): NavEntryDecorator<NavKey> {
    val parent = checkNotNull(LocalViewModelStoreOwner.current) { "Home has no ViewModel store" }
    val provider = remember(parent) { ViewModelStoreProvider(parent, "reikai.tabViewModelStores") }
    return remember(provider) {
        NavEntryDecorator { entry ->
            val owner = rememberViewModelStoreOwner(entry.contentKey, provider)
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { entry.Content() }
        }
    }
}
