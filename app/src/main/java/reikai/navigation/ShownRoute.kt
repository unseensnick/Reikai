package reikai.navigation

import androidx.navigation3.runtime.NavKey
import mihon.core.navigation.Navigator
import reikai.presentation.track.EntryTrackSheetRoute

/** The page the user is on: the top route, or the details page under the track sheet drawn over it. */
val Navigator.shownRoute: NavKey
    get() = routes.last { it !is EntryTrackSheetRoute }
