package reikai.domain.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.db.SqlDelightTransactions
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * A 0.3.x backup stored only its manual merges; its same-title groups were derived live and never
 * written. Restore rebuilds them through the kernel the upgrade migration uses, for both content types
 * in one place. Runs each type against the real schema.
 */
class RestorePrefEraGroupsConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var repository: MergeGroupRepositoryImpl
    private lateinit var restore: RestoreMergeGroups

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            val database = DatabaseBindings.providesDatabase(driver)
            repository = MergeGroupRepositoryImpl(database)
            restore = RestoreMergeGroups(repository, SqlDelightTransactions(database))
        }
    }

    @AfterEach
    fun tearDown() = driver.close()

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `same-title favourites of a pref-era backup come back as one group`(type: ContentType) = runTest {
        insert(type, 1, 2)

        restorePrefEra(type, favorites = listOf(fav("a", "Title"), fav("b", "Title")))

        groupOf(type, 1) shouldContainExactly listOf(1L, 2L)
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a same-title pair the backup unmerged stays split`(type: ContentType) = runTest {
        insert(type, 1, 2)

        restorePrefEra(
            type,
            favorites = listOf(fav("a", "Title"), fav("b", "Title")),
            unmerges = listOf(listOf("a", "b")),
        )

        groupOf(type, 1).shouldBeEmpty()
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a pref-era backup with same-title merging switched off keeps the pair split`(type: ContentType) = runTest {
        insert(type, 1, 2)

        restorePrefEra(
            type,
            favorites = listOf(fav("a", "Title"), fav("b", "Title")),
            switches = mapOf(SAME_TITLE_KEYS.getValue(type) to false),
        )

        groupOf(type, 1).shouldBeEmpty()
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a backup that stores its groups whole never groups by title`(type: ContentType) = runTest {
        insert(type, 1, 2)

        restore.fromBackup(type, emptyList(), prefEra = null, resolve = REFS::get)

        groupOf(type, 1).shouldBeEmpty()
    }

    @ParameterizedTest
    @EnumSource(value = ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a pref-era backup's manual merge still restores`(type: ContentType) = runTest {
        insert(type, 1, 2)

        restorePrefEra(
            type,
            groups = listOf(listOf("a", "b")),
            favorites = listOf(fav("a", "One"), fav("b", "Other")),
        )

        groupOf(type, 1) shouldContainExactly listOf(1L, 2L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authorGuard")
    fun `same-title favourites by different authors follow the type's author guard`(
        type: ContentType,
        expected: List<Long>,
    ) = runTest {
        insert(type, 1, 2)

        restorePrefEra(type, favorites = listOf(fav("a", "Title", "Ann"), fav("b", "Title", "Bo")))

        groupOf(type, 1) shouldContainExactly expected
    }

    private suspend fun restorePrefEra(
        type: ContentType,
        favorites: List<PrefEraGrouping.Favorite<String>>,
        groups: List<List<String>> = emptyList(),
        unmerges: List<List<String>> = emptyList(),
        switches: Map<String, Boolean> = emptyMap(),
    ) {
        // Absent switches read as the 0.3 defaults, the same fallback a backup without app settings gets.
        val prefs = ReikaiLibraryPreferences(InMemoryPreferenceStore())
        val titleSwitches = MergeGroupReconstruction.titleSwitches(type, prefs) { pref ->
            switches[pref.key()] ?: pref.defaultValue()
        }
        restore.fromBackup(type, groups, PrefEraGrouping(favorites, unmerges, titleSwitches), REFS::get)
    }

    // One shared author by default, so the novels author guard (on by default) is not what splits a pair.
    private fun fav(ref: String, title: String, author: String = "Ann") =
        PrefEraGrouping.Favorite(ref, title, author)

    private suspend fun insert(type: ContentType, vararg ids: Long) = ids.forEach { id ->
        val sql = when (type) {
            ContentType.MANGA ->
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                    "state_initialized, user_reader_flags, user_chapter_flags, " +
                    "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                    "state_chapter_fetch_interval, user_notes, remote_memo) VALUES ($id, 1, " +
                    "'m-url-$id', 'title', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')"
            else ->
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                    "favorite_at) VALUES ($id, 'src', 'n-url-$id', 'title', 0, 0, 0, 0)"
        }
        driver.execute(null, sql, 0).await()
    }

    private suspend fun groupOf(type: ContentType, id: Long): List<Long> {
        val groupId = repository.getGroupId(type, id) ?: return emptyList()
        return repository.getMembers(type, groupId)
    }

    companion object {
        private val REFS = mapOf("a" to 1L, "b" to 2L)

        private val SAME_TITLE_KEYS = mapOf(
            ContentType.MANGA to "auto_merge_same_title",
            ContentType.NOVELS to "novel_auto_merge_same_title",
        )

        // Manga never had the author guard; novels had it on by default.
        @JvmStatic
        fun authorGuard() = listOf(
            Arguments.of(ContentType.MANGA, listOf(1L, 2L)),
            Arguments.of(ContentType.NOVELS, emptyList<Long>()),
        )
    }
}
