package reikai.novel.host

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The Latest chip on a light-novel source is offered only when the plugin reads lnreader's
 * `showLatestNovels`, because the format declares no flag to ask. These pin the marker and, more
 * importantly, the direction: an unrecognised plugin loses the chip rather than getting one that
 * answers with the Popular list.
 */
class LatestSupportTest {

    @Test
    fun `a plugin that reads the option can serve Latest`() {
        derivesLatestSupport(
            """
            async popularNovels(pageNo, { showLatestNovels, filters }) {
                let link = `${'$'}{this.site}browse?sort=`;
                link += showLatestNovels ? 'latest' : 'popular';
            }
            """.trimIndent(),
        ) shouldBe true
    }

    @Test
    fun `destructuring the option still counts as reading it`() {
        derivesLatestSupport("const { showLatestNovels } = options;") shouldBe true
    }

    @Test
    fun `an option that only shares the prefix is not the one lnreader defines`() {
        derivesLatestSupport("const { showLatest } = options;") shouldBe false
    }

    @Test
    fun `a plugin that never mentions the option loses the chip`() {
        derivesLatestSupport(
            """
            async popularNovels(pageNo, { filters }) {
                return this.parseNovels(`${'$'}{this.site}browse?page=${'$'}{pageNo}`);
            }
            """.trimIndent(),
        ) shouldBe false
    }
}
