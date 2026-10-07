package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.create.BackupProtoWriter
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelSource
import eu.kanade.tachiyomi.data.backup.models.BackupNovelTracking
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.novel.source.NovelSourceManager
import reikai.presentation.recents.EmittingPreferenceStore
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** The warning before a restore names what the backup needs that this install lacks. */
class BackupFileValidatorTest {

    // What this device has installed; the validator reads it, and a restore never writes it.
    private val deviceNovelPreferences = NovelPreferences(EmittingPreferenceStore())

    private fun validator(
        vararg fields: Pair<Int, ByteArray>,
        trackerManager: TrackerManager = mockk(),
        extensions: ExtensionManager = mockk(),
    ): BackupFileValidator {
        val novelSources = mockk<NovelSourceManager>()
        coEvery { novelSources.ensureLoaded() } just runs
        coEvery { novelSources.get(any()) } returns null
        return validator(novelSources, fields.asList(), trackerManager, extensions)
    }

    // Strict mocks: touching a source, tracker or extension manager that is not stubbed fails the test.
    private fun validator(
        novelSources: NovelSourceManager,
        fields: List<Pair<Int, ByteArray>>,
        trackerManager: TrackerManager = mockk(),
        extensions: ExtensionManager = mockk(),
    ) = validatorOver(
        ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { gzip ->
                fields.forEach { (n, data) -> BackupProtoWriter.writeField(gzip, n, data) }
            }
        }.toByteArray(),
        novelSources,
        trackerManager,
        extensions,
    )

    private fun validatorOver(
        file: ByteArray,
        novelSources: NovelSourceManager,
        trackerManager: TrackerManager = mockk(),
        extensions: ExtensionManager = mockk(),
    ) = BackupFileValidator(
        context = mockk<Context> {
            every { contentResolver.openInputStream(any()) } returns file.inputStream()
        },
        sourceManager = mockk(),
        trackerManager = trackerManager,
        novelSourceManager = novelSources,
        parser = ProtoBuf,
        extensionManager = extensions,
        novelPreferences = deviceNovelPreferences,
    )

    @Test
    fun `the check after writing a backup reads it without resolving any source`() = runTest {
        // Resolving novel sources loads every installed plugin and can fetch over the network, on every
        // backup including the automatic ones, for a result the caller never reads.
        validator(mockk<NovelSourceManager>(), listOf(novel, extension, *plugin))
            .checkReadable(mockk<Uri>()) shouldBe Unit
    }

    @Test
    fun `the check after writing a backup rejects a malformed file`() = runTest {
        // A length-delimited field claiming more bytes than the file holds.
        val truncated = byteArrayOf(0x0A, 0x7F, 0x01)

        shouldThrow<IllegalStateException> {
            validatorOver(truncated, mockk()).checkReadable(mockk<Uri>())
        }
    }

    private val novel = 700 to ProtoBuf.encodeToByteArray(
        BackupNovel.serializer(),
        BackupNovel(source = "tachiyomi:123", url = "u"),
    )

    @Test
    fun `a missing novel source is named by the backup's source list`() = runTest {
        val name = 717 to ProtoBuf.encodeToByteArray(
            BackupNovelSource.serializer(),
            BackupNovelSource(name = "Foo", sourceId = "tachiyomi:123"),
        )

        validator(novel, name).validate(mockk<Uri>()).missingSources shouldBe listOf("Foo")
    }

    @Test
    fun `a backup made before novel source names falls back to the id`() = runTest {
        validator(novel).validate(mockk<Uri>()).missingSources shouldBe listOf("tachiyomi:123")
    }

    @Test
    fun `a signed-out tracker is named once, whichever content types track with it`() = runTest {
        val signedOut = { trackerName: String ->
            mockk<BaseTracker> {
                every { name } returns trackerName
                every { isLoggedIn } returns false
            }
        }
        val trackers = mockk<TrackerManager> {
            every { get(2L) } returns signedOut("AniList")
            every { get(5L) } returns signedOut("Kitsu")
            every { get(9L) } returns signedOut("NovelUpdates")
        }
        val manga = 1 to ProtoBuf.encodeToByteArray(
            BackupManga.serializer(),
            BackupManga(source = 1L, url = "m").apply {
                tracking = listOf(BackupTracking(syncId = 2, libraryId = 0), BackupTracking(syncId = 5, libraryId = 0))
            },
        )
        val trackedNovel = 700 to ProtoBuf.encodeToByteArray(
            BackupNovel.serializer(),
            BackupNovel(source = "tachiyomi:123", url = "u").apply {
                tracking = listOf(BackupNovelTracking(trackerId = 2L), BackupNovelTracking(trackerId = 9L))
            },
        )

        validator(manga, trackedNovel, trackerManager = trackers).validate(mockk<Uri>()).missingTrackers shouldBe
            listOf("AniList", "Kitsu", "NovelUpdates")
    }

    private val extension = 710 to ProtoBuf.encodeToByteArray(
        BackupExtension.serializer(),
        BackupExtension(pkgName = "ext.foo", name = "Foo"),
    )

    enum class InstalledAs { Loaded, NotLoaded, NovelLoaded, NovelNotLoaded }

    // Only the list [installedAs] names holds the app; null installs it nowhere.
    private fun extensionManager(installedAs: InstalledAs?): ExtensionManager {
        val loaded = listOf(mockk<Extension.Loaded> { every { pkgName } returns "ext.foo" })
        val notLoaded = listOf(mockk<Extension.NotLoaded> { every { pkgName } returns "ext.foo" })
        return mockk {
            coEvery { getLoadedExtensions() } returns loaded.takeIf { installedAs == InstalledAs.Loaded }.orEmpty()
            coEvery { getNotLoadedExtensions() } returns
                notLoaded.takeIf { installedAs == InstalledAs.NotLoaded }.orEmpty()
            coEvery { getLoadedNovelExtensions() } returns
                loaded.takeIf { installedAs == InstalledAs.NovelLoaded }.orEmpty()
            coEvery { getNotLoadedNovelExtensions() } returns
                notLoaded.takeIf { installedAs == InstalledAs.NovelNotLoaded }.orEmpty()
        }
    }

    @Test
    fun `an extension app the backup had that is not installed is listed to install`() = runTest {
        validator(extension, extensions = extensionManager(installedAs = null))
            .validate(mockk<Uri>()).missingExtensions shouldBe listOf("Foo")
    }

    // An untrusted or failing app is installed all the same, so it is not one to install.
    @ParameterizedTest
    @EnumSource(InstalledAs::class)
    fun `an installed extension app is not listed, manga or novel, loaded or not`(installedAs: InstalledAs) =
        runTest {
            validator(extension, extensions = extensionManager(installedAs))
                .validate(mockk<Uri>()).missingExtensions.shouldBeEmpty()
        }

    private fun pluginPreferences(seen: Map<String, LnSourceIdentity>): Array<Pair<Int, ByteArray>> = listOf(
        BackupPreference(
            deviceNovelPreferences.installedPluginUrls().key(),
            StringSetPreferenceValue(setOf(PLUGIN_URL)),
        ),
        BackupPreference(
            deviceNovelPreferences.installedPluginMetadata().key(),
            StringPreferenceValue(
                Json.encodeToString(
                    MapSerializer(String.serializer(), LnInstalledPluginMetadata.serializer()),
                    mapOf(PLUGIN_URL to LnInstalledPluginMetadata(pluginId = PLUGIN_ID)),
                ),
            ),
        ),
        BackupPreference(
            deviceNovelPreferences.seenNovelSources().key(),
            StringPreferenceValue(
                Json.encodeToString(MapSerializer(String.serializer(), LnSourceIdentity.serializer()), seen),
            ),
        ),
    )
        .map { 104 to ProtoBuf.encodeToByteArray(BackupPreference.serializer(), it) }
        .toTypedArray()

    private val plugin = pluginPreferences(mapOf(PLUGIN_ID to LnSourceIdentity(name = "Novel Foo")))

    @Test
    fun `a plugin the backup had that is not installed is listed by the name it last loaded under`() = runTest {
        validator(*plugin).validate(mockk<Uri>()).missingExtensions shouldBe listOf("Novel Foo")
    }

    @Test
    fun `a plugin the backup never loaded is listed by its script's file name`() = runTest {
        validator(*pluginPreferences(seen = emptyMap())).validate(mockk<Uri>()).missingExtensions shouldBe
            listOf("novelfoo")
    }

    @Test
    fun `a plugin installed from the same address is not listed, record or not`() = runTest {
        deviceNovelPreferences.installedPluginUrls().set(setOf(PLUGIN_URL))

        validator(*plugin).validate(mockk<Uri>()).missingExtensions.shouldBeEmpty()
    }

    @Test
    fun `a plugin installed from another repo is not listed`() = runTest {
        val url = "https://other.example/novelfoo.js"
        deviceNovelPreferences.installedPluginUrls().set(setOf(url))
        deviceNovelPreferences.installedPluginMetadata().set(mapOf(url to LnInstalledPluginMetadata(PLUGIN_ID)))

        validator(*plugin).validate(mockk<Uri>()).missingExtensions.shouldBeEmpty()
    }

    @Test
    fun `extension apps and plugins to install share one list`() = runTest {
        validator(extension, *plugin, extensions = extensionManager(installedAs = null))
            .validate(mockk<Uri>()).missingExtensions shouldBe listOf("Foo", "Novel Foo")
    }

    private companion object {
        const val PLUGIN_URL = "https://repo.example/plugins/novelfoo.js"
        const val PLUGIN_ID = "novel.foo"
    }
}
