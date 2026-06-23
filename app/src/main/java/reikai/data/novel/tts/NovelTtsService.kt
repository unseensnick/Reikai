package reikai.data.novel.tts

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reikai.domain.novel.tts.TtsPlayback

/**
 * Foreground service that keeps the process alive for novel read-aloud and shows a media-style
 * notification with lock-screen + headset controls. It owns no playback logic: it mirrors
 * [NovelTtsSession.state] into the notification + a [MediaSessionCompat], and routes the transport
 * actions back to the controller through the session callbacks. It stops itself once playback ends.
 */
class NovelTtsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSessionCompat
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "ReikaiNovelTts").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = NovelTtsSession.onPlay()
                override fun onPause() = NovelTtsSession.onPause()
                override fun onStop() = NovelTtsSession.onStop()
            })
            isActive = true
        }
        NovelTtsSession.state
            .onEach(::render)
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> NovelTtsSession.onPlay()
            ACTION_PAUSE -> NovelTtsSession.onPause()
            ACTION_STOP -> NovelTtsSession.onStop()
        }
        if (!started) {
            started = true
            startForeground(Notifications.ID_NOVEL_TTS, buildNotification(NovelTtsSession.state.value))
        }
        return START_NOT_STICKY
    }

    private fun render(state: NovelTtsSession.State) {
        if (state.playback == TtsPlayback.Stopped) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        updateMediaSession(state)
        if (started) {
            NotificationManagerCompat.from(this).notify(Notifications.ID_NOVEL_TTS, buildNotification(state))
        }
    }

    private fun updateMediaSession(state: NovelTtsSession.State) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title.ifBlank { CONTENT_TITLE })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, CONTENT_TEXT)
                .build(),
        )
        val playing = state.playback == TtsPlayback.Playing
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
    }

    private fun buildNotification(state: NovelTtsSession.State): Notification {
        val playing = state.playback == TtsPlayback.Playing
        val playPause = if (playing) {
            NotificationCompat.Action(R.drawable.ic_pause_24dp, "Pause", servicePendingIntent(ACTION_PAUSE))
        } else {
            NotificationCompat.Action(R.drawable.ic_play_arrow_24dp, "Play", servicePendingIntent(ACTION_PLAY))
        }
        val stop = NotificationCompat.Action(R.drawable.ic_close_24dp, "Stop", servicePendingIntent(ACTION_STOP))
        return NotificationCompat.Builder(this, Notifications.CHANNEL_NOVEL_TTS)
            .setSmallIcon(R.drawable.ic_reikai)
            .setContentTitle(state.title.ifBlank { CONTENT_TITLE })
            .setContentText(CONTENT_TEXT)
            .addAction(playPause)
            .addAction(stop)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setSilent(true)
            .build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, NovelTtsService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onDestroy() {
        mediaSession.isActive = false
        mediaSession.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_PLAY = "reikai.tts.PLAY"
        private const val ACTION_PAUSE = "reikai.tts.PAUSE"
        private const val ACTION_STOP = "reikai.tts.STOP"
        private const val CONTENT_TITLE = "Reading aloud"
        private const val CONTENT_TEXT = "Novel read aloud"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NovelTtsService::class.java))
        }
    }
}
