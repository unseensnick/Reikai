package reikai.domain.details

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** The screen edge the details Start/Resume button docks to, the same on either layout direction. */
enum class ReadButtonSide(val titleRes: StringResource) {
    LEFT(MR.strings.nav_zone_left),
    RIGHT(MR.strings.nav_zone_right),
}
