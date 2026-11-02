package ru.avitomonitor

import android.content.Context

/**
 * Хранилище ID уже увиденных объявлений (SharedPreferences, последние MAX_IDS штук).
 * LinkedHashSet хранит порядок добавления — самые старые ID вытесняются первыми.
 */
class SeenStore private constructor(context: Context) {
    private val sp = context.getSharedPreferences("seen_ids", Context.MODE_PRIVATE)
    private val ids = LinkedHashSet<String>(
        sp.getString(KEY, "").orEmpty().split(',').filter { it.isNotBlank() }
    )

    @Synchronized fun contains(id: String) = id in ids

    @Synchronized fun size() = ids.size

    @Synchronized fun add(id: String) {
        ids.add(id)
        persist()
    }

    @Synchronized fun addAll(newIds: Collection<String>) {
        ids.addAll(newIds)
        persist()
    }

    /** Переносит ID, которые сейчас видны на странице, в «свежий» конец, чтобы они не вытеснялись. */
    @Synchronized fun touchAll(visible: Collection<String>) {
        var changed = false
        for (id in visible) if (ids.remove(id)) { ids.add(id); changed = true }
        if (changed) persist()
    }

    @Synchronized fun clear() {
        ids.clear()
        persist()
    }

    private fun persist() {
        val it = ids.iterator()
        while (ids.size > MAX_IDS && it.hasNext()) { it.next(); it.remove() }
        sp.edit().putString(KEY, ids.joinToString(",")).apply()
    }

    companion object {
        const val MAX_IDS = 1000
        private const val KEY = "ids"

        @Volatile private var instance: SeenStore? = null

        fun get(context: Context): SeenStore =
            instance ?: synchronized(this) {
                instance ?: SeenStore(context.applicationContext).also { instance = it }
            }
    }
}
