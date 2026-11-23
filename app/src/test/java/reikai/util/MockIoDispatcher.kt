package reikai.util

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.core.migration.Migrator

/**
 * Stubs Dispatchers.IO with [dispatcher] until `unmockkStatic(Dispatchers::class)`. An object that binds
 * IO on first use would keep the stub for the rest of the test JVM, so those are first used here on the
 * real one: Migrator's scope is, and a later test's migration otherwise waits on a finished scheduler.
 */
fun mockIoDispatcher(dispatcher: CoroutineDispatcher) {
    check(Migrator.scope.isActive)
    mockkStatic(Dispatchers::class)
    every { Dispatchers.IO } returns dispatcher
}

/**
 * Puts Main on [main] and IO and Default on one dispatcher over its scheduler, so `runTest`, which takes
 * Main's scheduler, drives every launch in virtual time. Undone by [resetVirtualDispatchers].
 */
fun virtualDispatchers(main: TestDispatcher = StandardTestDispatcher()): TestDispatcher {
    Dispatchers.setMain(main)
    val background = StandardTestDispatcher(main.scheduler)
    mockIoDispatcher(background)
    every { Dispatchers.Default } returns background
    return background
}

/**
 * Points Dispatchers.Default back at a virtual IO, when IO is one. A `coEvery` or `coVerify` while
 * Dispatchers is mocked leaves Default answering a bare mock, since mockk takes the getter its runBlocking
 * reads for a chained call, so a harness calls this after its own. A second `mockkStatic` drops the IO stub.
 */
fun restubDefaultDispatcher() {
    val io = Dispatchers.IO
    if (io is TestDispatcher) every { Dispatchers.Default } returns io
}

fun resetVirtualDispatchers() {
    unmockkStatic(Dispatchers::class)
    Dispatchers.resetMain()
}
