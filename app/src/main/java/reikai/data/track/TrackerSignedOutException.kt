package reikai.data.track

import java.io.IOException

/**
 * A tracker call made with no usable login. An [IOException] on purpose: OkHttp rethrows anything else
 * an interceptor throws on its own dispatcher thread, which crashed the app, and TrackerError reads
 * this one as signed out for every caller (fill, refresh, search, bind).
 */
class TrackerSignedOutException(trackerName: String) : IOException("Not authenticated with $trackerName")
