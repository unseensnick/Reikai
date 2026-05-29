package yokai.presentation.details.manga

import android.os.Bundle
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController

/**
 * Conductor host for the Compose [MangaDetailsScreen]. The Compose library pushes this controller
 * (the library navigates via the Conductor router, not a Voyager navigator); it sets up the
 * Voyager [Navigator] so the screen and any internal pushes run on the Voyager stack.
 *
 * The primary constructor takes a [Bundle] so Conductor can restore the controller after process
 * death (every Controller needs a Bundle or no-arg constructor); the [Long] convenience
 * constructor packs the id into the args bundle. Mirrors the legacy
 * [eu.kanade.tachiyomi.ui.manga.MangaDetailsController] bundle pattern.
 */
class MangaDetailsComposeController(bundle: Bundle) : BaseComposeController(bundle) {

    constructor(mangaId: Long) : this(Bundle().apply { putLong(MANGA_ID_EXTRA, mangaId) })

    private val mangaId: Long = bundle.getLong(MANGA_ID_EXTRA, -1L)

    @Composable
    override fun ScreenContent() {
        Navigator(
            screen = MangaDetailsScreen(mangaId),
            content = { CrossfadeTransition(navigator = it) },
        )
    }

    companion object {
        const val MANGA_ID_EXTRA = "manga_id"
    }
}
