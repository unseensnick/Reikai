package reikai.domain.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the shared old-id -> name -> new-id translation PreferenceRestorer runs on both content types'
 * category-id settings. The silent-failure risk is dropping vs keeping an id: a category that came back
 * under the same name must survive with its fresh local id, and one that did not must be dropped rather
 * than left dangling.
 */
class TranslateCategoryIdTest {

    private val backupIdToName = mapOf("1" to "Reading", "2" to "Plan to read", "9" to "Gone")
    private val nameToNewId = mapOf("Reading" to "10", "Plan to read" to "20")

    @Test
    fun `remaps an id through its category name to the new local id`() {
        translateCategoryId("2", backupIdToName, nameToNewId) shouldBe "20"
    }

    @Test
    fun `drops an id whose category name did not come back on restore`() {
        translateCategoryId("9", backupIdToName, nameToNewId) shouldBe null
    }

    @Test
    fun `drops an id that is not in the backup category set`() {
        translateCategoryId("7", backupIdToName, nameToNewId) shouldBe null
    }

    @Test
    fun `keeps the Default category, which is 0 in every app that writes these backups`() {
        translateCategoryId("0", backupIdToName, nameToNewId) shouldBe "0"
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
}
