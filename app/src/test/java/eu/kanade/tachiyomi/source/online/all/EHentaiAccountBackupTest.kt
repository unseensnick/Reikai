package eu.kanade.tachiyomi.source.online.all

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.SourceTracker
import eu.kanade.tachiyomi.source.model.SManga
import exh.source.ExhPreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import mihon.app.di.AppGraph
import mihon.app.di.injekt.MetroInjektRegistrar
import mihon.core.metro.GraphProvider
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.core.common.preference.Preference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope

/**
 * E-Hentai backs a newly added gallery up to the account through the source-tracker hook, which every
 * add path reaches, and only while the account backup is on.
 */
class EHentaiAccountBackupTest {

    private val replaced: InjektScope = Injekt

    @AfterEach
    fun restoreInjekt() {
        Injekt = replaced
    }

    // The source reads its client and preferences through Injekt as it is built, as the app's does.
    private fun eHentai(loggedIn: Boolean = true, backupOn: Boolean = true, slot: Int = 3): EHentai {
        val preferences = ExhPreferences(
            InMemoryPreferenceStore(
                sequenceOf(
                    InMemoryPreference(Preference.privateKey("enable_exhentai"), loggedIn, false),
                    InMemoryPreference("eh_backup_favorites_to_account", backupOn, false),
                    InMemoryPreference("eh_favorites_backup_slot", slot, 0),
                ),
            ),
        )
        val graph = mockk<AppGraph>(relaxed = true) {
            every { exhPreferences } returns preferences
            every { networkHelper } returns mockk<NetworkHelper>(relaxed = true) {
                every { client } returns OkHttpClient()
            }
        }
        val application = mockk<Application>(relaxed = true, moreInterfaces = arrayOf(GraphProvider::class))
        @Suppress("UNCHECKED_CAST")
        every { (application as GraphProvider<AppGraph>).graph } returns graph
        Injekt = InjektScope(MetroInjektRegistrar(application, application as GraphProvider<AppGraph>))
        return EHentai(id = 1L, exh = true, context = application)
    }

    private val gallery = SManga.create().apply { url = "/g/123/abcdef1234/" }

    @Test
    fun `a gallery is backed up while the account backup is on`() {
        (eHentai() as SourceTracker).supportsFavoritesTracking shouldBe true
    }

    @Test
    fun `nothing is backed up while the account backup is off`() {
        (eHentai(backupOn = false) as SourceTracker).supportsFavoritesTracking shouldBe false
    }

    @Test
    fun `nothing is backed up while signed out of the account`() {
        (eHentai(loggedIn = false) as SourceTracker).supportsFavoritesTracking shouldBe false
    }

    @Test
    fun `reading a gallery is never sent to the site`() {
        (eHentai() as SourceTracker).supportsChapterTracking shouldBe false
    }

    @Test
    fun `an added gallery goes to the account's chosen favorites slot`() = runTest {
        val source = spyk(eHentai(slot = 3))
        coEvery { source.addFavorite(any(), any(), any()) } just runs

        (source as SourceTracker).onFavorited(gallery, emptyList())

        coVerify { source.addFavorite("123", "abcdef1234", 3) }
    }
}
