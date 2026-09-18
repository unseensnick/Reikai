package reikai.util

import android.content.Context
import android.content.pm.ApplicationInfo
import eu.kanade.tachiyomi.BuildConfig

/** A debuggable debug build, which keeps Chrome's inspector on every WebView for development. */
fun Context.isDebugInspectorBuild(): Boolean =
    BuildConfig.DEBUG && 0 != applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE

/**
 * What WebView debugging is set to when a novel web page is built. The switch is process-wide, so off
 * closes the inspector on every WebView in the app, which is what turning the setting off promises;
 * a debug build keeps it on regardless.
 */
fun webContentsDebugging(devTools: Boolean, debugBuild: Boolean): Boolean = devTools || debugBuild
