package reikai.presentation.category

import dev.icerock.moko.resources.StringResource
import reikai.domain.category.CategoryContentType
import tachiyomi.i18n.MR

/** Which libraries a category with this [CategoryContentType] applies to, as the categories UI names it. */
fun categoryContentTypeLabel(contentType: Long): StringResource = when (contentType) {
    CategoryContentType.MANGA -> MR.strings.category_content_type_manga
    CategoryContentType.NOVEL -> MR.strings.category_content_type_novels
    else -> MR.strings.category_content_type_all
}
