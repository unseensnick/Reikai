package reikai.presentation.browse.repos

import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Test
import reikai.domain.extension.NO_SIGNING_KEY
import reikai.domain.extension.RepoStatus
import reikai.domain.extension.toRepoStatus
import java.io.IOException

class RepoCardsTest {

    @Test
    fun `a store's listing counts each kind it lists`() {
        Result.success(listOf(available(Extension.Kind.MANGA), available(Extension.Kind.TACHIYOMI_NOVEL)))
            .toRepoStatus() shouldBe RepoStatus.Reached(manga = 1, novels = 1)
    }

    @Test
    fun `a store that could not be read is unreachable, not empty`() {
        Result.failure<List<Extension.Available>>(IllegalStateException("HTTP 404"))
            .toRepoStatus() shouldBe RepoStatus.Unreachable("HTTP 404")
    }

    @Test
    fun `a repo the last fetch did not cover reads as checking`() {
        repoCards(listOf(STORE), storeStatuses = emptyMap(), pluginRepos = emptySet(), pluginStatuses = null)
            .single().status shouldBe RepoStatus.Checking
    }

    @Test
    fun `a store that publishes no key shows none`() {
        repoCards(listOf(STORE.copy(signingKey = NO_SIGNING_KEY)), null, emptySet(), null).single().signingKey
            .shouldBeNull()
    }

    @Test
    fun `a store's own key is shown`() {
        repoCards(listOf(STORE), null, emptySet(), null).single().signingKey shouldBe "key"
    }

    @Test
    fun `a GitHub plugin repo is named after its owner`() {
        repoCards(emptyList(), null, setOf(LN_REPO), null).single().name shouldBe "LNReader"
    }

    @Test
    fun `stores and plugin repos form one list sorted by name`() {
        repoCards(listOf(STORE.copy(name = "Suwayomi")), null, setOf(LN_REPO), null).map { it.name } shouldBe
            listOf("LNReader", "Suwayomi")
    }

    @Test
    fun `cards name their type when the list holds both`() {
        val cards = repoCards(listOf(STORE), mapOf(STORE.indexUrl to RepoStatus.Reached(3, 0)), setOf(LN_REPO), null)

        showTypeBadges(cards) shouldBe true
    }

    @Test
    fun `cards do not name their type when the list holds one`() {
        val cards = repoCards(listOf(STORE), mapOf(STORE.indexUrl to RepoStatus.Reached(3, 0)), emptySet(), null)

        showTypeBadges(cards) shouldBe false
    }

    @Test
    fun `an unreachable store claims no type`() {
        val cards = repoCards(listOf(STORE), mapOf(STORE.indexUrl to RepoStatus.Unreachable("x")), setOf(LN_REPO), null)

        showTypeBadges(cards) shouldBe false
    }

    @Test
    fun `an address that reads as a plugin repo is never tried as a store`() = runTest {
        var storeTried = false

        addRepoOfEitherKind(
            addPluginRepo = { Result.success(Unit) },
            addStore = {
                storeTried = true
                Result.success(Unit)
            },
        )

        storeTried shouldBe false
    }

    @Test
    fun `an address that is not a plugin repo is added as a store`() = runTest {
        addRepoOfEitherKind(
            addPluginRepo = { Result.failure(IllegalStateException()) },
            addStore = { Result.success(Unit) },
        ) shouldBe AddRepoOutcome.ADDED
    }

    @Test
    fun `an address neither kind can read holds no repo`() = runTest {
        addRepoOfEitherKind(
            addPluginRepo = { Result.failure(IllegalStateException()) },
            addStore = { Result.failure(IllegalStateException()) },
        ) shouldBe AddRepoOutcome.NOT_A_REPO
    }

    @Test
    fun `an address neither kind could connect to was not reached`() = runTest {
        addRepoOfEitherKind(
            addPluginRepo = { Result.failure(IOException("offline")) },
            addStore = { Result.failure(IOException("offline")) },
        ) shouldBe AddRepoOutcome.UNREACHABLE
    }

    private fun available(kind: Extension.Kind) = Extension.Available(
        name = "Entry",
        pkgName = "pkg.${kind.name}",
        versionName = "1.6.1",
        versionCode = 1,
        libVersion = 1.6,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        kind = kind,
        sources = emptyList(),
        apkUrl = "a.apk",
        iconUrl = "a.png",
        store = STORE,
    )

    private companion object {
        val STORE = ExtensionStore(
            indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
            name = "Keiyoushi",
            badgeLabel = "KEI",
            signingKey = "key",
            contact = ExtensionStore.Contact(website = "https://keiyoushi.github.io", discord = null),
            isLegacy = false,
            extensionListUrl = null,
        )
        const val LN_REPO =
            "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"
    }
}
