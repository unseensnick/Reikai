package reikai.presentation.migrate.flow

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.novel.host.NovelItem
import reikai.novel.source.SmartNovelSearchEngine
import java.util.Collections

/** The multi-title fallback search, over both engines. */
class MultiTitleSearchConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `the primary search still takes a lone hit unscored`(engine: TitleEngine) = runTest {
        engine.primary("Reverend Insanity", listOf("Completely Different Book")) shouldBe "Completely Different Book"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `the fallback scores a lone hit and refuses an unrelated one`(engine: TitleEngine) = runTest {
        engine.fallback(listOf("Gu Zhen Ren"), listOf("Completely Different Book")) shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `the fallback refuses an unrelated hit over several titles`(engine: TitleEngine) = runTest {
        engine.fallback(listOf("Gu Zhen Ren", "Rev Insanity"), listOf("Completely Different Book")) shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a hit matching only the second title is found`(engine: TitleEngine) = runTest {
        engine.fallback(listOf("Kimetsu no Yaiba", "Demon Slayer"), listOf("Demon Slayer")) shouldBe "Demon Slayer"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `the hit closest to any title wins`(engine: TitleEngine) = runTest {
        engine.fallback(listOf("Kimetsu no Yaiba", "Demon Slayer"), listOf("Kimetsu no Yaib", "Demon Slayer")) shouldBe
            "Demon Slayer"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `deep mode sends no query twice across titles`(engine: TitleEngine) = runTest {
        engine.fallback(listOf("Demon Slayer", "Demon Hunter"), emptyList(), deep = true)

        engine.queries.sorted() shouldBe
            listOf("demon", "demon hunter", "demon slayer", "hunter", "hunter demon", "slayer", "slayer demon")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `deep mode sends no blank query for a long non-Latin title`(engine: TitleEngine) = runTest {
        engine.fallback(listOf("転生したらスライムだった件"), listOf("鬼滅の刃無限列車編の物語"), deep = true)

        engine.queries.filter { it.isBlank() } shouldBe emptyList()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `deep mode refuses an unrelated long non-Latin hit`(engine: TitleEngine) = runTest {
        engine.fallback(listOf("転生したらスライムだった件"), listOf("鬼滅の刃無限列車編の物語"), deep = true) shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `deep mode still finds a matching long non-Latin title`(engine: TitleEngine) = runTest {
        engine.fallback(listOf("転生したらスライムだった件"), listOf("鬼滅の刃無限列車編の物語", "転生したらスライムだった件"), deep = true) shouldBe
            "転生したらスライムだった件"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    fun `a title with no letters left is never searched`(engine: TitleEngine) = runTest {
        engine.fallback(listOf("★★★★★★★"), listOf("☆☆☆☆☆☆☆", "Other"), deep = true) shouldBe null
    }

    @Test
    fun `the fallback runs only when the primary finds nothing`() = runTest {
        var multiCalls = 0
        searchPrimaryThenFallback("A", listOf("B"), single = { "hit" }, multi = {
            multiCalls++
            "other"
        })

        multiCalls shouldBe 0
    }

    @Test
    fun `the fallback searches without the primary and stops at three titles`() = runTest {
        var searched: List<String>? = null
        searchPrimaryThenFallback("Main", listOf(" main ", "Alt", "B", "C", "D"), single = { null }, multi = {
            searched = it
            null
        })

        searched shouldBe listOf("Alt", "B", "C")
    }

    companion object {
        @JvmStatic
        fun engines() = listOf(MangaTitleEngine(), NovelTitleEngine())
    }
}

/** One engine's two entry points over a fake source answering [hits] to every query. */
abstract class TitleEngine {
    val queries: MutableList<String> = Collections.synchronizedList(mutableListOf())
    abstract suspend fun primary(title: String, hits: List<String>, deep: Boolean = false): String?
    abstract suspend fun fallback(titles: List<String>, hits: List<String>, deep: Boolean = false): String?
}

class MangaTitleEngine : TitleEngine() {
    override fun toString() = "manga"

    private fun source(hits: List<String>) = mockk<Source> {
        every { id } returns 1L
        every { getFilterList() } returns FilterList()
        coEvery { getSearchManga(any(), any(), any()) } answers {
            queries += secondArg<String>()
            MangasPage(
                hits.map {
                    SManga.create().apply {
                        title = it
                        url = "/$it"
                    }
                },
                false,
            )
        }
    }

    override suspend fun primary(title: String, hits: List<String>, deep: Boolean) = SmartSourceSearchEngine(null)
        .let { if (deep) it.deepSearch(source(hits), title) else it.regularSearch(source(hits), title) }?.title

    override suspend fun fallback(titles: List<String>, hits: List<String>, deep: Boolean) =
        SmartSourceSearchEngine(null).multiTitleSearch(source(hits), titles, deep)?.title
}

class NovelTitleEngine : TitleEngine() {
    override fun toString() = "novel"

    private fun search(hits: List<String>): suspend (String) -> List<NovelItem> = { query ->
        queries += query
        hits.map { NovelItem(name = it, path = "/$it", cover = null) }
    }

    override suspend fun primary(title: String, hits: List<String>, deep: Boolean) =
        SmartNovelSearchEngine(null).bestMatch(title, deep, search(hits))?.name

    override suspend fun fallback(titles: List<String>, hits: List<String>, deep: Boolean) =
        SmartNovelSearchEngine(null).bestMatchAny(titles, deep, search(hits))?.name
}
