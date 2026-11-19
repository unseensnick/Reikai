package reikai.presentation.details

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * A details page and a source's settings screen are each built in one home ([homes]), so a change to how
 * one is addressed lands once for both types. Scans every line of every source file outside the home, Mihon's
 * and Reikai's alike, so a new construction anywhere fails; upstream's own are listed by text in
 * [Home.upstream], since a sync keeps them as upstream wrote them.
 */
class DetailsRoutesGuardTest {

    class Home(
        val name: String,
        val files: Set<String>,
        val construction: Regex,
        val declaration: Regex,
        /** Upstream's own constructions, each as `path: line`, the line trimmed. */
        val upstream: Set<String> = emptySet(),
    ) {
        override fun toString() = name
    }

    @ParameterizedTest
    @MethodSource("homes")
    fun `a screen is constructed only in its home`(home: Home) {
        // Unit tests run from the module directory, so the source tree resolves relative to it.
        val root = File("src/main/java")
        root.isDirectory shouldBe true
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in home.files }
            .toList()
        files.shouldNotBeEmpty()

        val strays = files.flatMap { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            file.readLines()
                .mapIndexed { index, line -> index + 1 to line.trim() }
                .filter { (_, line) ->
                    !line.startsWith("//") && !line.startsWith("*") &&
                        home.construction.containsMatchIn(line) &&
                        !home.declaration.containsMatchIn(line) &&
                        "$path: $line" !in home.upstream
                }
                .map { (number, line) -> "$path:$number: $line" }
        }

        strays.joinToString("\n") shouldBe ""
    }

    companion object {
        @JvmStatic
        fun homes() = listOf(
            Home(
                "details pages",
                setOf("DetailsRoutes.kt"),
                Regex("""\b(MangaScreen|NovelRoute)\(|::(MangaScreen|NovelRoute)\b"""),
                Regex("""\bclass (MangaScreen|NovelRoute)\("""),
                upstream = setOf(
                    "eu/kanade/tachiyomi/ui/deeplink/DeepLinkScreen.kt: MangaScreen(",
                    "mihon/feature/upcoming/UpcomingScreen.kt: onClickUpcoming = { navigator.push(MangaScreen(it.id)) },",
                ),
            ),
            // The screen's own file keeps forNovel, the one builder the home calls for a novel source.
            Home(
                "source settings",
                setOf("SourcePreferencesScreens.kt", "SourcePreferencesScreen.kt"),
                Regex("""\bSourcePreferencesScreen(\(|\.forNovel\b)|::SourcePreferencesScreen\b"""),
                Regex("""\bclass SourcePreferencesScreen\("""),
            ),
        )
    }
}
