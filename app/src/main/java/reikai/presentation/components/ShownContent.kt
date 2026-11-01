package reikai.presentation.components

import androidx.compose.runtime.compositionLocalOf
import reikai.domain.library.ContentType

/**
 * The Show setting, provided once at the root so a chip row or a label can leave itself out without
 * every screen passing it down. Which rows a screen lists comes from its model, not from here.
 */
val LocalShownContent = compositionLocalOf { ContentType.ALL }
