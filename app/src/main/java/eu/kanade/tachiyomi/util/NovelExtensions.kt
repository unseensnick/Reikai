package eu.kanade.tachiyomi.util

import android.app.Activity
import eu.kanade.tachiyomi.data.database.models.sortedByLibraryCategoryPref
import eu.kanade.tachiyomi.ui.category.addtolibrary.SetNovelCategoriesSheet
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.widget.TriStateCheckBox
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.domain.novel.models.Novel

/**
 * Novel-side counterpart to [MangaExtensions]'s `moveCategories` family. Opens
 * [SetNovelCategoriesSheet] for a single novel or a multi-selection, preserving the manga
 * surface's affordances (tri-state checkboxes when the selection spans mixed memberships,
 * category list sorted by the user's `categorySortOrder` pref).
 */

suspend fun Novel.moveCategories(activity: Activity, onNovelMoved: () -> Unit) {
    moveCategories(activity, false, onNovelMoved)
}

suspend fun Novel.moveCategories(
    activity: Activity,
    addingToLibrary: Boolean,
    onNovelMoved: () -> Unit,
) {
    val getNovelCategories: GetNovelCategories = Injekt.get()
    val novelPrefs: NovelPreferences = Injekt.get()
    val categories = getNovelCategories.await()
        .sortedByLibraryCategoryPref(novelPrefs.categorySortOrder().get())
    val categoriesForNovel = this.id?.let { id -> getNovelCategories.awaitByNovelId(id) }.orEmpty()
    val ids = categoriesForNovel.mapNotNull { it.id }.toTypedArray()
    withUIContext {
        SetNovelCategoriesSheet(
            activity,
            this@moveCategories,
            categories.toMutableList(),
            ids,
            addingToLibrary,
        ) {
            onNovelMoved()
        }.show()
    }
}

suspend fun List<Novel>.moveCategories(
    activity: Activity,
    onNovelMoved: () -> Unit,
) {
    if (this.isEmpty()) return

    val getNovelCategories: GetNovelCategories = Injekt.get()
    val novelPrefs: NovelPreferences = Injekt.get()
    val categories = getNovelCategories.await()
        .sortedByLibraryCategoryPref(novelPrefs.categorySortOrder().get())
    val novelCategories = map { novel ->
        novel.id?.let { id -> getNovelCategories.awaitByNovelId(id) }.orEmpty()
    }
    // Common categories: in every novel's category set. Mixed: in some but not all. The sheet
    // renders mixed as the IGNORE tri-state so toggling preserves the per-novel membership for
    // unaffected novels.
    val commonCategories = novelCategories
        .reduce { set1, set2 -> set1.intersect(set2.toSet()).toMutableList() }
        .toSet()
    val mixedCategories = novelCategories.flatten().distinct().subtract(commonCategories).toMutableList()

    withUIContext {
        SetNovelCategoriesSheet(
            activity,
            this@moveCategories,
            categories.toMutableList(),
            categories.map {
                when (it) {
                    in commonCategories -> TriStateCheckBox.State.CHECKED
                    in mixedCategories -> TriStateCheckBox.State.IGNORE
                    else -> TriStateCheckBox.State.UNCHECKED
                }
            }.toTypedArray(),
            false,
        ) {
            onNovelMoved()
        }.show()
    }
}
