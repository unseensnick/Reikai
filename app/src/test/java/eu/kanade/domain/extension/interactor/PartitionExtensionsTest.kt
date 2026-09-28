package eu.kanade.domain.extension.interactor

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test

/** An installed extension with an update pending carries the version its store now lists. */
class PartitionExtensionsTest {

    @Test
    fun `a pending update names the version its store lists`() {
        val extensions = partitionExtensions(
            enabledLanguages = null,
            enabledContentWarnings = ContentWarning.entries.toSet(),
            installed = listOf(installed("app", "1.6.7", hasUpdate = true), installed("other", "1.0.0")),
            failed = emptyList(),
            offered = listOf(offered("app", "1.6.8"), offered("other", "1.0.0")),
        )

        extensions.updateVersions shouldBe mapOf("app" to "1.6.8")
    }

    @Test
    fun `a not-loaded extension with an update pending is listed with the updates`() {
        val extensions = partitionExtensions(
            enabledLanguages = null,
            enabledContentWarnings = ContentWarning.entries.toSet(),
            installed = emptyList(),
            failed = listOf(failed("app", hasUpdate = true), failed("other")),
            offered = listOf(offered("app", "1.6.8")),
        )

        extensions.updates.map { it.pkgName } to extensions.notLoaded.map { it.pkgName } shouldBe
            (listOf("app") to listOf("other"))
    }

    @Test
    fun `a not-loaded extension's pending update names the version its store lists`() {
        val extensions = partitionExtensions(
            enabledLanguages = null,
            enabledContentWarnings = ContentWarning.entries.toSet(),
            installed = emptyList(),
            failed = listOf(failed("app", hasUpdate = true)),
            offered = listOf(offered("app", "1.6.8")),
        )

        extensions.updateVersions shouldBe mapOf("app" to "1.6.8")
    }

    private fun failed(pkg: String, hasUpdate: Boolean = false) = Extension.NotLoaded(
        name = pkg,
        pkgName = pkg,
        versionName = "1.0.0",
        versionCode = 1,
        isShared = true,
        contentWarning = ContentWarning.SAFE,
        signatures = emptyList(),
        kind = Extension.Kind.MANGA,
        hasUpdate = hasUpdate,
        reason = Extension.NotLoaded.Reason.Unsigned,
    )

    private fun installed(pkg: String, version: String, hasUpdate: Boolean = false) = Extension.Loaded(
        name = pkg,
        pkgName = pkg,
        versionName = version,
        versionCode = 1,
        libVersion = 1.6,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        isShared = true,
        signatures = emptyList(),
        kind = Extension.Kind.MANGA,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        hasUpdate = hasUpdate,
    )

    private fun offered(pkg: String, version: String) = Extension.Available(
        name = pkg,
        pkgName = pkg,
        versionName = version,
        versionCode = 2,
        libVersion = 1.6,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        kind = Extension.Kind.MANGA,
        sources = emptyList(),
        apkUrl = "",
        iconUrl = "",
        store = mockk(),
    )
}
