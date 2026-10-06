package eu.kanade.tachiyomi.data.track.novelupdates

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

private typealias Releases = suspend ((NovelUpdatesRelease) -> Boolean) -> List<NovelUpdatesRelease>

class NovelUpdatesReleasesTest {

    private val release = { id: String, name: String -> NovelUpdatesRelease(id, name) }

    // The number is whatever the name says, as ChapterRecognition would read these plain names.
    private val numberOf = { name: String -> name.substringAfter('c').toDoubleOrNull() ?: -1.0 }

    @Test
    fun `the extension's release link carries its release id`() {
        releaseIdOf("https://www.novelupdates.com/extnu/6123456/") shouldBe "6123456"
    }

    @Test
    fun `the LNReader plugin's relative release link carries it too`() {
        releaseIdOf("extnu/6123456/") shouldBe "6123456"
    }

    @Test
    fun `another source's chapter link is no release`() {
        releaseIdOf("https://novelnice.com/novel/4021/chapter/12/").shouldBeNull()
    }

    @Test
    fun `a series link gives its slug`() {
        seriesSlugOf("https://www.novelupdates.com/series/shadow-slave/") shouldBe "shadow-slave"
    }

    @Test
    fun `the extension's bare slug is its own slug`() {
        seriesSlugOf("shadow-slave") shouldBe "shadow-slave"
    }

    @Test
    fun `the release list reads each row's release link`() {
        val html = """
            <ol>
              <li class="sp_li_chp"><a href="//www.novelupdates.com/group/g/"></a><a href="//www.novelupdates.com/extnu/11/">c1</a></li>
              <li class="sp_li_chp"><a href="//www.novelupdates.com/group/g/"></a><a href="//www.novelupdates.com/extnu/12/">c2</a></li>
            </ol>
        """.trimIndent()

        parseReleases(Jsoup.parse(html, "https://www.novelupdates.com/")) shouldBe
            listOf(release("11", "c1"), release("12", "c2"))
    }

    // The nth ask answers the nth list, staying on the last; [asked] records the series asked for.
    private class Site(private vararg val lists: List<NovelUpdatesRelease>) {
        val asked = mutableListOf<String>()
        val held = HeldReleases { novelId ->
            asked += novelId
            lists[minOf(asked.size, lists.size) - 1]
        }
    }

    private fun pickFrom(site: Site, novelId: String): Releases = { matches -> site.held.matching(novelId, matches) }

    private val notFetched: Releases = { error("not fetched") }

    @Test
    fun `the release the read chapter links to is ticked`() = runTest {
        pickRelease(2.0, setOf("12"), notFetched, numberOf) shouldBe "12"
    }

    @Test
    fun `read chapters linking to two releases tick neither`() = runTest {
        pickRelease(2.0, setOf("12", "22"), notFetched, numberOf).shouldBeNull()
    }

    @Test
    fun `without a link the site's only release of that number is ticked`() = runTest {
        val site = Site(listOf(release("11", "c1"), release("12", "c2")))
        pickRelease(2.0, emptySet(), pickFrom(site, "7"), numberOf) shouldBe "12"
    }

    @Test
    fun `two groups' releases of that number tick neither`() = runTest {
        val site = Site(listOf(release("12", "c2"), release("22", "c2")))
        pickRelease(2.0, emptySet(), pickFrom(site, "7"), numberOf).shouldBeNull()
    }

    @Test
    fun `an unnumbered chapter never ticks a release by number`() = runTest {
        pickRelease(-1.0, emptySet(), notFetched, numberOf).shouldBeNull()
    }

    @Test
    fun `a run of reads of one series asks the site for its releases once`() = runTest {
        val site = Site(listOf(release("11", "c1"), release("12", "c2")))
        pickRelease(1.0, emptySet(), pickFrom(site, "7"), numberOf)
        pickRelease(2.0, emptySet(), pickFrom(site, "7"), numberOf)
        site.asked shouldBe listOf("7")
    }

    @Test
    fun `a release posted since the list was held is found by asking again`() = runTest {
        val site = Site(listOf(release("11", "c1")), listOf(release("11", "c1"), release("12", "c2")))
        pickRelease(1.0, emptySet(), pickFrom(site, "7"), numberOf)
        pickRelease(2.0, emptySet(), pickFrom(site, "7"), numberOf) shouldBe "12"
    }

    @Test
    fun `a number the site has no release for asks it once, not twice`() = runTest {
        val site = Site(listOf(release("11", "c1")))
        pickRelease(3.0, emptySet(), pickFrom(site, "7"), numberOf)
        site.asked shouldBe listOf("7")
    }

    @Test
    fun `another series asks for its own releases`() = runTest {
        val site = Site(listOf(release("11", "c1")), listOf(release("81", "c1")))
        pickRelease(1.0, emptySet(), pickFrom(site, "7"), numberOf)
        pickRelease(1.0, emptySet(), pickFrom(site, "8"), numberOf) shouldBe "81"
    }

