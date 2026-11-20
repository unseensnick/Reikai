package reikai.util

import eu.kanade.tachiyomi.ui.main.MainActivity

/**
 * The main screen under a name that is never a launcher entry. MainActivity's own name is the build's own app icon,
 * as it was in 0.5.1, and goes off while another icon is picked, so every start of the main screen names this class.
 */
class MainActivityTarget : MainActivity()
