package reikai.presentation.novel.browse

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.novel.source.NovelSettings
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import java.util.concurrent.ConcurrentHashMap

class NovelSourceSettingsModelTest {

    private val stored = ConcurrentHashMap<String, JsonElement>()
    private val settings = NovelSettings.LnSchema(
        schema = buildJsonObject {
            put("user", buildJsonObject { put("type", "Text") })
            put(
                "adult",
                buildJsonObject {
                    put("type", "Switch")
                    put("value", false)
                },
            )
        },
        read = { stored[it] },
        write = { key, value -> if (value == null) stored.remove(key) else stored[key] = value },
    )
    private val model = NovelSourceSettingsModel()

    // The model loads and saves on IO, which this puts on the test scheduler beside Main.
    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @Test
    fun `saving writes the changed value and leaves an untouched default unstored`() = runTest {
        model.load(settings)
        model.loaded()
        model.change("user", JsonPrimitive("me"))

        model.saved()

        // A stored default would outlive a plugin update that changes it.
        stored shouldBe mapOf("user" to JsonPrimitive("me"))
    }

    @Test
    fun `saving a setting changed back to its stored value writes nothing`() = runTest {
        stored["user"] = JsonPrimitive("saved")
        model.load(settings)
        model.loaded()
        model.change("user", JsonPrimitive("other"))
        model.change("user", JsonPrimitive("saved"))
        stored.clear()

        model.saved()

        stored shouldBe emptyMap()
    }

    @Test
    fun `reopening drops an unsaved change`() = runTest {
        stored["user"] = JsonPrimitive("saved")
        model.load(settings)
        model.loaded()
        model.change("user", JsonPrimitive("unsaved"))

        model.load(settings)

        model.loaded()["user"] shouldBe JsonPrimitive("saved")
    }

    private suspend fun NovelSourceSettingsModel.saved() {
        val saved = CompletableDeferred<Unit>()
        save(settings) { saved.complete(Unit) }
        saved.await()
    }

    private suspend fun NovelSourceSettingsModel.loaded() = draft.filterNotNull().first()
}
