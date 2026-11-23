package reikai.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** The free-space floor both downloaders check before fetching a chapter. */
class DownloadSpaceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("rooms")
    fun `a volume has room while it stays at or above the floor`(
        name: String,
        hasRoom: () -> Boolean,
        expected: Boolean,
    ) {
        hasRoom() shouldBe expected
    }

    companion object {
        private const val MB = 1024L * 1024

        @JvmStatic
        fun rooms() = listOf(
            Arguments.of("a nearly full volume has no room", { hasRoomToDownload(199 * MB) }, false),
            Arguments.of("a volume exactly at the floor has room", { hasRoomToDownload(200 * MB) }, true),
            Arguments.of("a volume with room to spare has room", { hasRoomToDownload(500 * MB) }, true),
            Arguments.of("a volume whose space cannot be read is let through", { hasRoomToDownload(-1L) }, true),
            Arguments.of(
                "a copy that leaves the volume above the floor has room",
                { hasRoomToCopy(availableBytes = 300 * MB, bytes = 99 * MB) },
                true,
            ),
            Arguments.of(
                "a copy that would take the volume below the floor has no room",
                { hasRoomToCopy(availableBytes = 300 * MB, bytes = 101 * MB) },
                false,
            ),
            Arguments.of(
                "a copy whose volume cannot be read is let through",
                { hasRoomToCopy(availableBytes = -1L, bytes = 1L) },
                true,
            ),
        )
    }
}
