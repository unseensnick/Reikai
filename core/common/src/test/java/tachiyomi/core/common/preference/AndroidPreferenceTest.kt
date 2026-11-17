package tachiyomi.core.common.preference

import android.content.SharedPreferences
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Test

/**
 * A typed preferences class whose getters build a new preference per call (NovelPreferences) still hands a
 * composable's `remember(pref)` the same key, so its collector is not torn down on every recomposition.
 */
class AndroidPreferenceTest {

    private val store = mockk<SharedPreferences>(relaxed = true)

    private fun int(key: String, preferences: SharedPreferences = store) =
        AndroidPreference.IntPrimitive(preferences, emptyFlow(), key, 0)

    @Test
    fun `two preferences on one store and key are equal`() {
        (int("size") to int("size").hashCode()) shouldBe (int("size") to int("size").hashCode())
    }

    @Test
    fun `preferences on different keys are not equal`() {
        int("size") shouldNotBe int("spacing")
    }

    @Test
    fun `preferences on different stores are not equal`() {
        int("size") shouldNotBe int("size", mockk(relaxed = true))
    }

    /** `remember(pref)` must never hand a composable a preference of another type on the same key. */
    @Test
    fun `preferences of different types on one key are not equal`() {
        int("size") shouldNotBe AndroidPreference.LongPrimitive(store, emptyFlow(), "size", 0L)
    }
}
