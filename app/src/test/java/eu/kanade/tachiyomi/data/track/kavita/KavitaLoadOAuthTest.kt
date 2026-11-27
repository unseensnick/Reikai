package eu.kanade.tachiyomi.data.track.kavita

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import mihon.app.di.AppBindings
import org.junit.jupiter.api.Test
import reikai.data.track.trackerTestDependencies
import tachiyomi.domain.source.service.SourceManager

/** Kavita reads its server logins from the Kavita extension's own settings, one slot per source. */
class KavitaLoadOAuthTest {

    @Test
    fun `an uninstalled Kavita extension leaves every slot signed out`() {
        val sourceManager = mockk<SourceManager>()
        coEvery { sourceManager.get(any()) } returns null
        val kavita = Kavita(trackerTestDependencies(), AppBindings.providesJson(), lazyOf(sourceManager))

        kavita.loadOAuth()

        kavita.authentications!!.authentications.map { it.jwtToken } shouldBe listOf("", "", "")
    }
}
