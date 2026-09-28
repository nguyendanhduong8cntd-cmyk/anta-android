package com.alobo.clone

import android.util.Log

/**
 * Kept API-compatible with base-application-wrapper's Logger.kt. Debug logs
 * include the source location so logcat entries remain clickable in Android Studio.
 */
object Logger {
    private const val TAG = "VN_NAMI"
    private val DEBUG = BuildConfig.DEBUG

    private fun getCallerInfo(): String {
        if (!DEBUG) return ""
        val loggerClassName = Logger::class.java.name
        return Thread.currentThread().stackTrace
            .drop(2)
            .firstOrNull {
                it.className != loggerClassName &&
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

    fun v(description: String, params: Any? = null) {
        if (DEBUG) Log.v(TAG, buildMessage(description, params))
    }

    fun screen(name: String) = i("SCREEN_SHOW", name)
    fun dialog(name: String) = i("DIALOG_SHOW", name)
    fun click(target: String, screen: String) = d("CLICK", "screen=$screen target=$target")
}

object NetworkLogger {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?) {
        val headerArgs = headers.entries.joinToString(" ") { "-H '${it.key}: ${it.value}'" }
        val dataArg = body?.let { " --data '${it.replace("'", "'\\''")}'" }.orEmpty()
        Logger.d("CURL", "curl -X $method $headerArgs$dataArg '$url'")
        Logger.d("REQUEST", "method=$method url=$url headers=$headers body=$body")
    }

    fun response(url: String, code: Int, body: String?) {
        Logger.d("RESPONSE", "url=$url code=$code body=$body")
    }
}
