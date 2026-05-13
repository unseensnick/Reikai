package eu.kanade.tachiyomi.ui.manga.related.browse

import android.app.Activity
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.recommendation.RECOMMENDS_SOURCE
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.category.addtolibrary.SetCategoriesSheet
import eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCandidate
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.widget.TriStateCheckBox
import java.util.Date
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.category.interactor.GetCategories
import yokai.domain.category.interactor.SetMangaCategories
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.manga.models.MangaUpdate
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Phase 6.5 — orchestrates bulk "Add to library" from the related-mangas browse view.
 *
 * Reuses Yokai's existing pieces:
 * - Single-add branching in [eu.kanade.tachiyomi.util.addOrRemoveToFavorites] (default-category /
 *   last-used / no-categories / always-ask) — mirrored here for the multi-manga case.
 * - [SetCategoriesSheet] multi-manga constructor for the "always ask" branch.
 *
 * Deliberate skips:
 * - Duplicate-library detection (`getManga.awaitDuplicateFavorite`). Yokai's single-add path uses it
 *   to surface "already in library, want to migrate?" dialogs — useful for one-off picks, noise for
 *   bulk discovery. Most related-manga items aren't already in the library, so we filter
 *   `manga.favorite` post-resolve and move on.
 * - Tracker-origin candidates (`sourceId == RECOMMENDS_SOURCE`). Their URLs don't resolve to any
 *   installed extension; we partition them off and report them in the completion toast.
 *
 * HACK(unseensnick): [SetCategoriesSheet.addMangaToCategories] only flips `favorite` + writes
 * `date_added` when `listManga.size == 1`. For the multi-manga "always ask" path here, we set
 * favorite + persist via [UpdateManga] BEFORE invoking the sheet so the sheet's category-write is
 * applied against an already-favorited manga set. Remove the pre-flip if the sheet ever learns to
 * handle N>1 itself.
 */
class BulkAddToLibraryHandler(
    private val preferences: PreferencesHelper = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
) {

    /**
     * Add the given [candidates] to the library.
     *
     * @param activity host activity for the optional category-picker sheet + completion toast.
     * @param candidates the selected related-manga candidates from the browse view.
     * @param resolveLocal callback that materializes an [SManga] into a local [Manga] DB row
     *   (typically a thin wrapper around `MangaDetailsPresenter.toLocalManga`). Runs on whatever
     *   context the caller invokes us on; we wrap the per-candidate loop in [withIOContext].
     * @param onDone fired after the user-visible operation completes (either after the sheet
     *   is dismissed or immediately for non-prompting branches). The browse controller uses
     *   this to exit selection mode + refresh.
     */
    suspend fun bulkAdd(
        activity: Activity,
        candidates: List<RelatedMangaCandidate>,
        resolveLocal: suspend (SManga, Long) -> Manga?,
        onDone: () -> Unit,
    ) {
        val (trackerOrigin, addable) = candidates.partition { it.sourceId == RECOMMENDS_SOURCE }
        val skippedTrackerCount = trackerOrigin.size

        val resolved = withIOContext {
            addable.mapNotNull { c -> resolveLocal(c.manga, c.sourceId) }
                .filterNot { it.favorite }
        }

        if (resolved.isEmpty()) {
            withUIContext {
                showCompletionToast(activity, added = 0, skipped = skippedTrackerCount)
                onDone()
            }
            return
        }

        val categories = getCategories.await()
        val defaultCategoryId = preferences.defaultCategory().get()
        val defaultCategory = categories.find { it.id == defaultCategoryId }
        val lastUsedCategories = Category.lastCategoriesAddedTo.mapNotNull { catId ->
            categories.find { it.id == catId }
        }

        when {
            defaultCategory != null -> {
                applyDirect(resolved, listOf(defaultCategory.id!!.toLong()))
                finish(activity, resolved.size, skippedTrackerCount, onDone)
            }
            defaultCategoryId == LAST_USED &&
                (lastUsedCategories.isNotEmpty() || Category.lastCategoriesAddedTo.firstOrNull() == 0) -> {
                applyDirect(resolved, lastUsedCategories.map { it.id!!.toLong() })
                finish(activity, resolved.size, skippedTrackerCount, onDone)
            }
            defaultCategoryId == NO_CATEGORY || categories.isEmpty() -> {
                applyDirect(resolved, emptyList())
                finish(activity, resolved.size, skippedTrackerCount, onDone)
            }
            else -> {
                showAlwaysAskSheet(activity, resolved, categories, skippedTrackerCount, onDone)
            }
        }
    }

    private suspend fun applyDirect(resolved: List<Manga>, categoryIds: List<Long>) {
        val now = Date().time
        withIOContext {
            resolved.forEach { manga ->
                manga.favorite = true
                manga.date_added = now
                updateManga.await(
                    MangaUpdate(
                        id = manga.id!!,
                        favorite = true,
                        dateAdded = now,
                    ),
                )
                setMangaCategories.await(manga.id!!, categoryIds)
            }
        }
    }

    private suspend fun showAlwaysAskSheet(
        activity: Activity,
        resolved: List<Manga>,
        categories: List<Category>,
        skippedTrackerCount: Int,
        onDone: () -> Unit,
    ) {
        // Pre-flip favorite + persist before the sheet — see HACK comment in class KDoc.
        val now = Date().time
        withIOContext {
            resolved.forEach { manga ->
                manga.favorite = true
                manga.date_added = now
                updateManga.await(
                    MangaUpdate(
                        id = manga.id!!,
                        favorite = true,
                        dateAdded = now,
                    ),
                )
            }
        }

        // Compute common+mixed preselection (mirrors List<Manga>.moveCategories in MangaExtensions).
        // Freshly-added manga have no existing categories yet, so both sets are typically empty
        // and every checkbox starts UNCHECKED — but the reduce is correct for the general case
        // (e.g. some manga were already in library via a prior session).
        val mangaCategories = resolved.map { manga ->
            getCategories.awaitByMangaId(manga.id!!)
        }
        val commonCategories = mangaCategories
            .reduceOrNull { set1, set2 -> set1.intersect(set2.toSet()).toMutableList() }
            .orEmpty()
            .toSet()
        val mixedCategories = mangaCategories.flatten().distinct().subtract(commonCategories)
        val preselected = categories.map {
            when (it) {
                in commonCategories -> TriStateCheckBox.State.CHECKED
                in mixedCategories -> TriStateCheckBox.State.IGNORE
                else -> TriStateCheckBox.State.UNCHECKED
            }
        }.toTypedArray()

        withUIContext {
            SetCategoriesSheet(
                activity,
                resolved,
                categories.toMutableList(),
                preselected,
                addingToLibrary = true,
            ) {
                showCompletionToast(activity, added = resolved.size, skipped = skippedTrackerCount)
                onDone()
            }.show()
        }
    }

    private suspend fun finish(activity: Activity, added: Int, skipped: Int, onDone: () -> Unit) {
        withUIContext {
            showCompletionToast(activity, added = added, skipped = skipped)
            onDone()
        }
    }

    private fun showCompletionToast(activity: Activity, added: Int, skipped: Int) {
        val message = if (skipped > 0) {
            activity.getString(MR.strings.bulk_added_with_skipped, added, skipped)
        } else {
            activity.getString(MR.strings.bulk_added_to_library, added)
        }
        activity.toast(message)
    }

    companion object {
        private const val NO_CATEGORY = 0
        private const val LAST_USED = -2
    }
}
