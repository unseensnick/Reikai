package reikai.presentation.components

import androidx.compose.ui.graphics.vector.ImageVector
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.AttachMoney
import mihon.icons.materialsymbols.rounded.Block
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Done
import mihon.icons.materialsymbols.rounded.DoneAll
import mihon.icons.materialsymbols.rounded.Pause
import mihon.icons.materialsymbols.rounded.Schedule
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.i18n.MR

/** Library grouping, the details header and the duplicate cards all read a status through this table. */
class EntryStatusTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("labels")
    fun `a status code names its label`(code: Int, label: StringResource) {
        entryStatusRes(code.toLong()) shouldBe label
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("icons")
    fun `a status code draws its icon`(code: Int, icon: ImageVector) {
        entryStatusIcon(code.toLong()) shouldBe icon
    }

    companion object {
        @JvmStatic
        fun labels() = listOf(
            Arguments.of(SManga.UNKNOWN, MR.strings.unknown),
            Arguments.of(SManga.ONGOING, MR.strings.ongoing),
            Arguments.of(SManga.COMPLETED, MR.strings.completed),
            Arguments.of(SManga.LICENSED, MR.strings.licensed),
            Arguments.of(SManga.PUBLISHING_FINISHED, MR.strings.publishing_finished),
            Arguments.of(SManga.CANCELLED, MR.strings.cancelled),
            Arguments.of(SManga.ON_HIATUS, MR.strings.on_hiatus),
            Arguments.of(99, MR.strings.unknown),
        )

        @JvmStatic
        fun icons() = listOf(
            Arguments.of(SManga.UNKNOWN, MaterialSymbols.Rounded.Block),
            Arguments.of(SManga.ONGOING, MaterialSymbols.Rounded.Schedule),
            Arguments.of(SManga.COMPLETED, MaterialSymbols.Rounded.DoneAll),
            Arguments.of(SManga.LICENSED, MaterialSymbols.Rounded.AttachMoney),
            Arguments.of(SManga.PUBLISHING_FINISHED, MaterialSymbols.Rounded.Done),
            Arguments.of(SManga.CANCELLED, MaterialSymbols.Rounded.Close),
            Arguments.of(SManga.ON_HIATUS, MaterialSymbols.Rounded.Pause),
            Arguments.of(99, MaterialSymbols.Rounded.Block),
        )
    }
}
