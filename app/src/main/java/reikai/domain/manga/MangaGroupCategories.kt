package reikai.domain.manga

import dev.zacsweers.metro.Inject
import reikai.domain.merge.GroupCategories
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories

/** Manga's [GroupCategories], over Mihon's own category read and write. */
@Inject
class MangaGroupCategories(getCategories: GetCategories, setMangaCategories: SetMangaCategories) :
    GroupCategories(
        categoriesOf = { id -> getCategories.await(id).map { it.id } },
        write = { id, categoryIds -> setMangaCategories.await(id, categoryIds) },
    )
