package reikai.domain.source

/**
 * The stored titles of the other entries on one source. Both types name an entry's download folder by its title
 * within its source, so these tell a folder an entry shares from one that is its own.
 */
interface SourceTitlesRepository {

    suspend fun otherMangaTitles(sourceId: Long, mangaId: Long): List<String>

    suspend fun otherNovelTitles(source: String, novelId: Long): List<String>
}
