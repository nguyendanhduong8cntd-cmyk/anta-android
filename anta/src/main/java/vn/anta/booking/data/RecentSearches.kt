package vn.anta.booking.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException

/** The last few things the user searched for, newest first. Kept on the phone only. */
class RecentSearches(context: Context) {
    private val prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val terms: StateFlow<List<String>> = state.asStateFlow()

    fun add(term: String) = store(remember(state.value, term))

    fun clear() = store(emptyList())

    private fun store(terms: List<String>) {
        state.value = terms
        prefs.edit().putString(KEY, JSONArray(terms).toString()).apply()
    }

    private fun load(): List<String> = try {
        val saved = JSONArray(prefs.getString(KEY, "[]"))
        (0 until saved.length()).map(saved::getString).take(LIMIT)
    } catch (_: JSONException) {
        emptyList()
    }

    companion object {
        private const val KEY = "recent"
        const val LIMIT = 8

        /** [terms] with [term] moved to the front; a blank term changes nothing and case does not make a new entry. */
        fun remember(terms: List<String>, term: String): List<String> {
            val clean = term.trim()
            if (clean.isEmpty()) return terms
            return (listOf(clean) + terms.filterNot { it.equals(clean, ignoreCase = true) }).take(LIMIT)
        }
    }
}
