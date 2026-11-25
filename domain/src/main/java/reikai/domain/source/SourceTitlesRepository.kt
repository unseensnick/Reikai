package reikai.domain.source

/**
 * The stored titles of the other entries on the sources that share one download folder. Both types name an
 * entry's folder by its title within its source's folder, which two sources of one name and language share, so
 * these tell a folder an entry shares from one that is its own.
 */
interface SourceTitlesRepository {

    suspend fun otherMangaTitles(sourceIds: Collection<Long>, mangaId: Long): List<String>

    suspend fun otherNovelTitles(sources: Collection<String>, novelId: Long): List<String>
}
