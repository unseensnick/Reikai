package reikai.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.retain.retain
import androidx.navigation3.runtime.result.ResultEventBus

/**
 * The app's one result bus, retained across a configuration change so a result sent just before a
 * rotation still arrives. Upstream remembers it, which drops buffered results with the activity
 * (upstream-sync.md, Deliberate divergences). Process death still drops them, so a result that has to
 * survive it is keyed by something the screens save, as the migration pick's flow id is.
 */
@Composable
fun retainAppResultEventBus(): ResultEventBus = retain { ResultEventBus() }
