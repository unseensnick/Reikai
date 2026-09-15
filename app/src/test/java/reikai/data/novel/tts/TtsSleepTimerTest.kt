package reikai.data.novel.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TtsSleepTimerTest {

    private var now = 1_000_000L
    private val sleepTimer = TtsSleepTimer { now }

    private val at get() = sleepTimer.timer.value as SleepTimer.At

    @Test
    fun `a countdown ends its minutes from now`() {
        sleepTimer.setMinutes(15)

        sleepTimer.timer.value shouldBe SleepTimer.At(now + 15 * 60_000L, 15)
    }

    @Test
    fun `a countdown does not fire before its end`() {
        sleepTimer.setMinutes(15)
        now += 15 * 60_000L - 1

        sleepTimer.expire() shouldBe false
    }

    @Test
    fun `a countdown fires at its end`() {
        sleepTimer.setMinutes(15)
        now += 15 * 60_000L

        sleepTimer.expire() shouldBe true
    }

    @Test
    fun `a countdown clears once it fires`() {
        sleepTimer.setMinutes(15)
        now += 15 * 60_000L

        sleepTimer.expire()

        sleepTimer.timer.value shouldBe SleepTimer.Off
    }

    @Test
    fun `the end of chapter timer never fires on the clock`() {
        sleepTimer.setEndOfChapter()
        now += 24 * 60 * 60_000L

        sleepTimer.expire() shouldBe false
    }

    @Test
    fun `a started minute counts as a whole one`() {
        sleepTimer.setMinutes(15)
        now += 30_000L

        sleepTimer.minutesLeft(at) shouldBe 15
    }

    @Test
    fun `the next tick comes when the minutes left change`() {
        sleepTimer.setMinutes(15)
        now += 20_000L

        sleepTimer.untilNextTick(at) shouldBe 40_000L
    }

    @Test
    fun `the next tick on a whole minute is a minute away`() {
        sleepTimer.setMinutes(15)

        sleepTimer.untilNextTick(at) shouldBe 60_000L
    }

    @Test
    fun `stopping clears a countdown`() {
        sleepTimer.setMinutes(30)

        sleepTimer.onPublished(stopped = true)

        sleepTimer.timer.value shouldBe SleepTimer.Off
    }

    @Test
    fun `a countdown set while stopped starts counting when reading starts`() {
        sleepTimer.onPublished(stopped = true)
        sleepTimer.setMinutes(15)
        now += 30 * 60_000L
        sleepTimer.onPublished(stopped = false)

        at.endsAt shouldBe now + 15 * 60_000L
    }

    @Test
    fun `playing on keeps a countdown`() {
        sleepTimer.onPublished(stopped = false)
        sleepTimer.setMinutes(30)
        val set = at
        now += 5 * 60_000L

        sleepTimer.onPublished(stopped = false)

        sleepTimer.timer.value shouldBe set
    }

    @Test
    fun `the end of chapter timer is taken at the chapter end`() {
        sleepTimer.setEndOfChapter()

        sleepTimer.takeEndOfChapter() shouldBe true
    }

    @Test
    fun `the end of chapter timer is taken only once`() {
        sleepTimer.setEndOfChapter()
        sleepTimer.takeEndOfChapter()

        sleepTimer.takeEndOfChapter() shouldBe false
    }

    @Test
    fun `a countdown is not taken at a chapter end`() {
        sleepTimer.setMinutes(15)

        sleepTimer.takeEndOfChapter() shouldBe false
    }

    @Test
    fun `playing on keeps the end of chapter timer`() {
        sleepTimer.setEndOfChapter()

        sleepTimer.onPublished(stopped = false)

        sleepTimer.timer.value shouldBe SleepTimer.EndOfChapter
    }
}
