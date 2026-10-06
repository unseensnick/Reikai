package reikai.domain.download

import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk

/**
 * A source's download folders on a case-blind disk, as Android's shared storage is: a lookup finds a name in any
 * letter case, and a rename never lands on a name held in any case (the folder's own included) but on the first free
 * "name (n)", as the document provider does.
 */
class CaseBlindSourceFolder(vararg names: String) {
    private val held = names.toMutableList()

    val folder: UniFile = mockk {
        every { name } returns SOURCE
        every { findFile(any()) } answers
            { held.firstOrNull { it.equals(firstArg<String>(), ignoreCase = true) }?.let(::child) }
    }

    /** A downloads root holding only [folder]. */
    val root: UniFile = mockk {
        every { findFile(any()) } answers { folder.takeIf { firstArg<String>().equals(SOURCE, ignoreCase = true) } }
    }

    fun names(): List<String> = held.toList()

    private fun child(initial: String): UniFile {
        var current = initial
        return mockk {
            every { name } answers { current }
            every { parentFile } returns folder
            every { renameTo(any()) } answers {
                val asked = firstArg<String>()
                val target = generateSequence(0) { it + 1 }
                    .map { if (it == 0) asked else "$asked ($it)" }
                    .first { candidate -> held.none { it.equals(candidate, ignoreCase = true) } }
                held[held.indexOf(current)] = target
                current = target
                true
            }
        }
    }

    companion object {
        const val SOURCE = "src"
    }
}
