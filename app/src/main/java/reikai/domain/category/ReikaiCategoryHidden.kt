package reikai.domain.category

import tachiyomi.domain.category.model.Category

/**
 * "Hidden" category state, stored as a single bit in the existing [Category.flags] so no DB
 * migration is needed (decision D5 of the Stage 4 settings sheet).
 *
 * Bit 7 is provably free: Mihon's `LibrarySort` uses only the sort-type bits (mask `0b00111100`)
 * and the direction bit (mask `0b01000000`), so nothing else touches `0b10000000`. Komikku stores
 * hidden in a separate DB column, so its flag masks leave bit 7 alone too; the bit can ride
 * inertly through a Komikku round-trip (see the backup mapping in `BackupCategory`).
 */
const val CATEGORY_HIDDEN_MASK = 0b10000000L

val Category.isHidden: Boolean
    get() = flags and CATEGORY_HIDDEN_MASK == CATEGORY_HIDDEN_MASK

/** New flags value with the hidden bit set or cleared, leaving the sort/direction bits untouched. */
fun Category.flagsWithHidden(hidden: Boolean): Long =
    if (hidden) flags or CATEGORY_HIDDEN_MASK else flags and CATEGORY_HIDDEN_MASK.inv()
