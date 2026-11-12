package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.backup.FOREIGN_MERGED_SOURCE_ID
import reikai.data.backup.ForeignMergeReader
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.domain.backup.KitsuBackupScoreScale
import reikai.domain.library.ContentType

/**
 * A merged series from a TachiyomiSY or Komikku backup restores as one Reikai group, read from bytes in
 * each fork's own layout and restored over real SQL the way BackupRestorer wires it. The two forks differ
 * in where they mark the info entry (Komikku on the member, TachiyomiSY in its merge settings) and in
 * Komikku's extra merge url; both list the merged entry itself among the members.
 */
class ForeignMergeRestoreTest {

    enum class Layout { TACHIYOMI_SY, KOMIKKU }

    private val proto = ProtoBuf

    private fun mergedRow(
        layout: Layout,
        favorite: Boolean = true,
        infoMarked: Boolean = true,
        tracking: List<BackupTracking> = listOf(BackupTracking(syncId = 2, libraryId = 0)),
        info: Pair<String, Long> = INFO_URL to INFO_SOURCE,
        other: Pair<String, Long> = OTHER_URL to OTHER_SOURCE,
    ): ByteArray {
        val self = Triple(MERGED_URL, FOREIGN_MERGED_SOURCE_ID, -1)
        // Listed info, self, other, with the info entry ranked last: only its mark puts it first, and
        // without one only the priority sort and the self filter put the other member first.
        val members = listOf(Triple(info.first, info.second, 1), self, Triple(other.first, other.second, 0))
        return when (layout) {
            Layout.TACHIYOMI_SY -> proto.encodeToByteArray(
                SyManga.serializer(),
                SyManga(
                    source = FOREIGN_MERGED_SOURCE_ID, url = MERGED_URL, title = TITLE, dateAdded = DATE_ADDED,
                    categories = listOf(1L), tracking = tracking,
                    favorite = favorite, notes = NOTES, customTitle = CUSTOM_TITLE,
                    mergedEntries = members.map { (url, source, priority) -> SyMergedEntry(priority, url, source) },
                    mergeSettings = SyMergeSettings(info.first, info.second).takeIf { infoMarked },
                ),
            )
            Layout.KOMIKKU -> proto.encodeToByteArray(
                KomikkuManga.serializer(),
                KomikkuManga(
                    source = FOREIGN_MERGED_SOURCE_ID, url = MERGED_URL, title = TITLE, dateAdded = DATE_ADDED,
                    categories = listOf(1L), tracking = tracking,
                    favorite = favorite, notes = NOTES, customTitle = CUSTOM_TITLE,
                    mergedMangaReferences = members.map { (url, source, priority) ->
                        KomikkuMergedRef(infoMarked && url == info.first, priority, MERGED_URL, url, source)
                    },
                ),
            )
        }
    }

    private fun memberRow(url: String, source: Long, metadata: ForkFlatMetadata? = null) = proto.encodeToByteArray(
        KomikkuManga.serializer(),
        KomikkuManga(source = source, url = url, title = TITLE, favorite = false, flatMetadata = metadata),
    )

    private fun rows(layout: Layout) = listOf(
        mergedRow(layout),
        memberRow(OTHER_URL, OTHER_SOURCE),
        memberRow(
            INFO_URL,
            INFO_SOURCE,
            ForkFlatMetadata(ForkSearchMetadata("uploader", "{}", 1), listOf(ForkTag("tags", "Action"))),
        ),
        mergedRow(layout),
    )

    private fun merges(rows: List<ByteArray>) = ForeignMergeReader()
        .apply { rows.forEach { read(proto, it) } }
        .merges(KitsuBackupScoreScale.of(nativeScaleMarked = true, tracks = emptyList()))

    // Through the real restore, as the fork wrote the file: its rows are the backup's field 1, and nothing else.
    private suspend fun <T> restored(rows: List<ByteArray>, read: suspend MangaRestoreHarness.() -> T): T =
        MangaRestoreHarness.create().use { harness ->
            restoreFile(
                proto.encodeToByteArray(ForkBackup.serializer(), ForkBackup(rows)),
                RestoreOptions(appSettings = false, sourceSettings = false),
                mangaRestorer = harness.restorer(mergeGroups = MergeGroupRepositoryImpl(harness.database)),
            )
            harness.read()
        }

    private suspend fun MangaRestoreHarness.idOf(url: String, source: Long) =
        mangas.getMangaByUrlAndSourceId(url, source)?.id

