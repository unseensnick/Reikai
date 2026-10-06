package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation

/**
 * The rotations a reader default, or the rotation picker's grid, offers: every one but Default, which
 * only means something on a series, as "follow the reader's default". Both content types offer the same.
 */
val readerOrientationChoices: List<ReaderOrientation> = ReaderOrientation.entries - ReaderOrientation.DEFAULT

/**
 * The rotation the reader applies for an entry stored as [entryFlag]: its own, or [defaultFlag] when it
 * follows the default. An unknown flag follows the default too, as Mihon's `getMangaOrientation` does.
 */
fun resolveOrientation(entryFlag: Int, defaultFlag: Int): Int =
    ReaderOrientation.fromPreference(entryFlag).takeIf { it != ReaderOrientation.DEFAULT }?.flagValue ?: defaultFlag
