package mihon.domain.source.interactor

import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class UpdateMangaFromRemoteTest {

    private val updateMangaFromRemote = UpdateMangaFromRemote(
        sourceManager = mockk(relaxed = true),
        chapterRepository = mockk(relaxed = true),
        mangaRepository = mockk(relaxed = true),
        syncChaptersWithSource = mockk(relaxed = true),
        coverCache = mockk(relaxed = true),
        libraryPreferences = mockk(relaxed = true),
        downloadManager = mockk(relaxed = true),
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
