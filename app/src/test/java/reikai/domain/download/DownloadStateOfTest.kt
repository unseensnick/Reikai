package reikai.domain.download

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** The rule every Reikai chapter row reads its download state by, for manga and novels alike. */
class DownloadStateOfTest {

    @ParameterizedTest
    @EnumSource(Download.State::class)
    @DisplayName("a queued chapter shows its queue state even when a copy is on disk")
    fun queuedStateWins(queued: Download.State) {
        downloadStateOf(queued) { true } shouldBe queued
    }

    @Test
    @DisplayName("a chapter not queued but on disk is downloaded")
    fun onDiskIsDownloaded() {
        downloadStateOf(null) { true } shouldBe Download.State.DOWNLOADED
    }

    @Test
    @DisplayName("a chapter neither queued nor on disk is not downloaded")
    fun neitherIsNotDownloaded() {
        downloadStateOf(null) { false } shouldBe Download.State.NOT_DOWNLOADED
    }

    @Test
    @DisplayName("the disk index is not read for a queued chapter")
    fun diskIsNotReadWhenQueued() {
        var diskReads = 0
        downloadStateOf(Download.State.QUEUE) { diskReads++ > 0 }
        diskReads shouldBe 0
    }
}
