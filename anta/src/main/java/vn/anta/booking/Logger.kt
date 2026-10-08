package vn.anta.booking

import android.util.Log

/**
 * Kept API-compatible with base-application-wrapper's Logger.kt. Debug logs
 * include the source location so logcat entries remain clickable in Android Studio.
 */
object Logger {
    private const val TAG = "Anta"
    private val DEBUG = BuildConfig.DEBUG

    private fun getCallerInfo(): String {
        if (!DEBUG) return ""
        val loggerClassName = Logger::class.java.name
        return Thread.currentThread().stackTrace
            .drop(2)
            .firstOrNull {
                it.className != loggerClassName &&
                    it.className != NetworkLogger::class.java.name &&
                    !it.className.startsWith("java.lang.Thread")
            }
            ?.let { "(${it.fileName}:${it.lineNumber}) " }
            .orEmpty()
    }

    private fun buildMessage(description: String, params: Any?): String {
        val callerInfo = getCallerInfo()
        return if (params != null) "$callerInfo$description | Params: $params"
        else "$callerInfo$description"
    }

    fun d(description: String, params: Any? = null) {
        if (DEBUG) Log.d(TAG, buildMessage(description, params))
    }

    fun e(description: String, params: Any? = null, throwable: Throwable? = null) {
        val message = buildMessage(description, params)
        if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
    }

    fun i(description: String, params: Any? = null) {
        if (DEBUG) Log.i(TAG, buildMessage(description, params))
    }

    fun w(description: String, params: Any? = null, throwable: Throwable? = null) {
        val message = buildMessage(description, params)
        if (throwable == null) Log.w(TAG, message) else Log.w(TAG, message, throwable)
    }

    fun screen(name: String) = i("SCREEN_SHOW", name)
    fun click(target: String, screen: String) = d("CLICK", "screen=$screen target=$target")
}

/** Debug-only request log. Credentials are masked, so a shared logcat never carries a password or a session. */
object NetworkLogger {
    // By name rather than by list: a field called "...Token" is a credential, including the ones added later.
    private val SECRET_FIELD = Regex("\"([A-Za-z]*[Tt]oken|password|code)\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"")
    private const val MAX_BODY = 1_500

    internal fun masked(body: String?): String? = body?.let { text ->
        SECRET_FIELD.replace(text) { "\"${it.groupValues[1]}\":\"***\"" }.take(MAX_BODY)
    }

    fun request(method: String, url: String, headers: Map<String, String>, body: String?) {
        if (!BuildConfig.DEBUG) return
        // Named here, behind the debug check, so that a release build does not even carry the names.
        val shown = headers.mapValues { (name, value) -> if (name.lowercase() in setOf("authorization", "x-dev-key")) "***" else value }
        val headerArgs = shown.entries.joinToString(" ") { "-H '${it.key}: ${it.value}'" }
        val dataArg = masked(body)?.let { " --data '${it.replace("'", "'\\''")}'" }.orEmpty()
        Logger.d("CURL", "curl -X $method $headerArgs$dataArg '$url'")
    }

    fun response(method: String, url: String, code: Int, body: String?) {
        if (!BuildConfig.DEBUG) return
        Logger.d("RESPONSE", "$method $url code=$code body=${masked(body)}")
    }
}
