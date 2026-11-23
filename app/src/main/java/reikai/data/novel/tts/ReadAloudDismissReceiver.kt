package reikai.data.novel.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The read aloud notification swiped away, which stops read aloud rather than leaving it playing unseen. */
class ReadAloudDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = NovelTtsSession.onStop()
}
