package reikai.domain.novel

import dev.zacsweers.metro.Inject
import reikai.domain.category.GetNovelCategories
import reikai.domain.merge.GroupCategories
import reikai.domain.novel.interactor.SetNovelCategories

/** Novels' [GroupCategories], over the novel category read and write. */
@Inject
class NovelGroupCategories(getNovelCategories: GetNovelCategories, setNovelCategories: SetNovelCategories) :
    GroupCategories(
        categoriesOf = { id -> getNovelCategories.awaitByNovelId(id).map { it.id } },
        write = { id, categoryIds -> setNovelCategories.await(id, categoryIds) },
    )
