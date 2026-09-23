package reikai.presentation.browse.extension

import eu.kanade.domain.extension.model.Extensions
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

/** An extension store counts as a novel repo only once it lists a novel apk. */
class ApkExtensionsProviderTest {

    @Test
    fun `a store listing no novel apk is a repo for manga only`() = runTest {
        val model = mockk<ExtensionsViewModel>(relaxed = true) {
            every { hasRepos } returns MutableStateFlow(true)
            every { novelExtensions } returns
                MutableStateFlow(Extensions(emptyList(), emptyList(), emptyList(), emptyList()))
        }

        ApkExtensionsProvider(model).reposFor.first() shouldBe setOf(ContentType.MANGA)
    }

    @Test
    fun `a store listing a novel apk is a repo for novels too`() = runTest {
        val model = mockk<ExtensionsViewModel>(relaxed = true) {
            every { hasRepos } returns MutableStateFlow(true)
            every { novelExtensions } returns
                MutableStateFlow(Extensions(emptyList(), emptyList(), listOf(mockk()), emptyList()))
        }

        ApkExtensionsProvider(model).reposFor.first() shouldBe setOf(ContentType.MANGA, ContentType.NOVELS)
    }

    @Test
    fun `an apk with an update pending names the version it brings`() {
        val pending = mockk<Extension.Loaded>(relaxed = true) { every { pkgName } returns "pkg" }
        val extensions = Extensions(listOf(pending), emptyList(), emptyList(), emptyList(), mapOf("pkg" to "1.6.8"))

        apkExtensionRows(extensions, emptyMap()) { ExtensionKey.Manga(it.pkgName) }!!.single().updateVersion shouldBe
            "1.6.8"
    }
}
