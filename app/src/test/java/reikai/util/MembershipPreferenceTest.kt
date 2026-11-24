package reikai.util

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore

class MembershipPreferenceTest {

    @Test
    fun `a write to the set from elsewhere shows on the switch`() = runTest {
        val set = EmittingPreferenceStore().getStringSet("s", emptySet())
        set.set(setOf("eh"))
        set.containing("eh").changes().first() shouldBe true
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
