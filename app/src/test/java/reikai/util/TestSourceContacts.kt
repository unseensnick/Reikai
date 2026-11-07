package reikai.util

import kotlinx.coroutines.test.TestCoroutineScheduler
import reikai.domain.download.SourceContacts

/** Contacts timed on the test's virtual clock, the one the engines' delays run on. */
fun TestCoroutineScheduler.sourceContacts() = SourceContacts { currentTime }

/** Contacts on a real clock, for an engine whose delays run on the real IO dispatcher. */
fun realTimeSourceContacts() = SourceContacts { System.nanoTime() / 1_000_000 }