    private suspend fun MangaRestoreHarness.groupOf(id: Long?): Pair<List<Long>, Boolean>? {
        val repository = MergeGroupRepositoryImpl(database)
        val groupId = repository.getGroupId(ContentType.MANGA, id ?: return null) ?: return null
        return repository.getMembers(ContentType.MANGA, groupId) to repository.getGroup(groupId)!!.overrideSourceRanking
    }

    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `the members form one group, info entry first, as the user's own order`(layout: Layout) = runTest {
        restored(rows(layout)) {
            groupOf(idOf(INFO_URL, INFO_SOURCE)) to listOf(idOf(INFO_URL, INFO_SOURCE), idOf(OTHER_URL, OTHER_SOURCE))
        }.let { (group, expected) -> group shouldBe (expected to true) }
    }

    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `every member is in the library`(layout: Layout) = runTest {
        restored(rows(layout)) {
            listOf(INFO_URL to INFO_SOURCE, OTHER_URL to OTHER_SOURCE)
                .map { (url, source) -> mangas.getMangaByUrlAndSourceId(url, source)!!.favorite }
        } shouldBe listOf(true, true)
    }

    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `the merged entry's own row is not restored`(layout: Layout) = runTest {
        restored(rows(layout)) { idOf(MERGED_URL, FOREIGN_MERGED_SOURCE_ID) } shouldBe null
    }

    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `the Primary member takes the merged entry's tracks`(layout: Layout) = runTest {
        restored(rows(layout)) {
            tracks.getTracksByMangaId(idOf(INFO_URL, INFO_SOURCE)!!).map { it.trackerId }
        } shouldBe listOf(2L)
    }

    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `with the info entry missing from the file, the next member takes the tracks`(layout: Layout) = runTest {
        val rows = listOf(mergedRow(layout), memberRow(OTHER_URL, OTHER_SOURCE))

        restored(rows) { tracks.getTracksByMangaId(idOf(OTHER_URL, OTHER_SOURCE)!!).map { it.trackerId } } shouldBe
            listOf(2L)
    }

    // Merge A is [other, info] and B is [info, third]: info goes to A, the first to claim it, so B's own
    // data has only third left to land on.
    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `a merge whose Primary an earlier merge took gives its tracks to its next member`(layout: Layout) = runTest {
        val rows = listOf(
            mergedRow(
                layout,
                tracking = emptyList(),
                info = OTHER_URL to OTHER_SOURCE,
                other = INFO_URL to INFO_SOURCE,
            ),
            mergedRow(layout, other = THIRD_URL to THIRD_SOURCE),
            memberRow(OTHER_URL, OTHER_SOURCE),
            memberRow(INFO_URL, INFO_SOURCE),
            memberRow(THIRD_URL, THIRD_SOURCE),
        )

        restored(rows) { tracks.getTracksByMangaId(idOf(THIRD_URL, THIRD_SOURCE)!!).map { it.trackerId } } shouldBe
            listOf(2L)
    }

