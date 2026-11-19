package reikai.domain.entry

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.customInfo
import eu.kanade.tachiyomi.data.backup.models.toBackupCustomInfo
import eu.kanade.tachiyomi.ui.manga.seedManga
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.novel.CustomNovelInfoRepositoryImpl
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.SetCustomNovelInfo
import reikai.domain.novel.model.CustomNovelInfo
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.manga.CustomMangaInfoRepositoryImpl
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo

/** Alternative titles through every custom info writer, on a real database, for both types. */
class CustomInfoAltTitlesTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var mangaRepo: CustomMangaInfoRepositoryImpl
    private lateinit var novelRepo: CustomNovelInfoRepositoryImpl
    private val setManga by lazy { SetCustomMangaInfo(mangaRepo) }
    private val setNovel by lazy { SetCustomNovelInfo(novelRepo) }
    private val getManga by lazy { GetCustomMangaInfo(mangaRepo) }
    private val getNovel by lazy { GetCustomNovelInfo(novelRepo) }

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            database = DatabaseBindings.providesDatabase(driver)
            mangaRepo = CustomMangaInfoRepositoryImpl(database)
            novelRepo = CustomNovelInfoRepositoryImpl(database)
            driver.seedManga(ID, chapterId = 10L)
            driver.execute(
                null,
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES ($ID, 'src', 'n', 'novel', 0, 0, 0, 1000)",
                0,
            ).await()
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private suspend fun manga() = getManga.subscribe(ID).first()
    private suspend fun novel() = getNovel.subscribe(ID).first()

    @Test
    fun `a manga's alternative titles round-trip, commas included`() = runTest {
        setManga.set(CustomMangaInfo(ID, altTitles = ALTS))

        manga()?.altTitles shouldBe ALTS
    }

    @Test
    fun `a novel's alternative titles round-trip, commas included`() = runTest {
        setNovel.set(CustomNovelInfo(ID, altTitles = ALTS))

        novel()?.altTitles shouldBe ALTS
    }

    @Test
    fun `a row holding only alternative titles is kept, not cleared as empty`() = runTest {
        setManga.set(CustomMangaInfo(ID, altTitles = ALTS))

        (manga() != null) shouldBe true
    }

    @Test
    fun `renaming an entry keeps its alternative titles, both types`() = runTest {
        setManga.set(CustomMangaInfo(ID, title = "T", altTitles = ALTS))
        setNovel.set(CustomNovelInfo(ID, title = "T", altTitles = ALTS))
        val rename = SetEntryTitle(getManga, setManga, getNovel, setNovel)

        rename.await(EntryId.Manga(ID), "T2")
        rename.await(EntryId.Novel(ID), "T2")

        listOf(manga()?.altTitles, novel()?.altTitles) shouldBe listOf(ALTS, ALTS)
    }

    @Test
    fun `reset clears the alternative titles with the rest, both types`() = runTest {
        setManga.set(CustomMangaInfo(ID, title = "T", altTitles = ALTS))
        setNovel.set(CustomNovelInfo(ID, title = "T", altTitles = ALTS))
        val reset = ResetEntryInfo(setManga, setNovel, mockk(relaxed = true))

        reset.await(EntryId.Manga(ID))
        reset.await(EntryId.Novel(ID))

        listOf(manga(), novel()) shouldBe listOf(null, null)
    }

    /** An older backup's entry: custom author only, no field 806. */
    @Serializable
    class OldManga(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(802) val customAuthor: String? = null,
    )

    @Serializable
    class OldNovel(
        @ProtoNumber(1) val source: String,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(802) val customAuthor: String? = null,
    )

    // The restore's own steps: MangaRestorer reads customInfo, RestoreRepositoryImpl writes it through restore.
    private suspend fun restoreManga(bytes: ByteArray) {
        ProtoBuf.decodeFromByteArray(BackupManga.serializer(), bytes).customInfo
            ?.let { setManga.restore(it.toCustomMangaInfo(ID)) }
    }

    private suspend fun restoreNovel(bytes: ByteArray) {
        ProtoBuf.decodeFromByteArray(BackupNovel.serializer(), bytes).customInfo
            ?.let { setNovel.restore(it.toCustomNovelInfo(ID)) }
    }

    @Test
    fun `an older backup replaces the device row but keeps its alternative titles, both types`() = runTest {
        setManga.set(CustomMangaInfo(ID, title = "Device title", altTitles = ALTS))
        setNovel.set(CustomNovelInfo(ID, title = "Device title", altTitles = ALTS))

        restoreManga(ProtoBuf.encodeToByteArray(OldManga.serializer(), OldManga(1L, "/m", customAuthor = "A")))
        restoreNovel(ProtoBuf.encodeToByteArray(OldNovel.serializer(), OldNovel("src", "/n", customAuthor = "A")))

        listOf(manga()?.title, manga()?.altTitles, novel()?.title, novel()?.altTitles) shouldBe
            listOf(null, ALTS, null, ALTS)
    }

    @Test
    fun `an older backup with no custom info leaves the device's row alone`() = runTest {
        setManga.set(CustomMangaInfo(ID, title = "Device title", altTitles = ALTS))

        restoreManga(ProtoBuf.encodeToByteArray(OldManga.serializer(), OldManga(1L, "/m")))

        manga()?.altTitles shouldBe ALTS
    }

    @Test
    fun `field 806 round-trips through both backup models`() {
        val manga = BackupManga(source = 1L, url = "/m").apply {
            customInfo =
                CustomMangaInfo(ID, altTitles = ALTS).toBackupCustomInfo()
        }
        val novel = BackupNovel(source = "s", url = "/n").apply {
            customInfo =
                CustomNovelInfo(ID, altTitles = ALTS).toBackupCustomInfo()
        }

        val m = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), manga),
        )
        val n = ProtoBuf.decodeFromByteArray(
            BackupNovel.serializer(),
            ProtoBuf.encodeToByteArray(BackupNovel.serializer(), novel),
        )

        listOf(m.customInfo?.altTitles, n.customInfo?.altTitles) shouldBe listOf(ALTS, ALTS)
    }

    private companion object {
        const val ID = 7L
        val ALTS = listOf("Alt A", "Alt, B")
    }
}
