package reikai.presentation.browse.catalogue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Runs [refresh] once when this screen next resumes, after the returned call arms it: the catalogue's
 * pages are cached in its model, so a screen pushed over it that changes the listing has to ask for
 * the re-run. Survives the activity stop.
 */
@Composable
internal fun rememberRefreshOnReturn(refresh: () -> Unit): () -> Unit {
    var pending by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (pending) {
            pending = false
            refresh()
        }
    }
    return { pending = true }
}
