package ru.avitomonitor

import android.content.Context
import android.net.Uri

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "").orEmpty()
        set(v) = sp.edit().putString("url", v).apply()
    var token: String
        get() = sp.getString("token", "").orEmpty()
        set(v) = sp.edit().putString("token", v).apply()
    var chatId: String
        get() = sp.getString("chat_id", "").orEmpty()
        set(v) = sp.edit().putString("chat_id", v).apply()
    var intervalSec: Int
        get() = sp.getInt("interval", DEFAULT_INTERVAL)
        set(v) = sp.edit().putInt("interval", v).apply()
    var stopWords: String
        get() = sp.getString("stop_words", "").orEmpty()
        set(v) = sp.edit().putString("stop_words", v).apply()
    var priceMin: String
        get() = sp.getString("price_min", "").orEmpty()
        set(v) = sp.edit().putString("price_min", v).apply()
    var priceMax: String
        get() = sp.getString("price_max", "").orEmpty()
        set(v) = sp.edit().putString("price_max", v).apply()

    /** URL, для которого уже выполнен «тихий» первый проход. */
    var seededUrl: String
        get() = sp.getString("seeded_url", "").orEmpty()
        set(v) = sp.edit().putString("seeded_url", v).apply()

    /** Пользователь нажал Старт и не нажимал Стоп (для перезапуска сервиса системой). */
    var serviceEnabled: Boolean
        get() = sp.getBoolean("service_enabled", false)
        set(v) = sp.edit().putBoolean("service_enabled", v).apply()

    fun config() = Config(
        url = url,
        token = token.trim(),
        chatId = chatId.trim(),
        intervalSec = intervalSec.coerceAtLeast(MIN_INTERVAL),
        stopWords = stopWords.split(',', ';', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
        priceMin = priceMin.filter { it.isDigit() }.toLongOrNull(),
        priceMax = priceMax.filter { it.isDigit() }.toLongOrNull(),
    )

    companion object {
        const val DEFAULT_INTERVAL = 45
        const val MIN_INTERVAL = 30

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
    }
}
