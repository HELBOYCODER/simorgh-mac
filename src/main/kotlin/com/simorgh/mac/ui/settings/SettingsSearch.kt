package com.simorgh.mac.ui.settings

import androidx.compose.runtime.Stable
import com.simorgh.mac.str.Strings
import java.util.Locale

/**
 * Settings search matches every title and keyword in English and in Persian,
 * whatever the UI language is: a Persian user may type "DNS", an English one
 * may paste a Persian word from a guide. The desktop lookup reads the
 * generated string tables directly instead of Android resource contexts.
 */
@Stable
class SettingsSearchIndex {
    private val cache = HashMap<String, String>()

    private fun text(id: String): String = cache.getOrPut(id) {
        (Strings.EN[id].orEmpty() + "\n" + Strings.FA[id].orEmpty()).lowercase(Locale.ROOT)
    }

    fun matches(query: String, ids: Array<out String>): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        return ids.any { id -> normalize(text(id)).contains(q) }
    }

    companion object {
        /** Lower-case, and fold Arabic ي/ك to Persian ی/ک and drop ZWNJ so either keyboard matches. */
        fun normalize(s: String): String = s.trim().lowercase(Locale.ROOT)
            .replace('ي', 'ی').replace('ك', 'ک').replace("‌", "").replace(" ", " ")
    }
}

/** The current query, bound to an index. */
@Stable
class SettingsQuery(val text: String, private val index: SettingsSearchIndex?) {
    val isEmpty: Boolean get() = text.isBlank()
    fun hit(vararg ids: String): Boolean = isEmpty || (index?.matches(text, ids) ?: true)
}
