package reikai.presentation

import android.os.Bundle
import android.os.Parcel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.rememberResultEventBusNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import kotlinx.serialization.Serializable
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.Navigator
import mihon.core.navigation.NavigatorDisplay

/** The root a test's navigator starts on, when the test only needs a navigator to exist. */
@Serializable
data object TestRootRoute : NavKey

/** A page that is none of the ones a rule under test looks for. */
@Serializable
data object OtherTestRoute : NavKey

/**
 * A navigator over [routes], for a rule that only reads and edits the stack. Its constructor is internal to
 * the navigation module, which only Kotlin enforces, so a test builds one through reflection.
 */
fun testNavigator(vararg routes: NavKey): Navigator =
    Navigator::class.java.declaredConstructors.single().newInstance(NavBackStack(*routes)) as Navigator

/**
 * Draws [navigator] as the app does, providing it to the pages, each route with its own ViewModel store and the
 * host's result bus, so a test can push, pop and restore real routes. Hosted inside [ComposeTestHost], which provides the bus.
 */
@Composable
fun TestNavHost(navigator: Navigator, entries: EntryProviderScope<NavKey>.() -> Unit) {
    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavigatorDisplay(
            navigator = navigator,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
                rememberResultEventBusNavEntryDecorator(resultEventBus = LocalResultEventBus.current),
            ),
            entryProvider = entryProvider(builder = entries),
        )
    }
}

/**
 * [route] through the path a back stack is saved by (NavKeySerializer into a SavedState) and a Parcel, which is
 * what process death adds over a configuration change. Needs Robolectric for the Bundle and the Parcel.
 */
fun roundTrip(route: NavKey): NavKey {
    val saved = encodeToSavedState(NavKeySerializer<NavKey>(), route)
    val parcel = Parcel.obtain()
    try {
        parcel.writeBundle(saved)
        parcel.setDataPosition(0)
        val read = checkNotNull(parcel.readBundle(Bundle::class.java.classLoader))
        return decodeFromSavedState(NavKeySerializer<NavKey>(), read)
    } finally {
        parcel.recycle()
    }
}
