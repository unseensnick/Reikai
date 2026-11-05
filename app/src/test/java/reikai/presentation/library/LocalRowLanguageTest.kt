package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.novel.local.LocalNovelSource
import reikai.presentation.library.novels.NovelMergeCollapse
import reikai.presentation.library.novels.toLibraryRow
import tachiyomi.core.metadata.comicinfo.LOCAL_LANGUAGE_MEMO_KEY
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.source.local.LocalSource

/**
 * A library row's language, for both types: a local series' own, read from its files, else its source's.
 * A merged group searches each member by its own, so two local members keep two languages.
 */
class LocalRowLanguageTest {

    /** One entry: [local] or on an online source whose language is "en", its files naming [own]. */
    data class Entry(val id: Long, val local: Boolean, val own: String?)

    enum class Side {
        MANGA {
            override suspend fun rows(entries: List<Entry>, merged: Boolean): List<LibraryItem> {
                val items = entries.map { entry ->
                    val memo = entry.own?.let { mapOf(LOCAL_LANGUAGE_MEMO_KEY to JsonPrimitive(it)) }.orEmpty()
                    val source = if (entry.local) LocalSource.ID else ONLINE_MANGA
                    LibraryManga(
                        manga = Manga.create().copy(id = entry.id, source = source, memo = JsonObject(memo)),
                        categories = emptyList(),
                        totalChapters = 0,
                        readCount = 0,
                        bookmarkCount = 0,
                        latestUpload = null,
                        chapterFetchedAt = null,
                        lastRead = null,
                    ).toLibraryItem(
                        source = StubSource(source, if (entry.local) "other" else "en", "Source"),
                        downloadCount = 0,
                        badgePrefs = BADGES,
                        sourceBadge = SourceBadge.Generic,
                    )
                }
                return MangaMergeCollapse.collapse(
                    items,
                    membership = if (merged) items.associate { it.id to 1L } else emptyMap(),
                    mergingEnabled = true,
                    showMergeSourceIcons = false,
                    resolveSource = { error("no badges") },
                    badgePrefs = BADGES,
                )
            }
        },
        NOVELS {
            override suspend fun rows(entries: List<Entry>, merged: Boolean): List<LibraryItem> {
                val library = entries.map { entry ->
                    LibraryNovel(
                        novel = Novel.create().copy(
                            id = entry.id,
                            source = if (entry.local) LocalNovelSource.ID else "plugin",
                            url = "series-${entry.id}",
                            title = "Book",
                        ),
                        categories = emptyList(),
                        totalChapters = 0,
                        readCount = 0,
                        bookmarkCount = 0,
                        downloadCount = 0,
                        latestUpload = null,
                        chapterFetchedAt = null,
                        lastRead = null,
                    )
                }
                val languages = entries
                    .mapNotNull { entry -> entry.own?.let { "series-${entry.id}" to it } }.toMap()
                val membership = if (merged) library.associate { it.novel.id to 1L } else emptyMap()
                return NovelMergeCollapse.collapse(library, membership, mergingEnabled = true).map { group ->
                    group.toLibraryRow(
                        badgePrefs = BADGES,
                        showSourceIcons = false,
                        querySource = {
                            val isLocal = LocalNovelSource.isLocal(it)
                            LibraryQuerySource(it, name = it, language = if (isLocal) "other" else "en", isLocal)
                        },
                        localLanguages = languages,
                        sourceBadge = { SourceBadge.Generic },
                    )
                }
            }
        },
        ;

        abstract suspend fun rows(entries: List<Entry>, merged: Boolean): List<LibraryItem>
    }

    private suspend fun Side.row(entry: Entry) = rows(listOf(entry), merged = false).single()

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `a local series shows the language its files name`(side: Side) = runTest {
        side.row(Entry(1L, local = true, own = "ja")).badges.sourceLanguage shouldBe "ja"
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `a local series searches by the language its files name`(side: Side) = runTest {
        side.row(Entry(1L, local = true, own = "ja")).sourceLanguage shouldBe "ja"
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `a local series whose files name no language shows none`(side: Side) = runTest {
        side.row(Entry(1L, local = true, own = null)).badges.sourceLanguage shouldBe ""
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `an online series keeps its source's language`(side: Side) = runTest {
        side.row(Entry(1L, local = false, own = "ja")).sourceLanguage shouldBe "en"
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    fun `a merged series searches every member by its own language`(side: Side) = runTest {
        val entries =
            listOf(Entry(1L, local = true, own = "ja"), Entry(2L, local = true, own = "ko"), Entry(3L, false, null))

        side.rows(entries, merged = true).single().memberSources.map { it.language }.toSet() shouldBe
            setOf("ja", "ko", "en")
    }

    private companion object {
        const val ONLINE_MANGA = 100L
        val BADGES = LibraryBadgePrefs(false, false, false, language = true, source = false)
    }
}
