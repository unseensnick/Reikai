package reikai.util

/**
 * The first throwable in this one's cause chain, itself included, that [pick] recognises. Error
 * messages need it because OkHttp's await (OkHttpExtensions.kt) wraps every network failure in a bare
 * IOException carrying the real one as its cause.
 */
fun <T : Any> Throwable.firstCause(pick: (Throwable) -> T?): T? =
    generateSequence(this) { it.cause }.firstNotNullOfOrNull(pick)

/**
 * The message of the deepest cause, which is the one that actually says what went wrong. Moved here
 * from Mihon's ExtensionLoader so a failed novel plugin names its cause the way a failed extension does.
 */
val Throwable.rootMessage: String
    get() {
        val root = generateSequence(this) { it.cause }.last()
        return listOfNotNull(root::class.simpleName, root.message).joinToString(": ")
    }
