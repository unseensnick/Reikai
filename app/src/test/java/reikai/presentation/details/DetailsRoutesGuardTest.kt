package reikai.presentation.details

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.math.abs

/**
 * A details page and a source's settings screen are each built in one home ([homes]), so a change to how
 * one is addressed lands once for both types. Scans every file under reikai/ and exh/ whole, a Mihon file
 * with a `RK: whole file` header whole, and the Reikai lines of every other file the way
 * scripts/rk-fence-report.ps1 reads them: an island, the statement under a note, and the up-to-five-line
 * hunk a trailing marker fences (the lines around it up to a blank line). Upstream's own lines are left
 * alone. A Reikai-owned file outside reikai/ and exh/ carries no marker, so it is not covered.
 */
class DetailsRoutesGuardTest {

    class Home(val name: String, val files: Set<String>, val construction: Regex, val declaration: Regex) {
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
            val lines = file.readLines()
            val ours = path.startsWith("reikai/") || path.startsWith("exh/") ||
                lines.take(3).any { WHOLE_FILE.containsMatchIn(it) }
            scannedLines(lines, ours)
                .filter { (_, line) ->
                    home.construction.containsMatchIn(line) &&
                        !home.declaration.containsMatchIn(line)
                }
                .map { (number, line) -> "$path:$number: ${line.trim()}" }
        }

        strays.joinToString("\n") shouldBe ""
    }

    private fun scannedLines(lines: List<String>, wholeFile: Boolean): List<Pair<Int, String>> {
        val numbered = lines.mapIndexed { index, line -> index + 1 to line }
        if (wholeFile) return numbered.filterNot { (_, line) -> isComment(line) || line.trimStart().startsWith("*") }
        val inHunk = trailingHunks(lines)
        var inIsland = false
        var underNote = false
        return numbered.filter { (number, line) ->
            when {
                ISLAND_OPEN.containsMatchIn(line) -> inIsland = true
                ISLAND_CLOSE.containsMatchIn(line) -> inIsland = false
                NOTE.containsMatchIn(line) -> underNote = true
                line.isBlank() -> underNote = false
            }
            (inIsland || underNote || inHunk[number - 1]) && !isComment(line)
        }
    }

    // The fence report fences a hunk of up to five lines whole when one of them carries a trailing
    // marker; without the diff, every line within four of the marker, short of a blank line, may be in it.
    private fun trailingHunks(lines: List<String>): BooleanArray {
        val inHunk = BooleanArray(lines.size)
        lines.forEachIndexed { marked, line ->
            if (isComment(line) || !TRAILING_MARKER.containsMatchIn(line)) return@forEachIndexed
            inHunk[marked] = true
            for (step in listOf(-1, 1)) {
                var index = marked + step
                while (index in lines.indices && abs(index - marked) <= 4 && lines[index].isNotBlank()) {
                    inHunk[index] = true
                    index += step
                }
            }
        }
        return inHunk
    }

    private fun isComment(line: String) = line.trimStart().startsWith("//")

    companion object {
        @JvmStatic
        fun homes() = listOf(
            Home(
                "details pages",
                setOf("DetailsRoutes.kt"),
                Regex("""\b(MangaScreen|NovelRoute)\(|::(MangaScreen|NovelRoute)\b"""),
                Regex("""\bclass (MangaScreen|NovelRoute)\("""),
            ),
            // The screen's own file keeps forNovel, the one builder the home calls for a novel source.
            Home(
                "source settings",
                setOf("SourcePreferencesScreens.kt", "SourcePreferencesScreen.kt"),
                Regex("""\bSourcePreferencesScreen(\(|\.forNovel\b)|::SourcePreferencesScreen\b"""),
                Regex("""\bclass SourcePreferencesScreen\("""),
            ),
        )

        private val WHOLE_FILE = Regex("""^\s*//\s*\bRK: whole file""")
        private val ISLAND_OPEN = Regex("""^\s*//\s*\bRK\s*-->""")
        private val ISLAND_CLOSE = Regex("""^\s*//\s*\bRK\s*<--""")
        private val NOTE = Regex("""^\s*//\s*\bRK:""")
        private val TRAILING_MARKER = Regex("""\S.*//\s*\bRK\b""")
    }
}
