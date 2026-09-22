package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.configurableSources
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test

class SourcePreferencesBackupTest {

    private fun configurable(id: Long) = mockk<ConfigurableSource> { every { this@mockk.id } returns id }

    private fun novelApp(vararg sources: Source) = Extension.Loaded(
        name = "app",
        pkgName = "eu.kanade.tachiyomi.novelextension.app",
        versionName = "1.4.1",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        isShared = true,
        kind = Extension.Kind.TACHIYOMI_NOVEL,
        pkgFactory = null,
        sources = sources.toList(),
        icon = null,
    )

    @Test
    fun `a novel app's settings go into the backup beside the manga sources'`() {
        configurableSources(listOf(configurable(1)), listOf(novelApp(configurable(2)))).map { it.id } shouldBe
            listOf(1L, 2L)
    }

    @Test
    fun `a settings file a manga and a novel source share is written once`() {
        configurableSources(listOf(configurable(7)), listOf(novelApp(configurable(7)))).map { it.id } shouldBe
            listOf(7L)
    }

    @Test
    fun `a source with no settings is left out`() {
        configurableSources(emptyList(), listOf(novelApp(mockk<Source>()))) shouldBe emptyList()
    }
}
