package reikai.novel.host

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The loader never touches the network here: its NetworkHelper is a strict mock, so a download would
 * throw rather than quietly hand back the repo's latest script.
 */
class LnPluginLoaderTest {

    @TempDir
    lateinit var appFiles: File

    @TempDir
    lateinit var appCache: File

    private val url = "https://repo.test/plugins/novelbin.js"
    private val script = "exports.default = plugin; // v1"

    private val loader by lazy {
        val context = mockk<Context> {
            every { filesDir } returns appFiles
            every { cacheDir } returns appCache
        }
        LnPluginLoader(context, mockk())
    }

    @Test
    fun `an installed plugin loads the script stored for it`() = runTest {
        loader.store(url, script)

        loader.installed(url) shouldBe script
    }

    @Test
    fun `a plugin with no stored script has nothing to load`() = runTest {
        loader.installed(url) shouldBe null
    }

    /** Only an install or update stores a script, and it replaces the one that was running. */
    @Test
    fun `storing a script replaces the installed one`() = runTest {
        loader.store(url, script)

        loader.store(url, "exports.default = plugin; // v2")

        loader.installed(url) shouldBe "exports.default = plugin; // v2"
    }

    /** Scripts used to be kept in the cache folder; an upgrade keeps the version the user had. */
    @Test
    fun `a script left in the cache folder is loaded rather than lost`() = runTest {
        loader.store(url, script)
        val stored = appFiles.resolve("lnplugins").listFiles()!!.single()
        stored.renameTo(appCache.resolve("lnplugins").apply { mkdirs() }.resolve(stored.name))

        loader.installed(url) shouldBe script
    }

    /** A partial download left in the cache folder would fail to load, so it counts as missing. */
    @Test
    fun `a truncated script left in the cache folder has nothing to load`() = runTest {
        loader.store(url, "var plugin = { name: ")
        val stored = appFiles.resolve("lnplugins").listFiles()!!.single()
        stored.renameTo(appCache.resolve("lnplugins").apply { mkdirs() }.resolve(stored.name))

        loader.installed(url) shouldBe null
    }
}
