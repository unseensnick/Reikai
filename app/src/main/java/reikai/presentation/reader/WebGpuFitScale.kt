package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences.ScaleType

/**
 * The home zoom the high quality renderer gives a [width] x [height] page on a [screenWidth] x [screenHeight] screen,
 * or null where the library's own fit to the screen stands. Smart fit is the classic viewer's (the subsampling
 * view's `minScale`): a page taller than wide fills the width, any other fills the height.
 */
fun fitHomeScale(scaleType: ScaleType, screenWidth: Float, screenHeight: Float, width: Float, height: Float): Float? =
    when (scaleType) {
        ScaleType.FIT_WIDTH -> screenWidth / width
        ScaleType.FIT_HEIGHT -> screenHeight / height
        ScaleType.ORIGINAL_SIZE -> 1f
        ScaleType.SMART_FIT -> if (height > width) screenWidth / width else screenHeight / height
        ScaleType.FIT_SCREEN, ScaleType.STRETCH -> null
    }