    // A fork's file carries none of the native-scale markers, so a Kitsu score of 10 or less is doubled.
    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `a Kitsu track the Primary takes is rescaled like every other`(layout: Layout) = runTest {
        val kitsu = listOf(BackupTracking(syncId = 3, libraryId = 0, score = 4f))
        val rows =
            listOf(
                mergedRow(layout, tracking = kitsu),
                memberRow(OTHER_URL, OTHER_SOURCE),
                memberRow(INFO_URL, INFO_SOURCE),
            )

        restored(rows) { tracks.getTracksByMangaId(idOf(INFO_URL, INFO_SOURCE)!!).map { it.score } } shouldBe
            listOf(8.0)
    }

    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `with no info entry marked, the member first by priority takes the tracks`(layout: Layout) = runTest {
        val rows =
            listOf(
                mergedRow(layout, infoMarked = false),
                memberRow(OTHER_URL, OTHER_SOURCE),
                memberRow(INFO_URL, INFO_SOURCE),
            )
        restored(rows) { tracks.getTracksByMangaId(idOf(OTHER_URL, OTHER_SOURCE)!!).map { it.trackerId } } shouldBe
            listOf(2L)
    }

    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `the Primary member takes the merged entry's edited title`(layout: Layout) = runTest {
        restored(rows(layout)) {
            customInfo.getByMangaIdAsFlow(idOf(INFO_URL, INFO_SOURCE)!!).first()?.title
        } shouldBe CUSTOM_TITLE
    }

    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `the Primary member takes the merged entry's notes`(layout: Layout) = runTest {
        restored(rows(layout)) { mangas.getMangaByUrlAndSourceId(INFO_URL, INFO_SOURCE)!!.notes } shouldBe NOTES
    }

    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `a member keeps its gallery metadata`(layout: Layout) = runTest {
        restored(rows(layout)) {
            metadata.getTagsById(idOf(INFO_URL, INFO_SOURCE)!!).map { it.namespace to it.name }
        } shouldBe listOf("tags" to "Action")
    }

    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `each member takes the merged entry's categories`(layout: Layout) = runTest {
        val merges = merges(rows(layout))
        val member = merges.applyTo(
            proto.decodeFromByteArray(BackupManga.serializer(), memberRow(OTHER_URL, OTHER_SOURCE)),
        )
        member.categories shouldBe listOf(1L)
    }

    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `a merged entry listed twice gives one group`(layout: Layout) = runTest {
        merges(rows(layout)).groups.size shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(Layout::class)
    fun `a merged entry out of the library leaves its members out too`(layout: Layout) = runTest {
        val merges = merges(listOf(mergedRow(layout, favorite = false)))
        merges.applyTo(
            proto.decodeFromByteArray(BackupManga.serializer(), memberRow(OTHER_URL, OTHER_SOURCE)),
        ).favorite shouldBe
            false
    }

    private companion object {
        const val TITLE = "One-Punch Man"
        const val CUSTOM_TITLE = "OPM"
        const val NOTES = "notes"
        const val DATE_ADDED = 1_700_000_000_000L
        const val INFO_URL = "/manga/info"
        const val INFO_SOURCE = 11L
        const val OTHER_URL = "/manga/other"
        const val OTHER_SOURCE = 22L
        const val THIRD_URL = "/manga/third"
        const val THIRD_SOURCE = 33L
        const val MERGED_URL = INFO_URL
    }
}

// The forks' own BackupManga shapes, on their field numbers, so the bytes are what they write.

@Serializable
private class ForkBackup(@ProtoNumber(1) val manga: List<ByteArray>)

@Serializable
private class SyMergedEntry(
    @ProtoNumber(4) val chapterPriority: Int,
    @ProtoNumber(7) val mangaUrl: String,
    @ProtoNumber(8) val mangaSourceId: Long,
    @ProtoNumber(1) val isInfoManga: Boolean = false,
    @ProtoNumber(2) val fetchChapterUpdates: Boolean = true,
    @ProtoNumber(5) val downloadChapters: Boolean = true,
)

@Serializable
private class SyMergeSettings(
    @ProtoNumber(2) val infoMangaUrl: String,
    @ProtoNumber(3) val infoMangaSourceId: Long,
    @ProtoNumber(1) val chapterSortMode: Int = 0,
)

@Serializable
private class SyManga(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
    @ProtoNumber(3) val title: String,
    @ProtoNumber(13) val dateAdded: Long,
    @ProtoNumber(17) val categories: List<Long>,
    @ProtoNumber(18) val tracking: List<BackupTracking>,
    @ProtoNumber(100) val favorite: Boolean,
    @ProtoNumber(110) val notes: String,
    @ProtoNumber(800) val customTitle: String,
    @ProtoNumber(600) val mergedEntries: List<SyMergedEntry>,
    @ProtoNumber(604) val mergeSettings: SyMergeSettings?,
)

@Serializable
private class KomikkuMergedRef(
    @ProtoNumber(1) val isInfoManga: Boolean,
    @ProtoNumber(4) val chapterPriority: Int,
    @ProtoNumber(6) val mergeUrl: String,
    @ProtoNumber(7) val mangaUrl: String,
    @ProtoNumber(8) val mangaSourceId: Long,
    @ProtoNumber(2) val getChapterUpdates: Boolean = true,
    @ProtoNumber(3) val chapterSortMode: Int = 0,
    @ProtoNumber(5) val downloadChapters: Boolean = true,
)

@Serializable
private class ForkSearchMetadata(
    @ProtoNumber(1) val uploader: String?,
    @ProtoNumber(2) val extra: String,
    @ProtoNumber(4) val extraVersion: Int,
)

@Serializable
private class ForkTag(
    @ProtoNumber(1) val namespace: String?,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val type: Int = 0,
)

@Serializable
private class ForkFlatMetadata(
    @ProtoNumber(1) val searchMetadata: ForkSearchMetadata,
    @ProtoNumber(2) val searchTags: List<ForkTag>,
)

@Serializable
private class KomikkuManga(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
    @ProtoNumber(3) val title: String,
    @ProtoNumber(100) val favorite: Boolean,
    @ProtoNumber(13) val dateAdded: Long = 0,
    @ProtoNumber(17) val categories: List<Long> = emptyList(),
    @ProtoNumber(18) val tracking: List<BackupTracking> = emptyList(),
    @ProtoNumber(110) val notes: String = "",
    @ProtoNumber(800) val customTitle: String? = null,
    @ProtoNumber(600) val mergedMangaReferences: List<KomikkuMergedRef> = emptyList(),
    @ProtoNumber(601) val flatMetadata: ForkFlatMetadata? = null,
)
