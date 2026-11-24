package reikai.novel.host

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The Latest chip on a light-novel source is offered only when the plugin reads lnreader's
 * `showLatestNovels`, because the format declares no flag to ask. These pin the marker and, more
 * importantly, the direction: an unrecognised plugin loses the chip rather than getting one that
 * answers with the Popular list.
 */
class LatestSupportTest {

    @ParameterizedTest
    @MethodSource("plugins")
    fun `only a plugin that reads lnreader's option can serve Latest`(script: String, serves: Boolean) {
        derivesLatestSupport(script) shouldBe serves
    }

    private companion object {
        // Reading or destructuring the option counts; one that only shares its prefix, or none, does not.
        @JvmStatic
        fun plugins() = listOf(
            Arguments.of(
                """
                async popularNovels(pageNo, { showLatestNovels, filters }) {
                    let link = `${'$'}{this.site}browse?sort=`;
                    link += showLatestNovels ? 'latest' : 'popular';
                }
                """.trimIndent(),
                true,
            ),
            Arguments.of("const { showLatestNovels } = options;", true),
            Arguments.of("const { showLatest } = options;", false),
            Arguments.of(
                """
                async popularNovels(pageNo, { filters }) {
                    return this.parseNovels(`${'$'}{this.site}browse?page=${'$'}{pageNo}`);
                }
                """.trimIndent(),
                false,
            ),
        )
    }
}
