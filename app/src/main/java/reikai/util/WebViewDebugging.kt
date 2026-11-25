package reikai.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import eu.kanade.tachiyomi.BuildConfig
import mihon.app.di.appGraph

/** A debuggable debug build, which keeps Chrome's inspector on every WebView for development. */
fun Context.isDebugInspectorBuild(): Boolean =
    BuildConfig.DEBUG && 0 != applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE

/**
 * What WebView debugging is set to. The switch is process-wide, so off closes the inspector on every
 * WebView in the app, which is what turning the setting off promises; a debug build keeps it on regardless.
 */
fun webContentsDebugging(devTools: Boolean, debugBuild: Boolean): Boolean = devTools || debugBuild

/**
 * Applies the developer tools setting, called wherever a WebView is built: the switch starts off in every
 * process, so a page built after a restart would otherwise stay closed to the inspector.
 */
fun Context.applyWebContentsDebugging(
    devTools: Boolean = appGraph.novelPreferences.readerWebViewDevTools().get(),
) {
    WebView.setWebContentsDebuggingEnabled(webContentsDebugging(devTools, isDebugInspectorBuild()))
}
