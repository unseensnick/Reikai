package reikai.domain.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Pins the shared old-id -> name -> new-id translation PreferenceRestorer runs on both content types'
 * category-id settings. The silent-failure risk is dropping vs keeping an id: a category that came back
 * under the same name must survive with its fresh local id, and one that did not must be dropped rather
 * than left dangling.
 */
class TranslateCategoryIdTest {

    private val backupIdToName = mapOf("1" to "Reading", "2" to "Plan to read", "9" to "Gone")
    private val nameToNewId = mapOf("Reading" to "10", "Plan to read" to "20")

    @ParameterizedTest(name = "{0}")
    @MethodSource("translations")
    fun `an id survives a restore only through a category name that came back`(case: String, id: String, new: String?) {
        translateCategoryId(id, backupIdToName, nameToNewId) shouldBe new
    }

    @Test
    fun `names each backup id by its category when every id is distinct`() {
        backupCategoryIdToName(listOf(1L to "Reading", 2L to "Completed")) shouldBe
            mapOf("1" to "Reading", "2" to "Completed")
    }

    @Test
    fun `names no backup id when two categories share one, as a Yokai backup's all do`() {
        backupCategoryIdToName(listOf(0L to "Reading", 0L to "Completed")) shouldBe emptyMap()
    }

    private companion object {
        @JvmStatic
        fun translations() = listOf(
            Arguments.of("remapped through its name to the new local id", "2", "20"),
            Arguments.of("dropped when its name did not come back", "9", null),
            Arguments.of("dropped when the backup has no such category", "7", null),
            Arguments.of("the Default category, 0 in every app that writes these backups, is kept", "0", "0"),
        )
    }
}
