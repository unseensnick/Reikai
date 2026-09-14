package reikai.data.novel.tts

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.getSystemService
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import reikai.domain.novel.tts.TtsPlayback
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Foreground service that keeps the process alive for novel read-aloud and shows a media-style
 * notification with lock-screen + headset controls. It owns no playback logic: it mirrors
 * [NovelTtsSession.state] into the notification + a [MediaSessionCompat], routes the controls back
 * through the session callbacks, and carries out [TtsFocusPolicy] and the [TtsSleepTimer] countdown.
 * It stops itself once playback ends.
 */
class NovelTtsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequestCompat
    private val focusPolicy = TtsFocusPolicy()
    private var foreground = false
    private var lastStartId = 0
    private var noisyRegistered = false
    private val mediaButtonClaim = TtsMediaButtonClaim()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService<AudioManager>()!!
        // Media rather than accessibility usage: this is listening, on the media volume the TTS engine
        // already plays on. Speech lets the system treat it as talk, and pausing when ducked makes it
        // send the duck loss instead of lowering the voice under other audio.
        focusRequest = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(::onFocusChange, Handler(Looper.getMainLooper()))
            .build()
        mediaSession = MediaSessionCompat(this, "ReikaiNovelTts").apply {
            setCallback(sessionCallback)
            // Mutable because the system fills in the key event when it restarts playback from a button.
            val buttonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(this@NovelTtsService, MediaButtonReceiver::class.java))
            setMediaButtonReceiver(
                PendingIntent.getBroadcast(this@NovelTtsService, 0, buttonIntent, PendingIntent.FLAG_MUTABLE),
            )
            isActive = true
        }
        NovelTtsSession.state
            .onEach(::render)
            .launchIn(scope)
        scope.launch { runSleepTimer() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every start may be a startForegroundService, which must reach startForeground even when it
        // is about to stop again.
        startForeground(Notifications.ID_NOVEL_TTS, buildNotification(NovelTtsSession.state.value))
        foreground = true
        // Nothing to read: a media button after playback ended, or a play that failed before this ran.
        // The session's callbacks may still reach a reader, and a button must not start it again.
        if (NovelTtsSession.state.value.playback == TtsPlayback.Stopped) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_STOP -> NovelTtsSession.onStop()
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            Intent.ACTION_MEDIA_BUTTON -> MediaButtonReceiver.handleIntent(mediaSession, intent)
        }
        return START_NOT_STICKY
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = play()
        override fun onPause() = pause()
        override fun onStop() = NovelTtsSession.onStop()
        override fun onSkipToNext() = next()
        override fun onSkipToPrevious() = previous()

        override fun onSeekTo(pos: Long) = NovelTtsSession.onSeek((pos / PARAGRAPH_MS).toInt())

        // Mapped here rather than by the default, which holds a headset tap back to watch for a double
        // tap before acting on it and reads that as skip.
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            val event = IntentCompat.getParcelableExtra(mediaButtonEvent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                ?: return false
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                    if (NovelTtsSession.state.value.playback == TtsPlayback.Playing) pause() else play()
                KeyEvent.KEYCODE_MEDIA_PLAY -> play()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
                KeyEvent.KEYCODE_MEDIA_STOP -> NovelTtsSession.onStop()
                KeyEvent.KEYCODE_MEDIA_NEXT -> next()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> previous()
                else -> return false
            }
            return true
        }
    }

    private fun play() = NovelTtsSession.onPlay()

    /** Every pause not caused by a focus loss, so no later gain resumes it. */
    private fun pause() {
        carryOut(focusPolicy.onUserPause())
        NovelTtsSession.onPause()
    }

    private fun next() = NovelTtsSession.onNext()

    private fun previous() = NovelTtsSession.onPrevious()

    private fun render(state: NovelTtsSession.State) {
        carryOut(focusPolicy.onPlayback(state.playback))
        setNoisyReceiver(state.playback == TtsPlayback.Playing)
        mediaButtonClaim.hold(state.playback == TtsPlayback.Playing)
        if (state.playback == TtsPlayback.Stopped) {
            // Before the first start command there is nothing to stop yet; that command stops instead.
            if (foreground) stopForegroundAndSelf()
            return
        }
        updateMediaSession(state)
        refreshNotification()
    }

    private suspend fun runSleepTimer() {
        val timer = NovelTtsSession.sleepTimer
        val active = NovelTtsSession.state.map { it.playback != TtsPlayback.Stopped }.distinctUntilChanged()
        combine(active, timer.timer) { isActive, value -> value.takeIf { isActive } }
            .collectLatest { value ->
                refreshNotification()
                if (value !is SleepTimer.At) return@collectLatest
                while (true) {
                    delay(timer.untilNextTick(value))
                    if (timer.expire()) return@collectLatest pause()
                    refreshNotification()
                }
            }
    }

    private fun carryOut(focus: TtsFocusPolicy.Focus) {
        when (focus) {
            TtsFocusPolicy.Focus.Keep -> Unit
            TtsFocusPolicy.Focus.Request -> {
                val result = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest)
                respond(focusPolicy.onRequestResult(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED))
            }
            TtsFocusPolicy.Focus.Abandon -> AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
        }
    }

    private fun onFocusChange(change: Int) {
        val mapped = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> TtsFocusPolicy.Change.Gain
            AudioManager.AUDIOFOCUS_LOSS -> TtsFocusPolicy.Change.Loss
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> TtsFocusPolicy.Change.LossTransient
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> TtsFocusPolicy.Change.LossTransientCanDuck
            else -> return
        }
        respond(focusPolicy.onFocusChange(mapped))
    }

    private fun respond(response: TtsFocusPolicy.Response) {
        when (response) {
            TtsFocusPolicy.Response.Nothing -> Unit
            TtsFocusPolicy.Response.Pause -> NovelTtsSession.onPause()
            TtsFocusPolicy.Response.PauseAndAbandon -> {
                NovelTtsSession.onPause()
                AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
            }
            TtsFocusPolicy.Response.Resume -> NovelTtsSession.onPlay()
        }
    }

    private fun setNoisyReceiver(register: Boolean) {
        if (register == noisyRegistered) return
        if (register) {
            // The system still delivers its own broadcasts to a receiver no other app can reach.
            ContextCompat.registerReceiver(
                this,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        } else {
            unregisterReceiver(noisyReceiver)
        }
        noisyRegistered = register
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        // By id, so a start that arrived since is not stopped along with this one.
        stopSelf(lastStartId)
    }

    private fun updateMediaSession(state: NovelTtsSession.State) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, contentTitle(state))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, stringResource(MR.strings.tts_novel_read_aloud))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.paragraphCount * PARAGRAPH_MS)
                .build(),
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MEDIA_SESSION_ACTIONS)
                .setState(
                    if (state.playback == TtsPlayback.Playing) {
                        PlaybackStateCompat.STATE_PLAYING
                    } else {
                        PlaybackStateCompat.STATE_PAUSED
                    },
                    state.paragraph * PARAGRAPH_MS,
                    0f,
                )
                .build(),
        )
    }

    private fun refreshNotification() {
        val state = NovelTtsSession.state.value
        if (!foreground || state.playback == TtsPlayback.Stopped) return
        NotificationManagerCompat.from(this).notify(Notifications.ID_NOVEL_TTS, buildNotification(state))
    }

    private fun buildNotification(state: NovelTtsSession.State): Notification {
        val playing = state.playback == TtsPlayback.Playing
        val playPause = if (playing) {
            action(R.drawable.ic_pause_24dp, stringResource(MR.strings.action_pause), ACTION_PAUSE)
        } else {
            action(R.drawable.ic_play_arrow_24dp, stringResource(MR.strings.action_resume), ACTION_PLAY)
        }
        val stop = action(R.drawable.ic_close_24dp, stringResource(MR.strings.tts_stop), ACTION_STOP)
        val actions = listOf(
            action(
                R.drawable.ic_skip_previous_24dp,
                stringResource(MR.strings.tts_previous_paragraph),
                ACTION_PREVIOUS,
            ),
            playPause,
            action(R.drawable.ic_skip_next_24dp, stringResource(MR.strings.tts_next_paragraph), ACTION_NEXT),
            stop,
        )
        return NotificationCompat.Builder(this, Notifications.CHANNEL_NOVEL_TTS)
            .setSmallIcon(R.drawable.ic_reikai)
            .setContentTitle(contentTitle(state))
            .setContentText(contentText())
            .apply { actions.forEach { addAction(it) } }
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setSilent(true)
            .build()
    }

    private fun contentTitle(state: NovelTtsSession.State) =
        state.title.ifBlank { stringResource(MR.strings.tts_reading_aloud) }

    private fun contentText(): String {
        val timer = NovelTtsSession.sleepTimer
        return when (val value = timer.timer.value) {
            SleepTimer.Off -> stringResource(MR.strings.tts_novel_read_aloud)
            is SleepTimer.At -> timer.minutesLeft(value).let {
                pluralStringResource(MR.plurals.tts_sleep_timer_minutes_left, it, it)
            }
            SleepTimer.EndOfChapter -> stringResource(MR.strings.tts_sleep_timer_ends_with_chapter)
        }
    }

    private fun action(icon: Int, title: String, action: String): NotificationCompat.Action {
        val intent = Intent(this, NovelTtsService::class.java).setAction(action)
        val pending = PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(icon, title, pending)
    }

    override fun onDestroy() {
        setNoisyReceiver(false)
        mediaButtonClaim.release()
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
        mediaSession.isActive = false
        mediaSession.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_PLAY = "reikai.tts.PLAY"
        private const val ACTION_PAUSE = "reikai.tts.PAUSE"
        private const val ACTION_STOP = "reikai.tts.STOP"
        private const val ACTION_NEXT = "reikai.tts.NEXT"
        private const val ACTION_PREVIOUS = "reikai.tts.PREVIOUS"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NovelTtsService::class.java))
        }
    }
}

/**
 * The system seek bar measures time, so each paragraph is shown as one second of it. Speed stays zero,
 * so the bar only moves when a paragraph does.
 */
internal const val PARAGRAPH_MS = 1_000L

/** Every control read-aloud answers: it steps and seeks by paragraph as well as playing. */
internal val MEDIA_SESSION_ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
    PlaybackStateCompat.ACTION_SEEK_TO
