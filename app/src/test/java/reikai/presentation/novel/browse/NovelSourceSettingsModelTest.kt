package reikai.presentation.novel.browse

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.novel.source.NovelSettings
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

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `saving writes every drafted value`() = runTest {
        model.load(settings)
        model.loaded()
        model.change("user", JsonPrimitive("me"))
        val saved = CompletableDeferred<Unit>()

        model.save(settings) { saved.complete(Unit) }
        withContext(Dispatchers.Default) { withTimeout(5_000) { saved.await() } }

        stored shouldBe mapOf("user" to JsonPrimitive("me"), "adult" to JsonPrimitive(false))
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

    private suspend fun NovelSourceSettingsModel.loaded() =
        withContext(Dispatchers.Default) { withTimeout(5_000) { draft.filterNotNull().first() } }
}
