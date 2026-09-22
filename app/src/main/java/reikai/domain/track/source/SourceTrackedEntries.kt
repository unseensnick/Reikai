package reikai.domain.track.source

import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.SourceTracker
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CancellationException
import reikai.domain.category.GetNovelCategories
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.track.site.OwnedSites
import reikai.novel.source.NovelSourceManager
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/** Reads a manga or a novel for its source's tracker, each from its own tables. */
@Inject
class SourceTrackedEntries(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val getCategories: GetCategories,
    private val sourceManager: SourceManager,
    private val novelRepository: NovelRepository,
    private val novelChapterRepository: NovelChapterRepository,
    private val getNovelCategories: GetNovelCategories,
    private val novelSourceManager: NovelSourceManager,
) : TrackedEntryLoader {

    override suspend fun load(entry: EntryId): TrackedEntry? = when (entry) {
        is EntryId.Manga -> loadManga(entry.rawId)
        is EntryId.Novel -> loadNovel(entry.rawId)
    }

    private suspend fun loadManga(id: Long): TrackedEntry? {
        // The repository throws for a row that is gone rather than answering null.
        val manga = try {
            mangaRepository.getMangaById(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val source = sourceManager.get(manga.source) ?: return null
        val tracker = source as? SourceTracker ?: return null
        return TrackedEntry(
            tracker = tracker,
            trackerName = source.name,
            manga = manga.toSManga(),
            favorite = manga.favorite,
            chapters = chapterRepository.getChapterByMangaId(id).map {
                TrackedChapter(it.id, it.toSChapter(), it.read, it.chapterNumber)
            },
            categories = getCategories.await(id).names(),
        )
    }

    private suspend fun loadNovel(id: Long): TrackedEntry? {
        val novel = novelRepository.getById(id) ?: return null
        // Only an app's source can track, so the plugins are never loaded for this.
        val source = novelSourceManager.getWithoutPlugins(novel.source) ?: return null
        // A site a Reikai tracker owns is tracked by that tracker alone, never by the extension too.
        if (OwnedSites.ownerOf(source) != null) return null
        val tracker = source.tracker ?: return null
        val manga = SManga.create().apply {
            url = novel.url
            title = novel.title
            artist = novel.artist
            author = novel.author
            description = novel.description
            genre = novel.genre.orEmpty().joinToString()
            status = novel.status.toInt()
            thumbnail_url = novel.thumbnailUrl
            initialized = novel.initialized
        }
        return TrackedEntry(
            tracker = tracker,
            trackerName = source.name,
            manga = manga,
            favorite = novel.favorite,
            chapters = novelChapterRepository.getByNovelId(id).map {
                val chapter = SChapter.create().apply {
                    url = it.url
                    name = it.name
                    chapter_number = it.chapterNumber.toFloat()
                    date_upload = it.dateUpload
                }
                TrackedChapter(it.id, chapter, it.read, it.chapterNumber)
            },
            categories = getNovelCategories.awaitByNovelId(id).names(),
        )
    }

    private fun List<Category>.names() = filterNot { it.isSystemCategory }.map { it.name }
}
