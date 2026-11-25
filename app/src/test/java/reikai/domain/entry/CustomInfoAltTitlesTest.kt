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
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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

/**
 * Alternative titles through every custom info writer, on a real database, for both types. A restore's are
 * BackupCustomInfoConformanceTest's, through the real restorers.
 */
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

    // Alternative titles alone, so a row cleared as empty fails this as well as a lost comma.
    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `alternative titles alone round-trip, commas included`(kind: Kind) = runTest {
        kind.write(this@CustomInfoAltTitlesTest, null)

        kind.read(this@CustomInfoAltTitlesTest)?.altTitles shouldBe ALTS
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `renaming an entry keeps its alternative titles`(kind: Kind) = runTest {
        kind.write(this@CustomInfoAltTitlesTest, "T")

        SetEntryTitle(getManga, setManga, getNovel, setNovel).await(kind.entry, "T2")

        kind.read(this@CustomInfoAltTitlesTest)?.altTitles shouldBe ALTS
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `reset clears the alternative titles with the rest`(kind: Kind) = runTest {
        kind.write(this@CustomInfoAltTitlesTest, "T")

        ResetEntryInfo(setManga, setNovel, mockk(relaxed = true)).await(kind.entry)

        kind.read(this@CustomInfoAltTitlesTest) shouldBe null
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `field 806 round-trips through the backup model`(kind: Kind) {
        kind.backupRoundTrip() shouldBe ALTS
    }

    enum class Kind(
        val entry: EntryId,
        val write: suspend CustomInfoAltTitlesTest.(title: String?) -> Unit,
        val read: suspend CustomInfoAltTitlesTest.() -> EntryCustomInfo?,
        val backupRoundTrip: () -> List<String>?,
    ) {
        MANGA(
            EntryId.Manga(ID),
            { setManga.set(CustomMangaInfo(ID, title = it, altTitles = ALTS)) },
            { manga() },
            {
                val backup = BackupManga(source = 1L, url = "/m").apply {
                    customInfo = CustomMangaInfo(ID, altTitles = ALTS).toBackupCustomInfo()
                }
                ProtoBuf.decodeFromByteArray(
                    BackupManga.serializer(),
                    ProtoBuf.encodeToByteArray(BackupManga.serializer(), backup),
                ).customInfo?.altTitles
            },
        ),
        NOVEL(
            EntryId.Novel(ID),
            { setNovel.set(CustomNovelInfo(ID, title = it, altTitles = ALTS)) },
            { novel() },
            {
                val backup = BackupNovel(source = "s", url = "/n").apply {
                    customInfo = CustomNovelInfo(ID, altTitles = ALTS).toBackupCustomInfo()
                }
                ProtoBuf.decodeFromByteArray(
                    BackupNovel.serializer(),
                    ProtoBuf.encodeToByteArray(BackupNovel.serializer(), backup),
                ).customInfo?.altTitles
            },
        ),
    }

    private companion object {
        const val ID = 7L
        val ALTS = listOf("Alt A", "Alt, B")
    }
}
