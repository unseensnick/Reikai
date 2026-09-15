package reikai.data.novel.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.tts.TtsFocusPolicy.Change
import reikai.data.novel.tts.TtsFocusPolicy.Focus
import reikai.data.novel.tts.TtsFocusPolicy.Response
import reikai.domain.novel.tts.TtsPlayback

class TtsFocusPolicyTest {

    private val policy = TtsFocusPolicy()

    /** Playing with focus granted. */
    private fun playing() = policy.also {
        it.onPlayback(TtsPlayback.Playing)
        it.onRequestResult(granted = true)
    }

    /** Paused by [change], as the service leaves it once the controller has paused. */
    private fun pausedBy(change: Change) = playing().also {
        it.onFocusChange(change)
        it.onPlayback(TtsPlayback.Paused)
    }

    @Test
    fun `starting to play requests focus`() {
        policy.onPlayback(TtsPlayback.Playing) shouldBe Focus.Request
    }

    @Test
    fun `playing on while focus is held requests nothing more`() {
        playing().onPlayback(TtsPlayback.Playing) shouldBe Focus.Keep
    }

    @Test
    fun `a denied request pauses`() {
        policy.onPlayback(TtsPlayback.Playing)

        policy.onRequestResult(granted = false) shouldBe Response.Pause
    }

    @Test
    fun `the pause after a denied request abandons nothing`() {
        policy.onPlayback(TtsPlayback.Playing)
        policy.onRequestResult(granted = false)

        policy.onPlayback(TtsPlayback.Paused) shouldBe Focus.Keep
    }

    @ParameterizedTest
    @EnumSource(Change::class, names = ["LossTransient", "LossTransientCanDuck"])
    fun `a transient loss while playing pauses`(change: Change) {
        playing().onFocusChange(change) shouldBe Response.Pause
    }

    @Test
    fun `a permanent loss pauses and gives focus back`() {
        playing().onFocusChange(Change.Loss) shouldBe Response.PauseAndAbandon
    }

    @ParameterizedTest
    @EnumSource(Change::class, names = ["LossTransient", "LossTransientCanDuck"])
    fun `a pause from a transient loss keeps focus`(change: Change) {
        playing().onFocusChange(change)

        policy.onPlayback(TtsPlayback.Paused) shouldBe Focus.Keep
    }

    @ParameterizedTest
    @EnumSource(Change::class, names = ["LossTransient", "LossTransientCanDuck"])
    fun `the gain after a transient loss resumes`(change: Change) {
        pausedBy(change).onFocusChange(Change.Gain) shouldBe Response.Resume
    }

    @Test
    fun `a transient loss seen before the pause is still resumed after`() {
        val policy = playing()
        policy.onFocusChange(Change.LossTransient)
        policy.onPlayback(TtsPlayback.Playing)
        policy.onPlayback(TtsPlayback.Paused)

        policy.onFocusChange(Change.Gain) shouldBe Response.Resume
    }

    @Test
    fun `the gain resumes only once`() {
        pausedBy(Change.LossTransient).onFocusChange(Change.Gain)

        policy.onFocusChange(Change.Gain) shouldBe Response.Nothing
    }

    @Test
    fun `a gain after a permanent loss does not resume`() {
        pausedBy(Change.Loss).onFocusChange(Change.Gain) shouldBe Response.Nothing
    }

    @Test
    fun `a permanent loss after a transient one gives focus back`() {
        pausedBy(Change.LossTransient).onFocusChange(Change.Loss) shouldBe Response.PauseAndAbandon
    }

    @Test
    fun `a user pause during a transient loss cancels the resume`() {
        pausedBy(Change.LossTransient).onUserPause()

        policy.onFocusChange(Change.Gain) shouldBe Response.Nothing
    }

    @Test
    fun `a user pause during a transient loss gives focus back`() {
        pausedBy(Change.LossTransient).onUserPause() shouldBe Focus.Abandon
    }

    @Test
    fun `a user play and pause during a transient loss cancel the resume`() {
        val policy = pausedBy(Change.LossTransient)
        policy.onPlayback(TtsPlayback.Playing)
        policy.onPlayback(TtsPlayback.Paused)

        policy.onFocusChange(Change.Gain) shouldBe Response.Nothing
    }

    /** During a call the system refuses the request, and a refusal pauses; keeping would read over the call. */
    @Test
    fun `playing during a transient loss asks for focus again`() {
        pausedBy(Change.LossTransient).onPlayback(TtsPlayback.Playing) shouldBe Focus.Request
    }

    @Test
    fun `resuming on the gain after a transient loss keeps the focus it has`() {
        val policy = pausedBy(Change.LossTransient)
        policy.onFocusChange(Change.Gain)

        policy.onPlayback(TtsPlayback.Playing) shouldBe Focus.Keep
    }

    @Test
    fun `stopping during a transient loss cancels the resume`() {
        pausedBy(Change.LossTransient).onPlayback(TtsPlayback.Stopped)

        policy.onFocusChange(Change.Gain) shouldBe Response.Nothing
    }

    @Test
    fun `a user pause while playing gives focus back once paused`() {
        playing().onUserPause()

        policy.onPlayback(TtsPlayback.Paused) shouldBe Focus.Abandon
    }

    @Test
    fun `stopping gives focus back`() {
        playing().onPlayback(TtsPlayback.Stopped) shouldBe Focus.Abandon
    }

    @Test
    fun `a transient loss while paused does not pause again`() {
        val policy = playing()
        policy.onPlayback(TtsPlayback.Paused)

        policy.onFocusChange(Change.LossTransient) shouldBe Response.Nothing
    }
}
