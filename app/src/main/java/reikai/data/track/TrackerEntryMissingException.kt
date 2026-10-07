package reikai.data.track

/** The tracker has no entry at the id it was asked for, read by "Fill from tracker" as a 404 is. */
class TrackerEntryMissingException(trackerName: String) : Exception("No entry on $trackerName")
