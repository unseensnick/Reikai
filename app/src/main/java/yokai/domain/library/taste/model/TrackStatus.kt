package yokai.domain.library.taste.model

/**
 * Tracker-agnostic status, used by Layer A and Layer B alike to apply a single
 * status-weight table in [yokai.domain.library.taste.interactor.ComputeTasteProfile].
 *
 * Per-tracker integers are mapped at fetch time (Layer B) or join time (Layer A).
 */
enum class TrackStatus {
    COMPLETED,
    READING,
    ON_HOLD,
    PLAN_TO_READ,
    DROPPED,
    UNKNOWN,
}
