package reikai.novel.install

import kotlinx.serialization.SerializationException
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.domain.novel.LnSourceIdentity
import reikai.util.firstCause
import reikai.util.rootMessage

/**
 * An installed plugin that did not load, so the Extensions list can show it under Not loaded rather
 * than drop it from sight. The novel counterpart of a manga extension's not-loaded reason; plugins
 * are neither signed nor content-rated, so only these reasons can apply.
 */
data class LnPluginLoadFailure(
    val url: String,
    /** Null for a legacy install whose metadata never recorded one. */
    val pluginId: String?,
    val name: String,
    val iconUrl: String?,
    val lang: String?,
    val version: String?,
    /** The chapter stylesheet the registry named, fetched again with the script on a reinstall. */
    val customCssUrl: String?,
    val reason: Reason,
) {
    sealed interface Reason {
        /** The root cause in words, where the reason has one to name. */
        val message: String?

        /** No script is stored for it and fetching it again threw, so nothing runs until it is installed again. */
        data class Missing(override val message: String) : Reason

        /** The plugin ran, but what it says about itself could not be read. */
        data object Malformed : Reason {
            override val message: String? = null
        }

        /** Running the plugin threw. Retried on the next load either way. */
        data class Failed(override val message: String, val stackTrace: String) : Reason
    }

    companion object {
        /** What [error] means for the plugin at [url], named from what the app last knew about it. */
        fun of(
            url: String,
            error: Throwable,
            metadata: LnInstalledPluginMetadata?,
            seen: LnSourceIdentity?,
        ): LnPluginLoadFailure {
            val reason = if (error is LnPluginScriptMissingException) {
                Reason.Missing(message = error.rootMessage)
            } else if (error.firstCause { it as? SerializationException } != null) {
                Reason.Malformed
            } else {
                Reason.Failed(message = error.rootMessage, stackTrace = error.stackTraceToString())
            }
            return LnPluginLoadFailure(
                url = url,
                pluginId = metadata?.pluginId,
                name = pluginName(url, seen),
                iconUrl = metadata?.iconUrl ?: seen?.iconUrl,
                lang = metadata?.lang ?: seen?.lang,
                version = metadata?.version,
                customCssUrl = metadata?.customCssUrl,
                reason = reason,
            )
        }

        /** What to call a plugin in a message: the name it last loaded under, else its script's file name. */
        fun pluginName(url: String, seen: LnSourceIdentity?): String =
            seen?.name ?: url.substringAfterLast('/').substringBeforeLast('.')
    }
}

/** An installed plugin whose script is not stored and could not be fetched from its URL again. */
class LnPluginScriptMissingException(url: String, cause: Throwable) :
    Exception("no installed script for $url", cause)
