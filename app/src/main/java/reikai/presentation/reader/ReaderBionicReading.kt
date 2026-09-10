package reikai.presentation.reader

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.Flow

/**
 * Bolding the opening of each word, for a content type whose text the reader draws. Null for one
 * whose pages are images, so the bar button is absent rather than present and dead.
 *
 * Separate from [ReaderTextSettings], which the two typography dialogs read: this is a bar toggle
 * with no screen of its own, and the bar needs its state without opening anything.
 */
@Stable
interface ReaderBionicReading {

    val enabled: Flow<Boolean>

    /** A flip rather than a set, because the button shows the state it is inverting. */
    fun toggle()
}
