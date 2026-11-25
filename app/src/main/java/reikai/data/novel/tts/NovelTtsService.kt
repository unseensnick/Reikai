package reikai.data.novel.tts

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
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
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
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
import logcat.LogPriority
import mihon.app.di.appGraph
import reikai.data.notification.shownEntryName
import reikai.domain.novel.tts.ReadAloudStop
import reikai.domain.novel.tts.TtsPauseReason
import reikai.domain.novel.tts.TtsPlayback
import reikai.util.notificationIcon
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import mihon.icons.materialsymbols.R as MaterialSymbolsR

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
    private val focusPolicy = TtsFocusPolicy { NovelTtsSession.sleepTimer.expire() }
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
        NovelTtsSession.stops
            .onEach { stopped -> stopped.stopReason?.let { showStopped(stopped, it) } }
            .launchIn(scope)
        scope.launch { runSleepTimer() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every start may be a startForegroundService, which must reach startForeground even when it
        // is about to stop again. The system can refuse one from the background, which pauses speech
        // rather than crashing; the session's paused stop ends it if nobody comes back to it.
        try {
            startForeground(Notifications.ID_NOVEL_TTS, buildNotification(NovelTtsSession.state.value))
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                e !is ForegroundServiceStartNotAllowedException
            ) {
                throw e
            }
            logcat(LogPriority.WARN, e) { "Read aloud could not come to the foreground" }
            NovelTtsSession.onPause(TtsPauseReason.Refused)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        foreground = true
        running = true
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
    private fun pause(reason: TtsPauseReason = TtsPauseReason.User) {
        carryOut(focusPolicy.onUserPause())
        NovelTtsSession.onPause(reason)
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
                // Until the countdown ends: expiring clears it, paused or not, and a zero delay would not
                // suspend for collectLatest to cancel this on the cleared value.
                while (timer.timer.value == value) {
                    delay(timer.untilNextTick(value))
                    if (timer.expire()) pause(TtsPauseReason.SleepTimer) else refreshNotification()
                }
            }
    }

    private fun carryOut(focus: TtsFocusPolicy.Focus) {
        when (focus) {
            TtsFocusPolicy.Focus.Keep -> Unit
            TtsFocusPolicy.Focus.Request -> {
                val result = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest)
                respond(
                    focusPolicy.onRequestResult(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED),
                    TtsPauseReason.FocusLoss,
                )
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
        val reason = when (mapped) {
            TtsFocusPolicy.Change.LossTransient,
            TtsFocusPolicy.Change.LossTransientCanDuck,
            -> TtsPauseReason.TransientFocusLoss
            // A gain pausing is the sleep timer having run out during the loss.
            TtsFocusPolicy.Change.Gain -> TtsPauseReason.SleepTimer
            TtsFocusPolicy.Change.Loss -> TtsPauseReason.FocusLoss
        }
        respond(focusPolicy.onFocusChange(mapped), reason)
    }

    /** [reason] is what a pause in [response] is put down to. */
    private fun respond(response: TtsFocusPolicy.Response, reason: TtsPauseReason) {
        when (response) {
            TtsFocusPolicy.Response.Nothing -> Unit
            TtsFocusPolicy.Response.Pause -> NovelTtsSession.onPause(reason)
            TtsFocusPolicy.Response.PauseAndAbandon -> {
                NovelTtsSession.onPause(reason)
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
        running = false
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

    // A media-session notification is exempt from POST_NOTIFICATIONS; the guard in Context.notify would freeze it.
    @SuppressLint("MissingPermission")
    private fun refreshNotification() {
        val state = NovelTtsSession.state.value
        if (!foreground || state.playback == TtsPlayback.Stopped) return
        NotificationManagerCompat.from(this).notify(Notifications.ID_NOVEL_TTS, buildNotification(state))
    }

    private fun buildNotification(state: NovelTtsSession.State): Notification {
        val playing = state.playback == TtsPlayback.Playing
        val playPause = if (playing) {
            action(
                MaterialSymbolsR.drawable.rounded_filled_pause,
                stringResource(MR.strings.action_pause),
                ACTION_PAUSE,
            )
        } else {
            action(
                MaterialSymbolsR.drawable.rounded_filled_play_arrow,
                stringResource(MR.strings.action_resume),
                ACTION_PLAY,
            )
        }
        val stop = action(MaterialSymbolsR.drawable.rounded_close, stringResource(MR.strings.tts_stop), ACTION_STOP)
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
            .setSmallIcon(notificationIcon)
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
            .setContentIntent(openReader(state))
            // Swiping it away, where the system allows that, is stopping read aloud.
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    this,
                    DISMISS_REQUEST,
                    Intent(this, ReadAloudDismissReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    /**
     * The reader on the chapter being spoken, following read aloud rather than opening a chapter of its
     * own. One request code, so each build replaces the last one's chapter.
     */
    private fun openReader(state: NovelTtsSession.State): PendingIntent? {
        val novelId = state.novelId ?: return null
        val chapterId = state.chapterId ?: return null
        val intent = ReaderActivity.newNovelIntent(this, novelId, chapterId, state.sourceScoped, followSession = true)
        return PendingIntent.getActivity(
            this,
            OPEN_READER_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Why speech stopped by itself, left up for a few seconds once the media notification has gone. */
    @SuppressLint("MissingPermission")
    private fun showStopped(state: NovelTtsSession.State, reason: ReadAloudStop) {
        val text = when (reason) {
            ReadAloudStop.LoadFailed -> MR.strings.tts_stopped_load_failed
            ReadAloudStop.LoadTimedOut -> MR.strings.tts_stopped_load_timed_out
            ReadAloudStop.EngineSilent -> MR.strings.tts_stopped_voice_silent
        }
        val notice = NotificationCompat.Builder(this, Notifications.CHANNEL_NOVEL_TTS)
            .setSmallIcon(notificationIcon)
            .setContentTitle(contentTitle(state))
            .setContentText(stringResource(text))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(STOPPED_NOTICE_MS)
            .build()
        // Without notification permission there is nowhere to say it, and nothing else to do.
        runCatching { NotificationManagerCompat.from(this).notify(Notifications.ID_NOVEL_TTS, notice) }
    }

    /** The notification's and the lock screen's title, which name the novel only while content is shown. */
    private fun contentTitle(state: NovelTtsSession.State) =
        shownEntryName(
            state.title,
            appGraph.securityPreferences.hideNotificationContent.get(),
            appGraph.securityPreferences.hideAdultNotificationContent.get(),
            state.isAdult,
        )
            ?.ifBlank { null }
            ?: stringResource(MR.strings.tts_reading_aloud)

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
        running = false
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
        private const val OPEN_READER_REQUEST = 1
        private const val DISMISS_REQUEST = 2
        private const val STOPPED_NOTICE_MS = 8_000L

        /** Set once the service is in the foreground, so a second play does not start it again. */
        @Volatile
        private var running = false

        fun start(context: Context) {
            if (running) return
            // Refused at the call as well as in onStartCommand: from the background, or by the app op.
            // Posted, as the service's own refusal is, so the pause lands after the play that asked.
            try {
                ContextCompat.startForegroundService(context, Intent(context, NovelTtsService::class.java))
            } catch (e: RuntimeException) {
                if (e !is IllegalStateException && e !is SecurityException) throw e
                logcat(LogPriority.WARN, e) { "Read aloud could not start in the foreground" }
                Handler(Looper.getMainLooper()).post { NovelTtsSession.onPause(TtsPauseReason.Refused) }
            }
        }
    }
}

/**
 * The system seek bar measures time, so each paragraph is shown as one second of it. Speed stays zero,
 * so the bar only moves when a paragraph does.
 */
internal const val PARAGRAPH_MS = 1_000L

/** Every control read-aloud answers: it steps and seeks by paragraph as well as playing. */
private val MEDIA_SESSION_ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
    PlaybackStateCompat.ACTION_SEEK_TO
