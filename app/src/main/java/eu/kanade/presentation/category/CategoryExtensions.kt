package eu.kanade.presentation.category

import android.content.Context
import androidx.compose.runtime.Composable
import dev.icerock.moko.resources.StringResource
import reikai.domain.category.CategoryContentType
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

val Category.visualName: String
    @Composable
    get() = when {
        isSystemCategory -> stringResource(MR.strings.label_default)
        else -> name
    }

fun Category.visualName(context: Context): String =
    when {
        isSystemCategory -> context.stringResource(MR.strings.label_default)
        else -> name
    }

// RK: which libraries this category applies to, for the edit-categories row's secondary line.
val Category.contentTypeLabel: StringResource
    get() = when (contentType) {
        CategoryContentType.MANGA -> MR.strings.category_content_type_manga
        CategoryContentType.NOVEL -> MR.strings.category_content_type_novels
        else -> MR.strings.category_content_type_all
    }
