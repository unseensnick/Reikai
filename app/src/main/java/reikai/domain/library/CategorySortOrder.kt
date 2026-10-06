package reikai.domain.library

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * How every category list is ordered, library-wide. [stored] is the Int persisted under
 * `pref_category_sort_order` and carried verbatim by backups, so an order never changes its number.
 * A to Z and Z to A keep the system category on top; dynamic groups only honour the reversal.
 */
enum class CategorySortOrder(val stored: Int, val titleRes: StringResource) {
    MANUAL(0, MR.strings.category_sort_off),
    A_TO_Z(1, MR.strings.category_sort_a_to_z),
    Z_TO_A(2, MR.strings.category_sort_z_to_a),
    ;

    companion object {
        /** A number this build does not know (a newer backup) reads as the default, [MANUAL]. */
        fun fromStored(value: Int): CategorySortOrder = entries.firstOrNull { it.stored == value } ?: MANUAL
    }
}
