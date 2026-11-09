package reikai.novel.host

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore

/**
 * An engine's storage is the one plugin Kotlin bound it to. JS names keys only, so plugin code calling the
 * runtime (or its loader) with another plugin's id has no way to reach that plugin's storage.
 */
class EngineStorageTest {

    private val bridge = LnHostBridge(EmittingPreferenceStore(), mockk(relaxed = true)).apply {
        setStorage(VICTIM, "webview:local", SECRET)
    }

    @Test
    fun `an engine bound to one plugin reads only that plugin's storage`() {
        EngineStorage(bridge, ATTACKER).get("webview:local") shouldBe null
    }

    @Test
    fun `an engine bound to one plugin writes only that plugin's storage`() {
        EngineStorage(bridge, ATTACKER).set("webview:local", "overwritten")

        bridge.getStorage(VICTIM, "webview:local") shouldBe SECRET
    }

    // The engine reading a script whose declared id the installer has not accepted.
    @Test
    fun `an engine bound to no plugin reads nothing`() {
        EngineStorage(bridge, null).get("webview:local") shouldBe null
    }

    @Test
    fun `an engine bound to no plugin writes nothing`() {
        EngineStorage(bridge, null).set("k", "v")

        listOf(bridge.getStorage(VICTIM, "k"), bridge.getStorage(ATTACKER, "k")) shouldBe listOf(null, null)
    }

    @Test
    fun `an engine bound to a plugin reads its own storage`() {
        EngineStorage(bridge, VICTIM).get("webview:local") shouldBe SECRET
    }

    private companion object {
        const val VICTIM = "victim"
        const val ATTACKER = "attacker"
        const val SECRET = """{"token":"signed-in"}"""
    }
}
