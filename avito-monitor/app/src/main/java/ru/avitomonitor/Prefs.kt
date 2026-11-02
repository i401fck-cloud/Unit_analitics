package ru.avitomonitor

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString("token", "").orEmpty()
        set(v) = sp.edit().putString("token", v).apply()
    var chatId: String
        get() = sp.getString("chat_id", "").orEmpty()
        set(v) = sp.edit().putString("chat_id", v).apply()
    var intervalSec: Int
        get() = sp.getInt("interval", DEFAULT_INTERVAL)
        set(v) = sp.edit().putInt("interval", v).apply()

    var attachMode: AttachMode
        get() = AttachMode.of(sp.getString("attach_mode", null))
        set(v) = sp.edit().putString("attach_mode", v.key).apply()

    /** Автоматически включать ночной экран после простоя. */
    var autoNight: Boolean
        get() = sp.getBoolean("auto_night", true)
        set(v) = sp.edit().putBoolean("auto_night", v).apply()

    /** Пользователь нажал Старт и не нажимал Стоп (для перезапуска сервиса системой). */
    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", false)
        set(v) = sp.edit().putBoolean("service_enabled", v).apply()

    // ------------------------------------------------------------------ поиски

    var searches: List<Search>
        get() {
            val raw = sp.getString(KEY_SEARCHES, null)
            if (raw == null) return migrateLegacy()
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Search::fromJson) }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(it.toJson()) }
            sp.edit().putString(KEY_SEARCHES, arr.toString()).apply()
        }

    @Synchronized
    fun updateSearch(id: String, change: (Search) -> Search?) {
        searches = searches.mapNotNull { if (it.id == id) change(it) else it }
    }

    @Synchronized
    fun addSearch(s: Search) {
        searches = searches + s
    }

    fun search(id: String): Search? = searches.firstOrNull { it.id == id }

    /** Версия 1 хранила одну ссылку и фильтры отдельными ключами — переносим в список. */
    private fun migrateLegacy(): List<Search> {
        val url = sp.getString("url", "").orEmpty()
        val list = if (url.isBlank()) emptyList() else listOf(
            Search(
                id = Search.newId(),
                name = Search.guessName(url),
                url = url,
                priceMin = sp.getString("price_min", "").orEmpty().filter { it.isDigit() }.toLongOrNull(),
                priceMax = sp.getString("price_max", "").orEmpty().filter { it.isDigit() }.toLongOrNull(),
                stopWords = Search.parseWords(sp.getString("stop_words", "").orEmpty()),
            )
        )
        searches = list
        return list
    }

    /** Ссылки, для которых уже выполнен «тихий» первый проход. */
    fun isSeeded(url: String) = url in sp.getStringSet(KEY_SEEDED, emptySet()).orEmpty()

    fun markSeeded(url: String) {
        val set = sp.getStringSet(KEY_SEEDED, emptySet()).orEmpty().toMutableSet()
        set += url
        sp.edit().putStringSet(KEY_SEEDED, set).apply()
    }

    fun clearSeeded() = sp.edit().remove(KEY_SEEDED).apply()

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    fun config() = Config(
        token = token.trim(),
        chatId = chatId.trim(),
        intervalSec = intervalSec.coerceAtLeast(MIN_INTERVAL),
        attach = attachMode,
        searches = searches,
    )

    companion object {
        const val DEFAULT_INTERVAL = 45
        const val MIN_INTERVAL = 30
        const val KEY_SEARCHES = "searches"
        private const val KEY_SEEDED = "seeded_urls"

        /** Добавляет https:// и принудительно ставит сортировку по дате (s=104). */
        fun normalizeUrl(raw: String): String {
            var s = raw.trim()
            if (s.isEmpty()) return s
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            val src = Uri.parse(s)
            val b = src.buildUpon().scheme("https").clearQuery()
            for (name in src.queryParameterNames) {
                if (name == "s") continue
                for (value in src.getQueryParameters(name)) b.appendQueryParameter(name, value)
            }
            b.appendQueryParameter("s", "104")
            return b.build().toString()
        }

        fun isAvito(url: String) = Uri.parse(url).host.orEmpty().endsWith("avito.ru")

        /** Достаёт ссылку из текста «поделиться» (там бывает название + ссылка). */
        fun extractUrl(text: String): String? =
            Regex("""https?://\S*avito\.ru\S*""").find(text)?.value?.trimEnd('.', ',', ')')
    }
}
