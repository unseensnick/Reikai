package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The restore counts an installed app that did not load as installed, so the backup records it too. */
class ExtensionBackupCreatorTest {

    private inline fun <reified T : Extension.Installed> app(pkgName: String) = mockk<T> {
        every { this@mockk.pkgName } returns pkgName
        every { name } returns pkgName.uppercase()
    }

    @Test
    fun `a backup records installed apps whether or not they loaded, manga and novel`() = runTest {
        val manager = mockk<ExtensionManager> {
            every { loadedExtensionsFlow } returns flowOf(listOf(app<Extension.Loaded>("manga.loaded")))
            every { notLoadedExtensionsFlow } returns flowOf(listOf(app<Extension.NotLoaded>("manga.untrusted")))
            every { loadedNovelExtensionsFlow } returns flowOf(listOf(app<Extension.Loaded>("novel.loaded")))
            every { notLoadedNovelExtensionsFlow } returns flowOf(listOf(app<Extension.NotLoaded>("novel.untrusted")))
        }

        ExtensionBackupCreator(manager)().map { it.pkgName to it.name } shouldBe listOf(
            "manga.loaded" to "MANGA.LOADED",
            "manga.untrusted" to "MANGA.UNTRUSTED",
            "novel.loaded" to "NOVEL.LOADED",
            "novel.untrusted" to "NOVEL.UNTRUSTED",
        )
    }
}
