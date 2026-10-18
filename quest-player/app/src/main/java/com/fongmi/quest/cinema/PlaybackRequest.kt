package com.fongmi.quest.cinema

/** FongMi's headers extra is an alternating name/value string array, in milliseconds. */
object PlaybackRequest {
    fun headers(values: Array<String>?, additional: Map<String, String> = emptyMap()): Map<String, String> {
        val result = linkedMapOf<String, String>()
        additional.forEach { (name, value) -> addHeader(result, name, value) }
        values?.asList()?.chunked(2)?.forEach { pair ->
            if (pair.size == 2) addHeader(result, pair[0], pair[1])
        }
        return result
    }

    private fun addHeader(result: MutableMap<String, String>, name: String, value: String) {
        if (name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && !value.contains('\r') && !value.contains('\n')) {
            val old = result.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (old != null) result.remove(old)
            result[name] = value
        }
    }

    fun startPosition(value: Any?): Long = (value as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
}
