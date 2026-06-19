package reikai.domain.novel.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Which parts of a novel's state a source migration copies. Novels have only two applicable flags:
 * manga's custom-cover / notes / remove-download don't apply, and tracks are deferred (#8). Mirrors
 * [mihon.domain.migration.models.MigrationFlag], stored as a small bitmask in [reikai.domain.novel.NovelPreferences].
 */
enum class NovelMigrationFlag(val bit: Int, val titleRes: StringResource) {
    CHAPTER(0b001, MR.strings.chapters),
    CATEGORY(0b010, MR.strings.categories),
    ;

    companion object {
        val ALL: Set<NovelMigrationFlag> = entries.toSet()

        fun fromBits(bits: Int): Set<NovelMigrationFlag> =
            entries.filterTo(LinkedHashSet()) { it.bit and bits != 0 }

        fun toBits(flags: Set<NovelMigrationFlag>): Int =
            flags.fold(0) { acc, flag -> acc or flag.bit }

        val DEFAULT_BITS: Int = toBits(ALL)
    }
}
