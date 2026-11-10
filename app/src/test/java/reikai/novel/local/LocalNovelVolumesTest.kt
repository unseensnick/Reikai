package reikai.novel.local

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import reikai.novel.local.epub.EpubException
import java.io.IOException

/** Which volumes a refresh lists, since a volume left out loses its chapters' read state. */
class LocalNovelVolumesTest {

    private val missing = { IllegalStateException("missing") }

    @Test
    fun `a volume that fails to read fails the whole refresh`() {
        shouldThrow<IOException> {
            LocalNovelSource.readableVolumes(
                listOf("a" to Result.failure<Int>(IOException("busy")), "b" to Result.success(2)),
                missing,
            )
        }
    }

    @Test
    fun `a protected volume is left out`() {
        LocalNovelSource.readableVolumes(
            listOf("a" to Result.failure(EpubException(EpubException.Kind.Protected)), "b" to Result.success(2)),
            missing,
        ) shouldBe listOf("b" to 2)
    }

    @Test
    fun `two books of a series changed at the same time copy their covers apart`() {
        LocalNovelSource.coverCopyName("S", "a.epub", 5) shouldNotBe LocalNovelSource.coverCopyName("S", "b.epub", 5)
    }

    @Test
    fun `a series with no volume that opens fails with the first reason`() {
        shouldThrow<EpubException> {
            LocalNovelSource.readableVolumes(
                listOf("a" to Result.failure<Int>(EpubException(EpubException.Kind.NotAnEpub))),
                missing,
            )
        }.kind shouldBe EpubException.Kind.NotAnEpub
    }
}
