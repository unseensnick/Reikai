package eu.kanade.tachiyomi.widget

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.R
import androidx.core.view.inputmethod.EditorInfoCompat
import com.google.android.material.textfield.TextInputEditText
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.util.view.setIncognito
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mihon.app.di.appGraph

/**
 * A custom [TextInputEditText] that sets [EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING] to imeOptions
 * if [BasePreferences.incognitoMode] is true. Some IMEs may not respect this flag.
 *
 * @see setIncognito
 */
class TachiyomiTextInputEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.editTextStyle,
) : TextInputEditText(context, attrs, defStyleAttr) {

    private var scope: CoroutineScope? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val basePreferences = context.appGraph.basePreferences
        setIncognito(basePreferences, scope!!)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scope?.cancel()
        scope = null
    }
}
