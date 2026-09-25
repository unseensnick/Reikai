package tachiyomi.domain.release.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

@Inject
class GetApplicationRelease(
    private val service: ReleaseService,
) {
    suspend fun await(arguments: Arguments): Result {
        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.isPreview, // RK
            arguments.commitCount,
            arguments.versionName,
            release.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private fun isNewVersion(
        isPreview: Boolean, // RK: Reikai's preview channel, upstream's isNightly
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        // Removes prefixes like "r" or "v"
        val newVersion = versionTag.replace("[^\\d.]".toRegex(), "")
        return if (isPreview) { // RK: Reikai's preview channel
            // Preview builds: based on releases in the "unseensnick/Reikai-preview" repo
            // tagged as something like "r1234"
            newVersion.toInt() > commitCount
        } else {
            // RK: Reikai's own release repos
            // Release builds: based on releases in the "unseensnick/Reikai" repo
            // tagged as something like "v0.1.2"
            val oldVersion = versionName.replace("[^\\d.]".toRegex(), "")

            val newSemVer = newVersion.split(".").map { it.toInt() }
            val oldSemVer = oldVersion.split(".").map { it.toInt() }

            // RK --> Compare position by position, stopping at the first difference, over the
            //        longer of the two lists. Upstream walks only the installed version's
            //        positions and indexes the tag by them, which throws when the installed
            //        version is longer (why a Yokai-era five-segment build is never offered an
            //        update), ignores a segment the tag adds, and returns true on any later
            //        position being greater, so 0.3.9 reads as newer than 0.4.0.
            for (index in 0 until maxOf(newSemVer.size, oldSemVer.size)) {
                val new = newSemVer.getOrElse(index) { 0 }
                val old = oldSemVer.getOrElse(index) { 0 }
                if (new != old) return new > old
            }

            false
            // RK <--
        }
    }

    data class Arguments(
        val isFoss: Boolean,
        val isPreview: Boolean, // RK: Reikai's preview channel, upstream's isNightly
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}
