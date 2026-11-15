package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupProtoWriter
import eu.kanade.tachiyomi.data.backup.models.BackupFields
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The summary pass reads only a few fields of each entry, so an entry that is unreadable elsewhere is
 * found only when the entries are restored. The restore skips it, notes it in the error log and carries
 * on with the rest, as it does for an entry that fails to restore, for both content types. Under
 * Robolectric because writing the log reaches Android's graphics classes.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class UnreadableEntryRestoreTest(private val type: Type) {

    @get:Rule
    val cache = TemporaryFolder()

    private val restored = mutableListOf<String>()

    private suspend fun restoreBadThenGood() {
        val file = ByteArrayOutputStream().also { out ->
            listOf("bad", "good").forEach { url ->
                // The title, a string, written as a number in the bad one.
                val entry = type.entry(url) + if (url == "bad") byteArrayOf(0x18, 0x01) else byteArrayOf()
                BackupProtoWriter.writeField(out, type.field, entry)
            }
        }.toByteArray()
        restoreFile(
            file,
            RestoreOptions(appSettings = false, sourceSettings = false),
            mangaRestorer = mockk(relaxed = true) {
                coEvery { restore(any(), any()) } answers { restored += firstArg<List<BackupManga>>().map { it.url } }
            },
            novelRestorer = mockk(relaxed = true) {
                coEvery { restore(any(), any()) } answers { restored += firstArg<BackupNovel>().url }
            },
            cacheDir = cache.root,
        )
    }

    @Test
    fun `an unreadable entry is skipped and the rest still restore`() = runTest {
        restoreBadThenGood()

        restored shouldBe listOf("good")
    }

    @Test
    fun `an unreadable entry is named in the error log`() = runTest {
        restoreBadThenGood()

        File(cache.root, "reikai_restore_error.txt").readText() shouldContain "Entry 1 could not be read"
    }

    enum class Type(val field: Int) {
        MANGA(BackupFields.MANGA) {
            override fun entry(url: String) =
                ProtoBuf.encodeToByteArray(BackupManga.serializer(), BackupManga(source = 1, url = url))
        },
        NOVEL(BackupFields.NOVELS) {
            override fun entry(url: String) =
                ProtoBuf.encodeToByteArray(BackupNovel.serializer(), BackupNovel(source = "s", url = url))
        },
        ;

        abstract fun entry(url: String): ByteArray
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun types() = Type.entries.map { arrayOf<Any>(it) }
    }
}
