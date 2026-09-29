package eu.kanade.domain.extension.interactor

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import reikai.domain.extension.NO_SIGNING_KEY

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
            storeKeys = setOf(KEY),
        )

        extensions.updateVersions shouldBe mapOf("app" to "1.6.8")
    }

    @Test
    fun `a pending update names the version the store signing the apk lists, not another store's`() {
        val extensions = partitionExtensions(
            enabledLanguages = null,
            enabledContentWarnings = ContentWarning.entries.toSet(),
            installed = listOf(installed("app", "1.6.7", hasUpdate = true)),
            failed = emptyList(),
            offered = listOf(offered("app", "9.9.9", store = otherKey), offered("app", "1.6.8")),
            storeKeys = setOf(KEY, "other"),
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
            storeKeys = setOf(KEY),
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
            storeKeys = setOf(KEY),
        )

        extensions.updateVersions shouldBe mapOf("app" to "1.6.8")
    }

    @Test
    fun `a listing from a store whose key does not sign the installed apk is offered beside it`() {
        val extensions = partitionExtensions(
            enabledLanguages = null,
            enabledContentWarnings = ContentWarning.entries.toSet(),
            installed = listOf(installed("app", "1.0.0")),
            failed = emptyList(),
            offered = listOf(offered("app", "1.0.0"), offered("app", "1.0.0", store = otherKey)),
            storeKeys = setOf(KEY),
        )

        extensions.available.map { it.store.signingKey } shouldBe listOf("other")
    }

    @Test
    fun `a keyless store's listing of an installed extension is not offered again`() {
        val extensions = partitionExtensions(
            enabledLanguages = null,
            enabledContentWarnings = ContentWarning.entries.toSet(),
            installed = listOf(installed("app", "1.0.0")),
            failed = emptyList(),
            offered = listOf(offered("app", "1.0.0", store = keyless)),
            storeKeys = setOf(KEY),
        )

        extensions.available shouldBe emptyList()
    }

    private fun failed(pkg: String, hasUpdate: Boolean = false) = Extension.NotLoaded(
        name = pkg,
        pkgName = pkg,
        versionName = "1.0.0",
        versionCode = 1,
        isShared = true,
        contentWarning = ContentWarning.SAFE,
        signatures = listOf(KEY),
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
        signatures = listOf(KEY),
        kind = Extension.Kind.MANGA,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        hasUpdate = hasUpdate,
    )

    private fun offered(pkg: String, version: String, store: ExtensionStore = keyed) = Extension.Available(
        name = pkg,
        pkgName = pkg,
        versionName = version,
        versionCode = 2,
        libVersion = 1.6,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        kind = Extension.Kind.MANGA,
        sources = listOf(Extension.Available.Source(id = 1, lang = "en", name = pkg, baseUrl = "")),
        apkUrl = "",
        iconUrl = "",
        store = store,
    )

    private companion object {
        const val KEY = "key"

        val keyed = ExtensionStore(
            indexUrl = "https://a.example/index.min.json",
            name = "A",
            badgeLabel = "A",
            signingKey = KEY,
            contact = ExtensionStore.Contact(website = "", discord = null),
            isLegacy = false,
            extensionListUrl = null,
        )
        val otherKey = keyed.copy(indexUrl = "https://b.example/index.min.json", signingKey = "other")
        val keyless = keyed.copy(indexUrl = "https://c.example/index.min.json", signingKey = NO_SIGNING_KEY)
    }
}
