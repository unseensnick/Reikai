package reikai.domain.library

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/** The name a content type goes by wherever it is labelled: chips, tabs, row badges, the update-error dump. */
val ContentType.labelRes: StringResource
    get() = when (this) {
        ContentType.ALL -> MR.strings.content_type_all
        ContentType.MANGA -> MR.strings.content_type_manga
        ContentType.NOVELS -> MR.strings.content_type_novels
    }
