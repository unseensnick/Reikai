package reikai.data.notification

/**
 * The name of a series a notification may show: none while "Hide notification content" is on. The manga
 * notifiers apply the setting at each of theirs, and every novel one takes it from here.
 */
fun shownEntryName(name: String, hideContent: Boolean): String? = name.takeUnless { hideContent }
