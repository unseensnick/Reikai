package reikai.presentation.migrate.flow

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType

/** Which migration a multi-select opens: one content type's own flow, or none for a mixed selection. */
class MigrationRouteTest {

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a selection of one type opens that type's migration`(type: ContentType) {
        EntryMigrationSourcePickScreen.forSelection(listOf(entry(type, 3L), entry(type, 5L)))
            .shouldNotBeNull().contentType shouldBe type
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a selection of one type migrates exactly the selected ids`(type: ContentType) {
        EntryMigrationSourcePickScreen.forSelection(listOf(entry(type, 3L), entry(type, 5L)))
            .shouldNotBeNull().entryIds shouldBe listOf(3L, 5L)
    }

    @Test
    fun `a mixed manga and novel selection opens no migration`() {
        EntryMigrationSourcePickScreen.forSelection(listOf(EntryId.Manga(3L), EntryId.Novel(3L))) shouldBe null
    }

    @Test
    fun `an empty selection opens no migration`() {
        EntryMigrationSourcePickScreen.forSelection(emptyList()) shouldBe null
    }

    private fun entry(type: ContentType, id: Long): EntryId = when (type) {
        ContentType.MANGA -> EntryId.Manga(id)
        ContentType.NOVELS -> EntryId.Novel(id)
        ContentType.ALL -> error("not an entry type")
    }
}
