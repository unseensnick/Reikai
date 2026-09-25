package reikai.novel.download

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.IOException

/**
 * Only a finished novel download leaves the saved queue, as Mihon's Downloader removes a download
 * from its store only once it is DOWNLOADED. A chapter that failed comes back after a restart and
 * waits for Resume, like a failed manga chapter.
 */
class NovelDownloadManagerFailureTest {

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Novel")
    private val chapter = NovelChapter(
        id = 10L,
        novelId = 1L,
        url = "u10",
        name = "Ch 10",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = 10.0,
        sourceOrder = 10L,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )

    private val prefs = FakeSharedPreferences()
    private val context = mockk<Context>(relaxed = true) {
        every { getSharedPreferences(any(), any()) } returns prefs
        every { getSystemService(NotificationManager::class.java) } returns mockk<NotificationManager>(relaxed = true)
    }
    private val chapterRepo = mockk<NovelChapterRepository> { coEvery { getById(10L) } returns chapter }
    private val failingSource = mockk<NovelSource> {
        every { minimumRequestDelayMs } returns 0L
        coEvery { parseChapter(any()) } throws IOException("source down")
    }

    // Stubbed outside a mockk block, where a bare get binds to MockK's own.
    private val sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get("src") } returns failingSource }

    private val manager = NovelDownloadManager(
        context = context,
        provider = mockk(),
        cache = mockk { every { isChapterDownloaded(novel, chapter) } returns false },
        chapterRepo = chapterRepo,
        novelRepo = mockk<NovelRepository> { coEvery { getById(1L) } returns novel },
        sourceManager = sourceManager,
        installer = mockk { coEvery { ensureLoaded() } just runs },
        downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
        sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
        novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
        saver = mockk(),
        securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
        adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
    )

    @BeforeEach
    fun setUp() {
        mockkObject(NovelDownloadJob.Companion)
        every { NovelDownloadJob.start(any()) } just runs
        mockkStatic(Context::activeNetworkState)
        every { any<Context>().activeNetworkState() } returns NetworkState(true, true, true)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(NovelDownloadJob.Companion)
        unmockkStatic(Context::activeNetworkState)
    }

    @Test
    fun `a chapter that failed to download is still in the saved queue after a restart`() = runTest {
        manager.downloadChapters(listOf(chapter))

        manager.runQueue(onProgress = {}, onError = { _, _, _, _ -> })

        NovelDownloadStore(context, chapterRepo).restore().map { it.chapterId } shouldBe listOf(10L)
    }
}

/** An in-memory SharedPreferences, enough for the download store's string entries. */
private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val puts = mutableMapOf<String, Any?>()
        private val removes = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { puts[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { puts[key] = values }
        override fun putInt(key: String, value: Int) = apply { puts[key] = value }
        override fun putLong(key: String, value: Long) = apply { puts[key] = value }
        override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
        override fun remove(key: String) = apply { removes += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removes.forEach { values.remove(it) }
            values.putAll(puts)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
