package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.category.visualName
import reikai.domain.library.ContentType
import reikai.domain.library.includes
import reikai.presentation.components.LocalShownContent
import reikai.presentation.recommendation.SettingsRecommendationsScreen
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

// RK: whether a row or group for [type] shows under the Show setting
@ReadOnlyComposable
@Composable
fun showsContent(type: ContentType): Boolean = LocalShownContent.current.includes(type)

/** The settings screens that belong to one content type, gone from the list and search with it. */
internal val contentTypedScreens: Map<Any, ContentType> = mapOf(
    SettingsMangaReaderScreen to ContentType.MANGA,
    SettingsNovelReaderScreen to ContentType.NOVELS,
    SettingsEhScreen to ContentType.MANGA,
    SettingsMangaDexScreen to ContentType.MANGA,
    SettingsRecommendationsScreen to ContentType.MANGA,
)

// RK: "$category · Manga|Novels" for the content-type sub-group headers, so a duplicated setting reads
// distinctly and its settings-search breadcrumb differs between the two content types.
@ReadOnlyComposable
@Composable
fun contentTypedCategory(category: StringResource, contentType: StringResource): String =
    "${stringResource(category)} · ${stringResource(contentType)}"

/**
 * Returns a string of categories name for settings subtitle
 */
@ReadOnlyComposable
@Composable
fun getCategoriesLabel(
    allCategories: List<Category>,
    included: Set<String>,
    excluded: Set<String>,
): String {
    val context = LocalContext.current

    val includedCategories = included
        .mapNotNull { id -> allCategories.find { it.id == id.toLong() } }
        .sortedBy { it.order }
    val excludedCategories = excluded
        .mapNotNull { id -> allCategories.find { it.id == id.toLong() } }
        .sortedBy { it.order }
    val allExcluded = excludedCategories.size == allCategories.size

    val includedItemsText = when {
        // Some selected, but not all
        includedCategories.isNotEmpty() && includedCategories.size != allCategories.size ->
            includedCategories.joinToString { it.visualName(context) }
        // All explicitly selected
        includedCategories.size == allCategories.size -> stringResource(MR.strings.all)
        allExcluded -> stringResource(MR.strings.none)
        else -> stringResource(MR.strings.all)
    }
    val excludedItemsText = when {
        excludedCategories.isEmpty() -> stringResource(MR.strings.none)
        allExcluded -> stringResource(MR.strings.all)
        else -> excludedCategories.joinToString { it.visualName(context) }
    }
    return stringResource(MR.strings.include, includedItemsText) + "\n" +
        stringResource(MR.strings.exclude, excludedItemsText)
}
