package reikai.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Stands in for the graph's `@AppCoroutineScope` in a test that builds an app-scoped class by hand. It leaves out
 * the release builds' exception handler, so an uncaught failure still reaches the test runner. [AppScopeSettler]
 * ends it with the test that made it.
 */
fun testAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(AppScopeSettler::track)

/**
 * A scope that never runs what is launched on it, for a class whose launch is a background warm-up the test does not
 * exercise (`CoverCache`'s custom cover index): reads then take the class's not-yet-warmed path, deterministically.
 */
fun idleAppScope(): CoroutineScope = CoroutineScope(Job()).apply { cancel() }
