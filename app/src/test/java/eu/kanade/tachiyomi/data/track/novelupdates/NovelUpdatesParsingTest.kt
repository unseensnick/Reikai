package eu.kanade.tachiyomi.data.track.novelupdates

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/**
 * The selectors are the whole maintenance surface of a scraped tracker, and nothing else in the app
 * would notice one going stale: a changed class name yields an empty list, not an error. These
 * fixtures are trimmed from the shapes tsundoku's selectors target.
 *
 * They pin our reading of the markup, not the live site. Only a device pass proves the site matches.
 */
class NovelUpdatesParsingTest {

    private fun searchRow(inner: String) = Jsoup.parse("<div class='search_main_box_nu'>$inner</div>")
        .selectFirst("div.search_main_box_nu")!!

    @Test
    fun `a search row yields title, url, cover and numeric id`() {
        val row = searchRow(
            """
            <div class='search_img_nu'><img src='/img/cover.jpg'></div>
            <div class='search_title'><a href='https://www.novelupdates.com/series/shadow-slave/'>Shadow Slave</a></div>
            <span id='sid12345'></span>
            <div class='search_body_nu'>A blurb.<span class='testhide'>The rest of it.</span></div>
            <div class='search_genre'>Ongoing</div>
            """,
        )

        row.toSeries().run {
            title shouldBe "Shadow Slave"
            id shouldBe "12345"
            seriesUrl shouldBe "https://www.novelupdates.com/series/shadow-slave/"
            coverUrl shouldBe "https://www.novelupdates.com/img/cover.jpg"
            publishingStatus shouldBe "Ongoing"
        }
    }

    /** The blurb is split into a visible half and a hidden remainder; both belong in the summary. */
    @Test
    fun `the collapsed half of a blurb is stitched back on`() {
        val row = searchRow(
            """
            <div class='search_title'><a href='/series/x/'>X</a></div>
            <div class='search_body_nu'>He woke up.... more&gt;&gt;<span class='testhide'>Then everything changed.&lt;&lt;less</span></div>
            """,
        )

        row.toSeries().summary shouldBe "He woke up. Then everything changed."
    }

    /** Rows without a sid span exist, and bind resolves the real id from the series page instead. */
    @Test
    fun `a row with no numeric id reports none rather than inventing one`() {
        val row = searchRow("<div class='search_title'><a href='/series/x/'>X</a></div>")

        row.toSeries().id shouldBe null
    }

    @Test
    fun `an absolute cover url is left alone`() {
        val row = searchRow(
            """
            <div class='search_img_nu'><img src='https://cdn.example/c.jpg'></div>
            <div class='search_title'><a href='/series/x/'>X</a></div>
            """,
        )

        row.toSeries().coverUrl shouldBe "https://cdn.example/c.jpg"
    }

    @Test
    fun `the novel id comes from the shortlink first`() {
        val page = Jsoup.parse(
            """
            <link rel='shortlink' href='https://www.novelupdates.com/?p=98765'>
            <a href='/series/activity-stats/?seriesid=11111'>stats</a>
            <input id='mypostid' value='22222'>
            """,
        )

        parseNovelId(page) shouldBe "98765"
    }

    /** Not every series page carries a shortlink, so the other two are real fallbacks. */
    @Test
    fun `the novel id falls back to the stats link then the hidden input`() {
        parseNovelId(
            Jsoup.parse("<a href='/series/activity-stats/?seriesid=11111'>s</a><input id='mypostid' value='2'>"),
        ) shouldBe
            "11111"
        parseNovelId(Jsoup.parse("<input id='mypostid' value='22222'>")) shouldBe "22222"
        parseNovelId(Jsoup.parse("<p>nothing here</p>")) shouldBe null
    }

    /**
     * The add button is the sentinel for "on no list", a state rather than a failure. The list link
     * is present here on purpose: without it, a broken sentinel would still return null by falling
     * through to a missing link, and this would pass while meaning nothing.
     */
    @Test
    fun `the add button wins over any list link on the page`() {
        val page = Jsoup.parse(
            """
            <div class='sticon'>
              <img src='/img/addme.png'>
              <span class='sttitle'><a href='/reading-list/?list=3'>On Hold</a></span>
            </div>
            """,
        )

        parseListId(page) shouldBe null
    }

    @Test
    fun `an existing list shows its id`() {
        val page = Jsoup.parse(
            "<div class='sticon'><span class='sttitle'><a href='/reading-list/?list=3'>On Hold</a></span></div>",
        )

        parseListId(page) shouldBe 3L
    }

    @Test
    fun `reading lists come from the menu when it is present`() {
        val page = Jsoup.parse(
            """
            <div id='cssmenu'>
              <li><a href='/reading-list/?list=0'>Reading</a></li>
              <li><a href='/reading-list/?list=7'>Slow burn</a></li>
              <li><a href='/profile/'>Profile</a></li>
            </div>
            """,
        )

        parseReadingLists(page) shouldBe listOf("0" to "Reading", "7" to "Slow burn")
    }

    /** Without the menu the panel's dropdown carries the same lists, placeholder entry aside. */
    @Test
    fun `reading lists fall back to the dropdown`() {
        val page = Jsoup.parse(
            """
            <div class='sticon'><select class='stmove'>
              <option value='---'>Select...</option>
              <option value='0'>Reading</option>
              <option value='4'>Dropped</option>
            </select></div>
            """,
        )

        parseReadingLists(page) shouldBe listOf("0" to "Reading", "4" to "Dropped")
    }
}
