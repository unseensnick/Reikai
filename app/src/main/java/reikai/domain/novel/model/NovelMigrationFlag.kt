package reikai.domain.novel.model

/**
 * Which parts of a novel's state a source migration copies, mirroring Mihon's
 * [mihon.domain.migration.models.MigrationFlag] and stored as a small bitmask in
 * [reikai.domain.novel.NovelPreferences]. Like manga, tracks have no flag: they are always carried on
 * a migration (Mihon dropped its track flag). [REMOVE_DOWNLOAD] deletes the old source's downloaded
 * chapters on migrate, mirroring manga.
 */
enum class NovelMigrationFlag(val bit: Int) {
    CHAPTER(0b00001),
    CATEGORY(0b00010),
    COVER(0b00100),
    NOTES(0b01000),
    REMOVE_DOWNLOAD(0b10000),
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
