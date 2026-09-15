package reikai.novel.install

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.LnSourceIdentity

class LnPluginLoadFailureTest {

    private val url = "https://repo.test/plugins/novelbin.js"
    private val metadata = LnInstalledPluginMetadata(pluginId = "novelbin", version = "1.2.0")

    /** The host decodes the plugin's info after it runs, so a bad description arrives wrapped. */
    @Test
    fun `a plugin whose description cannot be read is malformed`() {
        val error = IllegalStateException("plugin info", SerializationException("name is required"))

        LnPluginLoadFailure.of(url, error, metadata, seen = null).reason shouldBe LnPluginLoadFailure.Reason.Malformed
    }

    @Test
    fun `a plugin with no stored script is missing`() {
        LnPluginLoadFailure.of(url, LnPluginScriptMissingException(url), metadata, seen = null).reason shouldBe
            LnPluginLoadFailure.Reason.Missing
    }

    @Test
    fun `a plugin that threw failed with its message`() {
        val reason = LnPluginLoadFailure.of(url, IllegalStateException("fetch is not defined"), metadata, null).reason

        reason.shouldBeInstanceOf<LnPluginLoadFailure.Reason.Failed>()
        reason.message shouldBe "fetch is not defined"
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
}
