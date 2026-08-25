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

    /** Fixtures here are the real header markup, so this one is not a guess at the site's shape. */
    @Test
    fun `the username comes from the profile link`() {
        val page = Jsoup.parse(
            "<a href='https://www.novelupdates.com/user/12345/booklover/'>User Profile</a>",
        )

        parseUsername(page) shouldBe "booklover"
    }

    /** The header carries one label per layout, and the id-suffixed one is padded with markup space. */
    @Test
    fun `the username falls back to the header label`() {
        val page = Jsoup.parse(
            """
            <span class='menu_username_right' id='menu_username_right'>booklover

            </span>
            <span class='menu_right username_main' id='menu_right_item'>booklover</span>
            """,
        )

        parseUsername(page) shouldBe "booklover"
    }

    @Test
    fun `a signed-out page reports no username`() {
        parseUsername(Jsoup.parse("<a href='/login/'>Log in</a>")) shouldBe null
    }

    private val seriesPage = """
        <div class='seriestitlenu'>A Novel (WN)</div>
        <div class='serieseditimg'><img src='https://cdn.novelupdates.com/images/2016/03/latest-1.jpeg'></div>
        <div id='editdescription'>Seventeen-year-old Hajime is an everyday otaku.</div>
        <div id='showauthors'><a>Chuuni Suki</a><a>Ryo Shirakome</a><a>&#21388;&#20108;&#22909;&#12365;</a><a>&#30333;&#31859;&#33391;</a></div>
        <div id='showartists'><a>Takayaki</a><a>&#12383;&#12363;&#12420;Ki</a></div>
        <div id='seriesgenre'><a>Action</a><a>Adventure</a><a>Fantasy</a></div>
        <div id='showtags'><a>Adapted to Anime</a><a>Angels</a><a>Betrayal</a></div>
    """.trimIndent()

    @Test
    fun `a series page yields the fields Fill from tracker uses`() {
        parseDetails(Jsoup.parse(seriesPage)).run {
            title shouldBe "A Novel (WN)"
            coverUrl shouldBe "https://cdn.novelupdates.com/images/2016/03/latest-1.jpeg"
            description shouldBe "Seventeen-year-old Hajime is an everyday otaku."
            genres shouldBe listOf("Action", "Adventure", "Fantasy")
        }
    }

    /** Tags outnumber genres roughly ten to one, and both would land in the same field. */
    @Test
    fun `the tag list is left out of the genres`() {
        parseDetails(Jsoup.parse(seriesPage)).genres.contains("Adapted to Anime") shouldBe false
    }

    /** The site credits each person twice, romanized then native; both are the same two people. */
    @Test
    fun `a credit listed in two scripts is not counted twice`() {
        parseDetails(Jsoup.parse(seriesPage)).run {
            authors shouldBe listOf("Chuuni Suki", "Ryo Shirakome")
            artists shouldBe listOf("Takayaki")
        }
    }

    /** An entry that was never romanized keeps its credits rather than losing them entirely. */
    @Test
    fun `a credit with no romanized form is kept`() {
        preferLatinNames(listOf("白米良")) shouldBe listOf("白米良")
    }

    @Test
    fun `a series page missing its optional parts still parses`() {
        parseDetails(Jsoup.parse("<div class='seriestitlenu'>Bare</div>")).run {
            title shouldBe "Bare"
            coverUrl shouldBe null
            description shouldBe null
            genres shouldBe emptyList()
        }
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
