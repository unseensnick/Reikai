package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMangaSourceRef
import eu.kanade.tachiyomi.data.backup.models.customInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import reikai.domain.backup.KitsuBackupScoreScale

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

// A manga row's key and, on a merged entry, its merge fields alone, so the summary pass skips every row's chapters.
@Serializable
internal class BackupForeignMergeRow(
    @ProtoNumber(1) val source: Long = 0,
    @ProtoNumber(2) val url: String = "",
    @ProtoNumber(600) val members: List<BackupForeignMergedEntry> = emptyList(),
    @ProtoNumber(604) val settings: BackupForeignMergeSettings? = null,
)

/**
 * A TachiyomiSY or Komikku merged entry as Reikai restores it: its members, Primary first, become one
 * group in the library, and the entry's own row, whose source Reikai lacks, is left out.
 */
class ForeignMerge internal constructor(private val row: BackupForeignMergeRow, private val entry: BackupManga) {

    /** Members in the order the fork showed them: its info entry first, then by chapter priority. */
    val members: List<Pair<Long, String>> = row.members
        .filter { it.mangaSourceId != FOREIGN_MERGED_SOURCE_ID && it.mangaUrl.isNotBlank() }
        .sortedWith(compareByDescending<BackupForeignMergedEntry> { isInfo(it) }.thenBy { it.chapterPriority })
        .map { it.mangaSourceId to it.mangaUrl }
        .distinct()

    private fun isInfo(ref: BackupForeignMergedEntry): Boolean = ref.isInfoManga ||
        row.settings?.let { it.infoMangaSourceId == ref.mangaSourceId && it.infoMangaUrl == ref.mangaUrl } == true

    /** Its tracks on the file's Kitsu scale, which pass 1 decides only after it has decoded this entry. */
    fun rescaleKitsu(scale: KitsuBackupScoreScale): ForeignMerge = apply { entry.rescaleKitsu(scale) }

    /** Puts [member] in the library the way the merged entry was, and gives the Primary its own data and settings. */
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
        // The fork showed the merged entry's own chapter and reader settings, never a member's.
        member.chapterFlags = entry.chapterFlags
        member.viewer_flags = entry.viewer_flags ?: entry.viewer
        member.excludedScanlators = entry.excludedScanlators
    }
}

/**
 * Pass 1's reading of a backup's manga rows: each merged entry, and which rows the file holds, since a member
 * the file lacks restores nowhere and so cannot be its group's Primary. The keys go once [merges] is built.
 */
class ForeignMergeReader {
    private val merges = mutableListOf<ForeignMerge>()
    private val rows = HashSet<Pair<Long, String>>()

    fun read(parser: ProtoBuf, data: ByteArray) {
        val row = parser.decodeFromByteArray(BackupForeignMergeRow.serializer(), data)
        if (row.source != FOREIGN_MERGED_SOURCE_ID) {
            rows += row.source to row.url
            return
        }
        // The full entry only for a merged row, which carries no chapters of its own.
        val merge = ForeignMerge(row, parser.decodeFromByteArray(BackupManga.serializer(), data))
        if (merge.members.isNotEmpty()) merges += merge
    }

    /** The merged entries read, their tracks on the file's Kitsu [scale]. */
    fun merges(scale: KitsuBackupScoreScale) = ForeignMerges(merges.map { it.rescaleKitsu(scale) }, rows)
}

/** Every merged entry one backup holds, keyed so the restore can find a member as it decodes it. */
class ForeignMerges(merges: List<ForeignMerge>, inFile: Set<Pair<Long, String>>) {

    // Komikku lists a merged entry twice, once as a favourite and once as a merge.
    private val distinct = merges.distinctBy { it.members }

    // Each merge's members the file holds that no earlier merge took, as the group restore claims a group's
    // members only once it has two (RestoreMergeGroups). Its first is the group's lead, so the one that takes
    // the merged entry's own data, even when the fork's info entry is absent or already in another group.
    private val placed = HashSet<Pair<Long, String>>().let { claimed ->
        distinct.map { merge ->
            merge to merge.members.filter { it in inFile && it !in claimed }.also { if (it.size >= 2) claimed += it }
        }
    }

    // A member of two merges joins the library the way the first one was.
    private val byMember = buildMap {
        distinct.forEach { merge -> merge.members.forEach { putIfAbsent(it, merge) } }
    }

    private val byPrimary = buildMap {
        placed.forEach { (merge, members) -> members.firstOrNull()?.let { putIfAbsent(it, merge) } }
    }

    /** The groups to restore, in each merge's member order. */
    val groups: List<List<BackupMangaSourceRef>> =
        placed.map { (_, members) -> members.map { (source, url) -> BackupMangaSourceRef(url, source) } }

    fun applyTo(manga: BackupManga): BackupManga = manga.also {
        val key = it.source to it.url
        val primaryOf = byPrimary[key]
        byMember[key]?.takeIf { merge -> merge !== primaryOf }?.applyTo(it, isPrimary = false)
        primaryOf?.applyTo(it, isPrimary = true)
    }
}

/** A merged entry's own row, left out of a restore: no source in Reikai can ever load it. */
val BackupManga.isForeignMergedRow: Boolean get() = source == FOREIGN_MERGED_SOURCE_ID
