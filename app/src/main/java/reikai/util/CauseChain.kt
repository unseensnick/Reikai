package reikai.util

/**
 * The first throwable in this one's cause chain, itself included, that [pick] recognises. Error
 * messages need it because OkHttp's await (OkHttpExtensions.kt) wraps every network failure in a bare
 * IOException carrying the real one as its cause.
 */
fun <T : Any> Throwable.firstCause(pick: (Throwable) -> T?): T? =
    generateSequence(this) { it.cause }.firstNotNullOfOrNull(pick)
