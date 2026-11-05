package reikai.presentation.local

import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Files opened with or shared to the app while the import screen is open, which that screen adds to its
 * own picks rather than being replaced. Buffered, so files sent before the screen's model exists still land.
 */
@Inject
@SingleIn(AppScope::class)
class ImportHandOff {
    private val channel = Channel<List<Uri>>(Channel.UNLIMITED)

    val files: Flow<List<Uri>> = channel.receiveAsFlow()

    fun send(uris: List<Uri>) {
        channel.trySend(uris)
    }
}