    @Test
    fun `an unread moves the site back to the highest chapter still read`() {
        progressAfterUnread(unread = listOf(8.0), stillRead = 7.0, onSite = 10) shouldBe 7.0
    }

    @Test
    fun `an unread of the top chapter among others moves the site back to the highest still read`() {
        progressAfterUnread(unread = listOf(8.0, 30.0), stillRead = 29.0, onSite = 200) shouldBe 29.0
    }

    @Test
    fun `an unread below the highest chapter still read leaves a site that is ahead alone`() {
        progressAfterUnread(unread = listOf(8.0), stillRead = 29.0, onSite = 200).shouldBeNull()
    }

    @Test
    fun `an unread below the highest chapter still read never moves the site forward`() {
        progressAfterUnread(unread = listOf(8.0), stillRead = 29.0, onSite = 20).shouldBeNull()
    }

    @Test
    fun `an unread of an unnumbered chapter leaves the site alone`() {
        progressAfterUnread(unread = listOf(-1.0), stillRead = 50.0, onSite = 200).shouldBeNull()
    }

    @Test
    fun `an unread of the only chapter read, an unnumbered one, leaves the site alone`() {
        progressAfterUnread(unread = listOf(-1.0), stillRead = null, onSite = 200).shouldBeNull()
    }

    @Test
    fun `an unread above the site's progress leaves the site alone`() {
        progressAfterUnread(unread = listOf(30.0), stillRead = 29.0, onSite = 20).shouldBeNull()
    }

    @Test
    fun `an unread reaching below the site's progress moves it back though it also reaches above`() {
        progressAfterUnread(unread = listOf(8.0, 30.0), stillRead = 7.0, onSite = 20) shouldBe 7.0
    }

    @Test
    fun `an unread of every chapter moves the site to none read`() {
        progressAfterUnread(unread = listOf(1.0), stillRead = null, onSite = 5) shouldBe 0.0
    }

    @Test
    fun `an automatic read of an earlier chapter leaves the site where it is`() {
        holdsBack(isRead = true, neverBackwards = true, chapter = 3.0, onSite = 5) shouldBe true
    }

    @Test
    fun `a later chapter moves the site on`() {
        holdsBack(isRead = true, neverBackwards = true, chapter = 6.0, onSite = 5) shouldBe false
    }

    @Test
    fun `an earlier chapter moves the site back when the setting is off`() {
        holdsBack(isRead = true, neverBackwards = false, chapter = 3.0, onSite = 5) shouldBe false
    }

    @Test
    fun `a progress set by hand is never held back`() {
        holdsBack(isRead = false, neverBackwards = true, chapter = 3.0, onSite = 5) shouldBe false
    }

    @Test
    fun `a series on none of the user's lists is filed as plan to read when nothing is read`() {
        bindOnSite(siteStatus = null, hasReadChapters = false) shouldBe
            BindOnSite.File(NovelUpdates.PLAN_TO_READ)
    }

    @Test
    fun `a series on none of the user's lists is filed as reading once chapters are read`() {
        bindOnSite(siteStatus = null, hasReadChapters = true) shouldBe
            BindOnSite.File(NovelUpdates.READING)
    }

    @Test
    fun `a series already on a list keeps it and writes nothing`() {
        bindOnSite(siteStatus = NovelUpdates.ON_HOLD, hasReadChapters = true) shouldBe
            BindOnSite.Keep(moveTo = null)
    }

    @Test
    fun `a series on a list of the user's own keeps it too`() {
        bindOnSite(siteStatus = NovelUpdates.OTHER_LIST, hasReadChapters = true) shouldBe
            BindOnSite.Keep(moveTo = null)
    }

    @Test
    fun `a planned series moves to reading once chapters are read`() {
        bindOnSite(siteStatus = NovelUpdates.PLAN_TO_READ, hasReadChapters = true) shouldBe
            BindOnSite.Keep(moveTo = NovelUpdates.READING)
    }

    @Test
    fun `a planned series with nothing read stays planned`() {
        bindOnSite(siteStatus = NovelUpdates.PLAN_TO_READ, hasReadChapters = false) shouldBe
            BindOnSite.Keep(moveTo = null)
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("statusesAfterRead")
    fun `a read moves a series to reading unless it is completed or on a list of the user's own`(
        status: Long,
        expected: Long,
    ) {
        statusAfterRead(status) shouldBe expected
    }

    companion object {
        @JvmStatic
        fun statusesAfterRead() = listOf(
            Arguments.of(NovelUpdates.PLAN_TO_READ, NovelUpdates.READING),
            Arguments.of(NovelUpdates.ON_HOLD, NovelUpdates.READING),
            Arguments.of(NovelUpdates.DROPPED, NovelUpdates.READING),
            Arguments.of(NovelUpdates.READING, NovelUpdates.READING),
            Arguments.of(NovelUpdates.COMPLETED, NovelUpdates.COMPLETED),
            Arguments.of(NovelUpdates.OTHER_LIST, NovelUpdates.OTHER_LIST),
        )
    }
}
