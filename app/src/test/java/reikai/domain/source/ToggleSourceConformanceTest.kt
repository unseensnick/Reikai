package reikai.domain.source

import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.presentation.recents.EmittingPreferenceStore

/** Switching a source on or off, pinned once for both types: each keeps its own disabled list by its own ids. */
class ToggleSourceConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("toggles")
    fun `a disabled source flips back on`(toggle: Toggle) {
        toggle.disabled = setOf("7")

        toggle.flip("7")

        toggle.disabled shouldBe emptySet()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("toggles")
    fun `switching several off leaves each on the list`(toggle: Toggle) {
        toggle.disabled = setOf("7")

        toggle.switchOff(listOf("8", "9"))

        toggle.disabled shouldBe setOf("7", "8", "9")
    }

    /** One type's toggle over its own disabled list, with ids written as that list stores them. */
    interface Toggle {
        var disabled: Set<String>

        fun flip(id: String)

        fun switchOff(ids: List<String>)
    }

    companion object {
        @JvmStatic
        fun toggles(): List<Toggle> = listOf(
            object : Toggle {
                private val preferences = SourcePreferences(EmittingPreferenceStore())
                private val toggle = ToggleSource(preferences)
                override var disabled: Set<String>
                    get() = preferences.disabledSources.get()
                    set(value) = preferences.disabledSources.set(value)

                override fun flip(id: String) = toggle.await(id.toLong())

                override fun switchOff(ids: List<String>) = toggle.await(ids.map(String::toLong), enable = false)

                override fun toString() = "manga"
            },
            object : Toggle {
                private val preferences = ReikaiSourcePreferences(EmittingPreferenceStore())
                private val toggle = ToggleNovelSource(preferences)
                override var disabled: Set<String>
                    get() = preferences.disabledNovelSources.get()
                    set(value) = preferences.disabledNovelSources.set(value)

                override fun flip(id: String) = toggle.await(id)

                override fun switchOff(ids: List<String>) = toggle.await(ids, enable = false)

                override fun toString() = "novel"
            },
        )
    }
}
