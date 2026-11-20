package eu.kanade.tachiyomi.ui.deeplink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import reikai.util.MainActivityTarget

class DeepLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent.apply {
            flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            setClass(applicationContext, MainActivityTarget::class.java) // RK
        }
        startActivity(intent)
        finish()
    }
}
