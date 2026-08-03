package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.i18n.stringResource

/** The data-to-migrate checkboxes, rendered wherever a migration is confirmed. Only [applicable]
 *  flags are offered: a flag no entry in this migration can use is left out, never shown disabled. */
@Composable
internal fun MigrationFlagChecks(
    applicable: Set<MigrationDataFlag>,
    selected: Set<MigrationDataFlag>,
    onToggle: (MigrationDataFlag) -> Unit,
) {
    // Iterated in enum order so the list does not reshuffle between openings.
    MigrationDataFlag.entries.filter { it in applicable }.forEach { flag ->
        LabeledCheckbox(
            label = stringResource(flag.label()),
            checked = flag in selected,
            onCheckedChange = { onToggle(flag) },
        )
    }
}

private fun MigrationDataFlag.label(): StringResource = when (this) {
    MigrationDataFlag.CHAPTER -> MR.strings.chapters
    MigrationDataFlag.CATEGORY -> MR.strings.categories
    MigrationDataFlag.CUSTOM_COVER -> MR.strings.custom_cover
    MigrationDataFlag.NOTES -> MR.strings.action_notes
    MigrationDataFlag.REMOVE_DOWNLOAD -> MR.strings.delete_downloaded
}

/** Flag sets survive rotation as their names; the enum itself is not parcelable. */
internal val migrationFlagSaver: Saver<Set<MigrationDataFlag>, List<String>> = Saver(
    save = { flags -> flags.map { it.name } },
    restore = { names -> names.mapTo(LinkedHashSet()) { MigrationDataFlag.valueOf(it) } },
)
