package reikai.data.track

import java.io.IOException

/**
 * A tracker call made with no usable login. An [IOException] on purpose: OkHttp rethrows anything else
 * an interceptor throws on its own dispatcher thread, which crashed the app, and TrackerError reads
 * this one as signed out for every caller (fill, refresh, search, bind). Open so a tracker's own
 * signed-out type (MyAnimeList's MALTokenExpired) keeps its name and still reads as one.
 */
open class TrackerSignedOutException(trackerName: String) : IOException("Not authenticated with $trackerName")
