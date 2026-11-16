package reikai.novel.install

import android.content.Context
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.LnSourceIdentity
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.model.SourceNotInstalledException
import tachiyomi.i18n.MR
import java.io.IOException
import java.net.UnknownHostException

class LnPluginLoadFailureTest {

    private val url = "https://repo.test/plugins/novelbin.js"
    private val metadata = LnInstalledPluginMetadata(pluginId = "novelbin", version = "1.2.0")

    /** The host decodes the plugin's info after it runs, so a bad description arrives wrapped. */
    @Test
    fun `a plugin whose description cannot be read is malformed`() {
        val error = IllegalStateException("plugin info", SerializationException("name is required"))

        LnPluginLoadFailure.of(url, error, metadata, seen = null).reason shouldBe LnPluginLoadFailure.Reason.Malformed
    }

    /** The refetch fails inside OkHttp's bare IOException, so the cause worth showing is two levels down. */
    @Test
    fun `a plugin whose script could not be fetched again is missing and names the real cause`() {
        val error = LnPluginScriptMissingException(url, IOException("wrapper", UnknownHostException("repo.test")))

        LnPluginLoadFailure.of(url, error, metadata, seen = null).reason shouldBe
            LnPluginLoadFailure.Reason.Missing("UnknownHostException: repo.test")
    }

    @Test
    fun `a plugin that threw failed with its message`() {
        val reason = LnPluginLoadFailure.of(url, IllegalStateException("fetch is not defined"), metadata, null).reason

        reason.shouldBeInstanceOf<LnPluginLoadFailure.Reason.Failed>()
        reason.message shouldBe "IllegalStateException: fetch is not defined"
    }

    /** A network failure arrives wrapped in a bare IOException, as a failed manga extension's does. */
    @Test
    fun `a plugin whose script fetch failed shows the real cause`() {
        val error = IOException("wrapper", UnknownHostException("repo.test"))

        val reason = LnPluginLoadFailure.of(url, error, metadata, null).reason

        reason.shouldBeInstanceOf<LnPluginLoadFailure.Reason.Failed>()
        reason.message shouldBe "UnknownHostException: repo.test"
    }

    @Test
    fun `a plugin is named from the source it last loaded as`() {
        val seen = LnSourceIdentity(name = "NovelBin", lang = "English")

        LnPluginLoadFailure.of(url, IllegalStateException(), metadata, seen).name shouldBe "NovelBin"
    }

    /** A plugin that never loaded here has no remembered name, so its file name stands in. */
    @Test
    fun `a plugin that never loaded is named from its file`() {
        LnPluginLoadFailure.of(url, IllegalStateException(), metadata = null, seen = null).name shouldBe "novelbin"
    }

    @Test
    fun `an installed plugin that failed to load is unreachable for its load's reason`() {
        mockkStatic(LOCALIZE) {
            every { any<Context>().stringResource(MR.strings.ext_malformed_message) } returns "Malformed"
            val failures = mapOf(url to LnPluginLoadFailure.of(url, SerializationException("x"), metadata, null))

            failures.unreachable("novelbin", mockk()).message shouldBe "Malformed"
        }
    }

    @Test
    fun `a plugin that is not installed is unreachable as not installed`() {
        emptyMap<String, LnPluginLoadFailure>().unreachable("novelbin", mockk())
            .shouldBeInstanceOf<SourceNotInstalledException>()
    }

    private companion object {
        const val LOCALIZE = "tachiyomi.core.common.i18n.LocalizeKt"
    }
}
