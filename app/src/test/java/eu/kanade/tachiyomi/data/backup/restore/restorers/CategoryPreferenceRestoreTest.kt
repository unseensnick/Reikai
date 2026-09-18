package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The category-id settings name categories by the id they had on the device that made the backup, so a
 * restore maps each through its category's name to the id it has here. Yōkai writes no category id, so
 * every one of its categories decodes as 0, and keying by that id sent the settings to whichever
 * category came last. 0 is the Default category in Yōkai, Mihon and Reikai alike, so it maps to itself.
 */
class CategoryPreferenceRestoreTest {

    private val store = EmittingPreferenceStore()
    private val libraryPreferences = LibraryPreferences(store)

    // The device after its categories were restored by name.
    private val deviceCategories = listOf(
        Category(id = 0, name = "Default", order = 0, flags = 0),
        Category(id = 100, name = "Reading", order = 1, flags = 0),
        Category(id = 200, name = "Completed", order = 2, flags = 0),
    )

    private val restorer = PreferenceRestorer(
        context = mockk(),
        getCategories = mockk<GetCategories> { coEvery { await() } returns deviceCategories },
        preferenceStore = store,
        categoryIdPreferences = CategoryIdPreferences(
            libraryPreferences,
            DownloadPreferences(store),
            NovelPreferences(store),
            ReikaiLibraryPreferences(store),
            ReikaiSourcePreferences(store),
        ),
        novelPreferences = NovelPreferences(store),
        extensionSourcePreferences = SourcePreferences(store),
    )

    @BeforeEach
    fun stubTheJobs() {
        mockkObject(LibraryUpdateJob)
        mockkObject(BackupCreateJob)
        every { LibraryUpdateJob.setupTask(any(), any()) } returns Unit
        every { BackupCreateJob.setupTask(any(), any()) } returns Unit
    }

    @AfterEach
    fun releaseTheJobs() {
        unmockkObject(LibraryUpdateJob)
        unmockkObject(BackupCreateJob)
    }

    /** Yōkai's own categories as its backup encodes them: name, order and flags, no id. */
    private fun yokaiCategories(): List<BackupCategory> {
        val bytes = ProtoBuf.encodeToByteArray(
            YokaiBackup.serializer(),
            YokaiBackup(listOf(YokaiCategory("Reading", 1), YokaiCategory("Completed", 2))),
        )
        return ProtoBuf.decodeFromByteArray(ReikaiBackup.serializer(), bytes).categories
    }

    private val reikaiCategories = listOf(
        BackupCategory(name = "Reading", order = 1, id = 11),
        BackupCategory(name = "Completed", order = 2, id = 12),
    )

    private suspend fun restore(categories: List<BackupCategory>, vararg preferences: BackupPreference) =
        restorer.restoreApp(preferences.toList(), categories)

    @Test
    fun `a Yokai backup's Default category stays the Default category`() = runTest {
        restore(yokaiCategories(), BackupPreference(DEFAULT_CATEGORY, IntPreferenceValue(0)))

        libraryPreferences.defaultCategory.get() shouldBe 0
    }

    @Test
    fun `a Yokai backup's default pointing at its own category is dropped rather than guessed`() = runTest {
        restore(yokaiCategories(), BackupPreference(DEFAULT_CATEGORY, IntPreferenceValue(3)))

        libraryPreferences.defaultCategory.isSet() shouldBe false
    }

    @Test
    fun `a Yokai backup's update categories keep only the Default category`() = runTest {
        restore(
            yokaiCategories(),
            BackupPreference(libraryPreferences.updateCategories.key(), StringSetPreferenceValue(setOf("0", "3"))),
        )

        libraryPreferences.updateCategories.get() shouldBe setOf("0")
    }

    @Test
    fun `a Reikai backup's default category maps to the same category here`() = runTest {
        restore(reikaiCategories, BackupPreference(DEFAULT_CATEGORY, IntPreferenceValue(12)))

        libraryPreferences.defaultCategory.get() shouldBe 200
    }

    @Test
    fun `a Reikai backup's Default category stays the Default category`() = runTest {
        restore(reikaiCategories, BackupPreference(DEFAULT_CATEGORY, IntPreferenceValue(0)))

        libraryPreferences.defaultCategory.get() shouldBe 0
    }

    /** The Backup field 2 of each app, alone. */
    @Serializable
    class YokaiBackup(@ProtoNumber(2) val categories: List<YokaiCategory>)

    @Serializable
    class ReikaiBackup(@ProtoNumber(2) val categories: List<BackupCategory>)

    @Serializable
    class YokaiCategory(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val order: Int = 0,
        @ProtoNumber(100) val flags: Int = 0,
    )

    private companion object {
        const val DEFAULT_CATEGORY = LibraryPreferences.DEFAULT_CATEGORY_PREF_KEY
    }
}
