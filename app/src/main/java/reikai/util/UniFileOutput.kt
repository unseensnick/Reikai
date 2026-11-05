package reikai.util

import android.content.Context
import com.hippo.unifile.UniFile
import java.io.IOException
import java.io.OutputStream

/**
 * Opens [this] to be written from empty. UniFile's own `openOutputStream()` asks for plain "w", which the
 * platform's document providers write in place since Android 10, so a shorter file over an existing one
 * kept the old one's tail. Mihon's `ZipWriter` opens with "wt" for the same reason.
 */
fun UniFile.openTruncatingOutputStream(context: Context): OutputStream =
    context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")
