package reikai.domain.recommendation.taste

/**
 * Tracker-agnostic reading status. Per-tracker status integers are mapped to this at fetch time so
 * [ComputeTasteProfile] can apply a single status-weight table regardless of which tracker an entry
 * came from.
 */
enum class TrackStatus {
    COMPLETED,
    READING,
    ON_HOLD,
    PLAN_TO_READ,
    DROPPED,
    UNKNOWN,
}
