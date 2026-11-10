package mihon.domain.source.interactor

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.entry.signedKey
import reikai.domain.local.LocalSeriesLocks
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover

class UpdateMangaFromRemoteTest {

    private val updateMangaFromRemote = UpdateMangaFromRemote(
        sourceManager = mockk(relaxed = true),
        chapterRepository = mockk(relaxed = true),
        mangaRepository = mockk(relaxed = true),
        syncChaptersWithSource = mockk(relaxed = true),
        coverCache = mockk(relaxed = true),
        libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
        downloadManager = mockk(relaxed = true),
        seriesLocks = LocalSeriesLocks().apply { open() },
    )

    @Test
    fun `an extension built against a missing app method fails as an Exception callers already handle`() = runTest {
        val source = mockk<Source> {
            coEvery { getMangaUpdate(any(), any(), any(), any()) } throws NoSuchMethodError("okhttp3.Foo.bar")
        }

        val result = updateMangaFromRemote(source, Manga.create(), fetchDetails = true, fetchChapters = true)

        result.exceptionOrNull().shouldBeInstanceOf<Exception>()
    }
}
