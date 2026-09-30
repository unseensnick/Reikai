package reikai.domain.track.autobind

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.model.Novel
import reikai.domain.track.EntryTrackPort
import reikai.domain.track.supportsContent
import reikai.novel.source.NovelSource
import tachiyomi.domain.manga.model.Manga

/**
 * Both details screens' Tracking button counts through the sheet's own offer rule, so the button
 * cannot count a tracker the sheet would not show. Run over an entry of each type.
 */
class TrackingButtonConformanceTest {

    enum class Type(val isNovel: Boolean, val entry: AutoBindEntry) {
        MANGA(false, AutoBindEntry.Manga(Manga.create(), mockk<Source>())),
        NOVEL(true, AutoBindEntry.Novel(Novel.create(), mockk<NovelSource>())),
        ;

        fun port() = mockk<EntryTrackPort> {
            every { supports(any()) } answers { firstArg<Tracker>().supportsContent(isNovel) }
            coEvery { autoBindEntry() } returns entry
        }
    }

    private val autoBindTrackers = AutoBindTrackers(mockk())

    /** An auto-binding tracker offered only for entries it accepts, like a manga server's own. */
    private fun tracker(id: Long, accepting: Boolean, manga: Boolean = true, novels: Boolean = true) =
        mockk<Tracker>(moreInterfaces = arrayOf(AutoBindTracker::class)).also { tracker ->
            every { tracker.id } returns id
            every { tracker.supportsManga } returns manga
            every { tracker.supportsNovels } returns novels
            val auto = tracker as AutoBindTracker
            every { auto.tracker } returns tracker
            every { auto.offeredOnlyWhenAccepted } returns true
            every { auto.accepts(any()) } returns accepting
        }

    private suspend fun button(type: Type, trackers: List<Tracker>, bound: List<Long>) =
        trackingButtonState(bound, offerTrackers(type.port(), trackers, autoBindTrackers).offered)

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a bound tracker the sheet offers is counted`(type: Type) = runTest {
        button(type, listOf(tracker(BOUND, accepting = true)), bound = listOf(BOUND)).count shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a bound tracker offered only for entries it accepts is not counted when it declines this one`(type: Type) =
        runTest {
            button(type, listOf(tracker(BOUND, accepting = false)), bound = listOf(BOUND)).count shouldBe 0
        }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a declining tracker alone leaves the button without trackers`(type: Type) = runTest {
        button(type, listOf(tracker(BOUND, accepting = false)), bound = emptyList()).hasTrackers shouldBe false
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a bound tracker whose catalogue lacks the type is not counted`(type: Type) = runTest {
        val wrongCatalogue = tracker(BOUND, accepting = true, manga = type.isNovel, novels = !type.isNovel)

        button(type, listOf(wrongCatalogue), bound = listOf(BOUND)).count shouldBe 0
    }

    private companion object {
        const val BOUND = 5L
    }
}
