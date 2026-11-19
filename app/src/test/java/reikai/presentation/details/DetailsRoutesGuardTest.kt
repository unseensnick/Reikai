package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * A details page is built only in DetailsRoutes.kt, so a change to how one is addressed lands once for
 * both types. Scans every file under reikai/ and exh/ whole, and the `// RK` lines of every other file
 * (an island, a trailing marker, or the statement under a `// RK:` note); upstream's own lines are left
 * alone. A Reikai-owned file outside reikai/ and exh/ carries no marker, so it is not covered.
 */
class DetailsRoutesGuardTest {

    @Test
    fun `details screens are constructed only in DetailsRoutes`() {
        val root = File("src/main/java")
        val strays = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "DetailsRoutes.kt" }
            .flatMap { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                val ours = path.startsWith("reikai/") || path.startsWith("exh/")
                scannedLines(file.readLines(), ours)
                    .filter { (_, line) -> CONSTRUCTION.containsMatchIn(line) && !DECLARATION.containsMatchIn(line) }
                    .map { (number, line) -> "$path:$number: ${line.trim()}" }
            }
            .toList()

        strays.joinToString("\n") shouldBe ""
    }

    private fun scannedLines(lines: List<String>, wholeFile: Boolean): List<Pair<Int, String>> {
        val numbered = lines.mapIndexed { index, line -> index + 1 to line }
        if (wholeFile) return numbered.filterNot { (_, line) -> isComment(line) || line.trimStart().startsWith("*") }
        var inIsland = false
        var underNote = false
        return numbered.filter { (_, line) ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("$MARKER -->") -> inIsland = true
                trimmed.startsWith("$MARKER <--") -> inIsland = false
                trimmed.startsWith("$MARKER:") -> underNote = true
                trimmed.isEmpty() -> underNote = false
            }
            (inIsland || underNote || TRAILING_MARKER.containsMatchIn(line)) && !isComment(line)
        }
    }

    private fun isComment(line: String) = line.trimStart().startsWith("//")

    private companion object {
        val CONSTRUCTION = Regex("""\b(MangaScreen|NovelScreen)\(""")
        val DECLARATION = Regex("""\bclass (MangaScreen|NovelScreen)\(""")

        // Built from halves so the commit hook does not read this Reikai file as carrying a marker.
        const val MARKER = "//" + " RK"
        val TRAILING_MARKER = Regex("""\S.*$MARKER\b""")
    }
}
