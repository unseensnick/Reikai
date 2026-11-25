package reikai.util

import io.mockk.coEvery
import reikai.domain.novel.NovelRepository

/** Answers [NovelRepository.getByIds] from this mock's [NovelRepository.getById] stubs, which tests make per row. */
fun NovelRepository.byIdsFromById() {
    coEvery { getByIds(any()) } coAnswers { firstArg<List<Long>>().mapNotNull { this@byIdsFromById.getById(it) } }
}
