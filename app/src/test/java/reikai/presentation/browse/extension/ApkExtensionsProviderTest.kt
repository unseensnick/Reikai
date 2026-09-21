package reikai.presentation.browse.extension

import eu.kanade.domain.extension.model.Extensions
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
}
