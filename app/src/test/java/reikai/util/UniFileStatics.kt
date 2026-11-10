package reikai.util

import android.net.Uri
import android.provider.DocumentsContract
import android.text.TextUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic

/**
 * What unifile's file-backed implementation and the storage gateway ask of android.jar's statics, which the JVM
 * test jar throws on: a plain-file test outside Robolectric stubs them around each test. A file-backed UniFile is
 * never a tree document, so its listing needs no count query.
 */
fun stubUniFileStatics() {
    mockkStatic(TextUtils::class, Uri::class, DocumentsContract::class)
    every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
    every { Uri.fromFile(any()) } returns mockk(relaxed = true)
    every { DocumentsContract.isTreeUri(any()) } returns false
}

fun unstubUniFileStatics() = unmockkStatic(TextUtils::class, Uri::class, DocumentsContract::class)
