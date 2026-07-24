package reikai.domain.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pins the shared old-id -> name -> new-id translation both restore paths run (manga inline in
 * PreferenceRestorer, novel post-restore in NovelRestorer). The silent-failure risk is dropping vs keeping
 * an id: a category that came back under the same name must survive with its fresh local id, and one that
 * did not must be dropped rather than left dangling.
 */
class TranslateCategoryIdsTest {

    private val backupIdToName = mapOf("1" to "Reading", "2" to "Plan to read", "9" to "Gone")
    private val nameToNewId = mapOf("Reading" to "10", "Plan to read" to "20")

    @Test
    fun `remaps each id through its category name to the new local id`() {
        translateCategoryIds(setOf("1", "2"), backupIdToName, nameToNewId) shouldBe setOf("10", "20")
    }

    @Test
    fun `drops an id whose category name did not come back on restore`() {
        translateCategoryIds(setOf("1", "9"), backupIdToName, nameToNewId) shouldBe setOf("10")
    }

    @Test
    fun `drops an id that is not in the backup category set`() {
        translateCategoryIds(setOf("1", "7"), backupIdToName, nameToNewId) shouldBe setOf("10")
    }

    @Test
    fun `returns empty for empty input`() {
        translateCategoryIds(emptySet(), backupIdToName, nameToNewId) shouldBe emptySet()
    }
}
