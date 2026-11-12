package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMangaSourceRef
import eu.kanade.tachiyomi.data.backup.models.customInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/** The source TachiyomiSY and Komikku give a merged entry; Reikai has no such source. */
const val FOREIGN_MERGED_SOURCE_ID = 6969L

// One member of a TachiyomiSY or Komikku merged entry, BackupManga field 600 in both. Their per-member
// update and download switches (2, 5) and sort mode (3) have no Reikai setting, so they are not read.
@Serializable
class BackupForeignMergedEntry(
    @ProtoNumber(1) var isInfoManga: Boolean = false,
    @ProtoNumber(4) var chapterPriority: Int = 0,
    @ProtoNumber(7) var mangaUrl: String = "",
    @ProtoNumber(8) var mangaSourceId: Long = 0,
)

// TachiyomiSY's merge settings, field 604, which name the info entry Komikku marks on the member instead.
@Serializable
class BackupForeignMergeSettings(
    @ProtoNumber(2) var infoMangaUrl: String = "",
    @ProtoNumber(3) var infoMangaSourceId: Long = 0,
)

// A merged entry's merge fields alone, so the summary pass skips every other row's chapters.
@Serializable
private class BackupForeignMergeRow(
    @ProtoNumber(1) val source: Long = 0,
    @ProtoNumber(600) val members: List<BackupForeignMergedEntry> = emptyList(),
    @ProtoNumber(604) val settings: BackupForeignMergeSettings? = null,
)

/**
 * A TachiyomiSY or Komikku merged entry as Reikai restores it: its members, Primary first, become one
 * group in the library, and the entry's own row, whose source Reikai lacks, is left out.
 */
class ForeignMerge private constructor(private val row: BackupForeignMergeRow, private val entry: BackupManga) {

    /** Members in the order the fork showed them: its info entry first, then by chapter priority. */
    val members: List<Pair<Long, String>> = row.members
        .filter { it.mangaSourceId != FOREIGN_MERGED_SOURCE_ID && it.mangaUrl.isNotBlank() }
        .sortedWith(compareByDescending<BackupForeignMergedEntry> { isInfo(it) }.thenBy { it.chapterPriority })
        .map { it.mangaSourceId to it.mangaUrl }
        .distinct()

    private fun isInfo(ref: BackupForeignMergedEntry): Boolean = ref.isInfoManga ||
        row.settings?.let { it.infoMangaSourceId == ref.mangaSourceId && it.infoMangaUrl == ref.mangaUrl } == true

    /** Puts [member] in the library the way the merged entry was, and gives the Primary its own data. */
    fun applyTo(member: BackupManga, isPrimary: Boolean) {
        if (!entry.favorite) return
        if (!member.favorite) {
            member.favorite = true
            member.dateAdded = entry.dateAdded
            member.favoriteModifiedAt = entry.favoriteModifiedAt
        }
        member.categories = (member.categories + entry.categories).distinct()
        if (!isPrimary) return
        val tracked = member.tracking.mapTo(HashSet()) { it.syncId }
        member.tracking += entry.tracking.filterNot { it.syncId in tracked }
        if (member.customInfo == null) member.customInfo = entry.customInfo
        if (member.notes.isBlank()) member.notes = entry.notes
    }

    companion object {
        /** The merged entry the encoded manga row [data] is, or null for any other row. */
        fun of(parser: ProtoBuf, data: ByteArray): ForeignMerge? {
            val row = parser.decodeFromByteArray(BackupForeignMergeRow.serializer(), data)
            if (row.source != FOREIGN_MERGED_SOURCE_ID) return null
            // The full entry only for a merged row, which carries no chapters of its own.
            val entry = parser.decodeFromByteArray(BackupManga.serializer(), data)
            return ForeignMerge(row, entry).takeIf { it.members.isNotEmpty() }
        }
    }
}

/** Every merged entry one backup holds, keyed so the restore can find a member as it decodes it. */
class ForeignMerges(merges: List<ForeignMerge>) {

    // Komikku lists a merged entry twice, once as a favourite and once as a merge.
    private val distinct = merges.distinctBy { it.members }

    // A member of two merges is placed by the first, the rule the group restore applies.
    private val byMember = buildMap {
        distinct.forEach { merge -> merge.members.forEach { putIfAbsent(it, merge) } }
    }

    /** The groups to restore, in each merge's member order. */
    val groups: List<List<BackupMangaSourceRef>> =
        distinct.map { merge -> merge.members.map { (source, url) -> BackupMangaSourceRef(url, source) } }

    fun applyTo(manga: BackupManga): BackupManga = manga.also {
        val key = it.source to it.url
        val merge = byMember[key] ?: return@also
        merge.applyTo(it, isPrimary = merge.members.first() == key)
    }
}

/** A merged entry's own row, left out of a restore: no source in Reikai can ever load it. */
val BackupManga.isForeignMergedRow: Boolean get() = source == FOREIGN_MERGED_SOURCE_ID
