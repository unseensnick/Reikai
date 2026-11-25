package reikai.util

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore

class MembershipPreferenceTest {

    @Test
    fun `a write to the set from elsewhere shows on the switch`() = runTest {
        val set = EmittingPreferenceStore().getStringSet("s", setOf("eh"))
        val seen = async { set.containing("eh").changes().take(2).toList() }
        runCurrent()

        set.set(setOf("other"))

        seen.await() shouldBe listOf(true, false)
    }

    @Test
    fun `turning the switch off removes only its element`() {
        val set = EmittingPreferenceStore().getStringSet("s", setOf("eh", "other"))
        set.containing("eh").set(false)
        set.get() shouldBe setOf("other")
    }

    @Test
    fun `turning the switch on adds its element`() {
        val set = EmittingPreferenceStore().getStringSet("s", setOf("other"))
        set.containing("eh").set(true)
        set.get() shouldBe setOf("other", "eh")
    }
}
